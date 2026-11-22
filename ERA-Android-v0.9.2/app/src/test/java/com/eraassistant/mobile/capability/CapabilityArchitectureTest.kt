package com.eraassistant.mobile.capability

import com.eraassistant.mobile.capability.adapters.AndroidDeviceCapabilityAdapter
import com.eraassistant.mobile.capability.adapters.NativeWebViewBrowserCapabilityAdapter
import com.eraassistant.mobile.observation.NormalizedObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CapabilityArchitectureTest {

    private lateinit var registry: CapabilityRegistry

    @Before
    fun setup() {
        registry = CapabilityRegistry()
    }

    @Test
    fun testCapabilityDescriptorAndContract() {
        val descriptor = CapabilityDescriptor(
            id = CapabilityId("test.cap.mock"),
            name = "Mock Capability",
            description = "A mock capability for testing contracts.",
            category = CapabilityCategory.DEVICE_CONTROL,
            supportedActions = setOf("mock.action.run", "mock.action.stop")
        )

        assertEquals("test.cap.mock", descriptor.id.value)
        assertEquals(2, descriptor.supportedActions.size)
        assertTrue(descriptor.worksOffline)
    }

    @Test
    fun testCapabilityRegistryRegistrationAndDiscovery() = runBlocking {
        val adapter = object : CapabilityAdapter {
            override val descriptor = CapabilityDescriptor(
                id = CapabilityId("test.sample"),
                name = "Sample Adapter",
                description = "Sample adapter for registry test",
                category = CapabilityCategory.INFORMATION_SEARCH,
                supportedActions = setOf("sample.search")
            )
            override suspend fun checkAvailability(): CapabilityAvailability = CapabilityAvailability.AVAILABLE
            override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? = null
            override suspend fun execute(request: StructuredActionRequest): StructuredActionResult =
                StructuredActionResult.success(request.actionId, mapOf("result" to "found"))
        }

        registry.register(adapter)
        assertEquals(1, registry.size())
        assertEquals(adapter, registry.getAdapter(CapabilityId("test.sample")))
        assertEquals(adapter, registry.findCapabilityForAction("sample.search"))

        val available = registry.discoverAvailableCapabilities()
        assertTrue(available[adapter.descriptor]?.isAvailable == true)

        val resolved = registry.validateAndResolve(
            StructuredActionRequest(
                actionId = "a1",
                idempotencyKey = "k1",
                capabilityId = CapabilityId("test.sample"),
                actionName = "sample.search"
            )
        )
        assertTrue(resolved.isSuccess)
    }

    @Test
    fun testActionValidationRejectsInvalidParameters() = runBlocking {
        val adapter = object : CapabilityAdapter {
            override val descriptor = CapabilityDescriptor(
                id = CapabilityId("test.validator"),
                name = "Validator Adapter",
                description = "Validates request parameters",
                category = CapabilityCategory.DEVICE_CONTROL,
                supportedActions = setOf("validator.run")
            )
            override suspend fun checkAvailability(): CapabilityAvailability = CapabilityAvailability.AVAILABLE
            override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
                val count = request.param<Int>("count")
                if (count == null || count <= 0) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'count' must be positive.")
                }
                return null
            }
            override suspend fun execute(request: StructuredActionRequest): StructuredActionResult =
                StructuredActionResult.success(request.actionId)
        }

        registry.register(adapter)

        val invalidRequest = StructuredActionRequest(
            actionId = "a_bad",
            idempotencyKey = "k_bad",
            capabilityId = CapabilityId("test.validator"),
            actionName = "validator.run",
            parameters = mapOf("count" to -5)
        )
        val resolveResult = registry.validateAndResolve(invalidRequest)
        assertTrue(resolveResult.isFailure)
        assertTrue(resolveResult.exceptionOrNull()?.message?.contains("Precondition validation failed") == true)
    }

    @Test
    fun typedActionSchemasRejectWrongTypesMissingAndUndeclaredParameters() = runBlocking {
        var calls = 0
        val adapter = object : CapabilityAdapter {
            override val descriptor = CapabilityDescriptor(
                id = CapabilityId("test.typed"),
                name = "Typed Adapter",
                description = "Uses strict request schema",
                category = CapabilityCategory.DEVICE_CONTROL,
                supportedActions = setOf("typed.action"),
                actionSchemas = mapOf(
                    "typed.action" to CapabilityActionSchema(
                        parameters = mapOf(
                            "count" to CapabilityParameterSchema(
                                type = CapabilityParameterType.INTEGER,
                                required = true,
                                minNumber = 1.0,
                                maxNumber = 10.0
                            )
                        )
                    )
                )
            )
            override suspend fun checkAvailability() = CapabilityAvailability.AVAILABLE
            override fun validatePreconditions(request: StructuredActionRequest) = null
            override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
                calls++
                return StructuredActionResult.success(request.actionId, mapOf("accepted" to true))
            }
        }
        registry.register(adapter)

        val wrongType = registry.validateAndResolve(
            StructuredActionRequest("a1", "k1", adapter.descriptor.id, "typed.action", mapOf("count" to "5"))
        )
        assertTrue(wrongType.isFailure)
        assertTrue(wrongType.exceptionOrNull()?.message?.contains("INTEGER") == true)

        val missing = registry.validateAndResolve(
            StructuredActionRequest("a2", "k2", adapter.descriptor.id, "typed.action")
        )
        assertTrue(missing.isFailure)
        assertTrue(missing.exceptionOrNull()?.message?.contains("Required parameter") == true)

        val undeclared = registry.validateAndResolve(
            StructuredActionRequest("a3", "k3", adapter.descriptor.id, "typed.action", mapOf("count" to 5, "shell" to "no"))
        )
        assertTrue(undeclared.isFailure)
        assertEquals(0, calls)

        val validRequest = StructuredActionRequest("a4", "k4", adapter.descriptor.id, "typed.action", mapOf("count" to 5))
        val valid = registry.validateAndResolve(validRequest)
        assertTrue(valid.isSuccess)
        val validOutput = registry.validateOutput(validRequest, valid.getOrThrow().execute(validRequest))
        assertEquals(ActionResultStatus.SUCCESS, validOutput.status)
        assertEquals(mapOf("accepted" to true), validOutput.outputData)
    }

    @Test
    fun adapterOutputMustPreserveActionIdentityAndBoundPayloads() {
        val adapterId = CapabilityId("test.output")
        val request = StructuredActionRequest("expected", "key", adapterId, "output.action")
        val wrongIdentity = registry.validateOutput(
            request,
            StructuredActionResult.success("different")
        )
        assertEquals(ActionResultStatus.FAILURE, wrongIdentity.status)
        assertTrue(wrongIdentity.error?.message?.contains("action ID") == true)

        val oversized = registry.validateOutput(
            request,
            StructuredActionResult.success("expected", mapOf("payload" to "x".repeat(4_001)))
        )
        assertEquals(ActionResultStatus.FAILURE, oversized.status)
    }

    @Test
    fun testBrowserAdapterCompatibility() = runBlocking {
        val adapter = NativeWebViewBrowserCapabilityAdapter(clientProvider = { null })
        assertEquals(CapabilityId.NATIVE_WEBVIEW, adapter.descriptor.id)
        assertTrue(adapter.descriptor.supportedActions.contains(NativeWebViewBrowserCapabilityAdapter.ACTION_NAVIGATE))
        assertTrue(adapter.descriptor.supportedActions.contains(NativeWebViewBrowserCapabilityAdapter.ACTION_CLICK))
        assertTrue(adapter.descriptor.supportedActions.contains(NativeWebViewBrowserCapabilityAdapter.ACTION_FILL))

        // Check availability without initialized WebView
        val availability = adapter.checkAvailability()
        assertFalse(availability.isAvailable)
        assertTrue(availability.unavailableReason?.contains("not initialized") == true)
    }
}
