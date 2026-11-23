package com.eraassistant.mobile.tools

import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.domain.AgentDomain
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.planner.TaskCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutonomyToolSelectorTest {
    private class FakeDeviceAdapter(
        private val availability: CapabilityAvailability = CapabilityAvailability.AVAILABLE
    ) : CapabilityAdapter {
        override val descriptor = CapabilityDescriptor(
            id = CapabilityId.ANDROID_DEVICE,
            name = "Android Device",
            description = "Test device adapter",
            category = CapabilityCategory.DEVICE_CONTROL,
            supportedActions = setOf("device.app.launch"),
            worksOffline = true
        )
        override suspend fun checkAvailability() = availability
        override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? = null
        override suspend fun execute(request: StructuredActionRequest) =
            StructuredActionResult.success(request.actionId)
    }

    @Test fun selectsExactRegisteredActionWhenAvailable() = runBlocking {
        val registry = CapabilityRegistry().apply { register(FakeDeviceAdapter()) }
        val result = AutonomyToolSelector(registry).select(ToolSelectionRequest(
            capability = TaskCapability.APP_LAUNCH,
            domains = setOf(AgentDomain.ANDROID_OS),
            sensitivity = ToolDataSensitivity.PERSONAL
        ))
        assertEquals(ToolSelectionStatus.SELECTED, result.status)
        assertEquals("device.app.launch", result.selected?.actionName)
    }

    @Test fun neverSelectsToolAbovePrivacyCeiling() = runBlocking {
        val registry = CapabilityRegistry().apply { register(FakeDeviceAdapter()) }
        val result = AutonomyToolSelector(registry).select(ToolSelectionRequest(
            TaskCapability.APP_LAUNCH, setOf(AgentDomain.ANDROID_OS), ToolDataSensitivity.SENSITIVE
        ))
        assertEquals(ToolSelectionStatus.PRIVACY_BLOCKED, result.status)
    }

    @Test fun reportsPermissionRequirementWithoutAutoGranting() = runBlocking {
        val registry = CapabilityRegistry().apply {
            register(FakeDeviceAdapter(CapabilityAvailability.missing("Camera permission missing", setOf("CAMERA"))))
        }
        val result = AutonomyToolSelector(registry).select(ToolSelectionRequest(
            TaskCapability.APP_LAUNCH, setOf(AgentDomain.ANDROID_OS)
        ))
        assertEquals(ToolSelectionStatus.PERMISSION_REQUIRED, result.status)
        assertNull(result.selected)
    }
}
