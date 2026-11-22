package com.eraassistant.mobile.capability

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Central capability registry. Planning refers to IDs/actions; only this class resolves adapters. */
class CapabilityRegistry {
    private val adapters = ConcurrentHashMap<CapabilityId, CapabilityAdapter>()
    private val actionToCapability = ConcurrentHashMap<String, CapabilityId>()

    @Synchronized
    fun register(adapter: CapabilityAdapter) {
        val descriptor = adapter.descriptor
        require(descriptor.name.isNotBlank() && descriptor.description.isNotBlank()) {
            "Capability name and description must not be blank."
        }
        require(descriptor.supportedActions.isNotEmpty()) { "A capability must declare supported actions." }
        require(descriptor.supportedActions.all { it.isNotBlank() }) { "Capability action names must not be blank." }
        require(descriptor.actionSchemas.keys.all { it in descriptor.supportedActions }) {
            "Action schemas may only be declared for actions supported by this adapter."
        }
        require(!adapters.containsKey(descriptor.id)) { "Capability '${descriptor.id}' is already registered." }

        // Validate every action before mutating either map, preventing a partial registration.
        val conflicts = descriptor.supportedActions.mapNotNull { action ->
            actionToCapability[action]?.let { action to it }
        }
        require(conflicts.isEmpty()) {
            conflicts.joinToString { (action, owner) -> "Action '$action' is already owned by '$owner'." }
        }

        adapters[descriptor.id] = adapter
        descriptor.supportedActions.forEach { action -> actionToCapability[action] = descriptor.id }
    }

    @Synchronized
    fun unregister(capabilityId: CapabilityId) {
        val adapter = adapters.remove(capabilityId) ?: return
        adapter.descriptor.supportedActions.forEach { action ->
            actionToCapability.remove(action, capabilityId)
        }
    }

    fun getAdapter(id: CapabilityId): CapabilityAdapter? = adapters[id]
    fun containsCapability(id: CapabilityId): Boolean = adapters.containsKey(id)

    fun findCapabilityForAction(actionName: String): CapabilityAdapter? {
        val capabilityId = actionToCapability[actionName] ?: return null
        return adapters[capabilityId]
    }

    fun allDescriptors(): List<CapabilityDescriptor> =
        adapters.values.map { it.descriptor }.sortedBy { it.id.value }

    fun supportedActions(): Set<String> = actionToCapability.keys.toSet()

    /** Reports availability for every currently registered adapter. */
    suspend fun discoverAvailableCapabilities(): Map<CapabilityDescriptor, CapabilityAvailability> {
        val result = mutableMapOf<CapabilityDescriptor, CapabilityAvailability>()
        for (adapter in adapters.values) {
            val availability = try {
                adapter.checkAvailability()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CapabilityAvailability.missing("Availability check failed: ${safeMessage(error)}")
            }
            result[adapter.descriptor] = availability
        }
        return result
    }

    /** Resolves only the exact declared CapabilityId; action-name fallback is intentionally forbidden. */
    suspend fun validateAndResolve(request: StructuredActionRequest): Result<CapabilityAdapter> {
        val adapter = adapters[request.capabilityId]
            ?: return Result.failure(
                IllegalArgumentException("Requested capability '${request.capabilityId}' is not registered.")
            )

        if (request.actionName !in adapter.descriptor.supportedActions) {
            return Result.failure(
                IllegalArgumentException("Capability '${adapter.descriptor.id}' does not support action '${request.actionName}'.")
            )
        }

        val schema = adapter.descriptor.actionSchemas[request.actionName]
        if (schema != null) {
            val schemaError = validateParameters(schema, request.parameters)
            if (schemaError != null) return Result.failure(IllegalArgumentException(schemaError))
        }

        val availability = try {
            adapter.checkAvailability()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CapabilityAvailability.missing("Availability check failed: ${safeMessage(error)}")
        }
        if (!availability.isAvailable) {
            val permissions = availability.missingPermissions.joinToString().takeIf(String::isNotBlank)
            return Result.failure(
                IllegalStateException(
                    "Capability '${adapter.descriptor.id}' is unavailable: ${availability.unavailableReason.orEmpty()}" +
                        (permissions?.let { " Missing: $it" } ?: "")
                )
            )
        }

        val error = adapter.validatePreconditions(request)
        if (error != null) {
            return Result.failure(IllegalStateException("Precondition validation failed: $error"))
        }
        return Result.success(adapter)
    }

    /**
     * Validates adapter output before it reaches the planner, journal, or UI.
     * The adapter must not change action identity or return an unbounded payload.
     */
    fun validateOutput(
        request: StructuredActionRequest,
        result: StructuredActionResult
    ): StructuredActionResult {
        val invalidReason = when {
            result.actionId != request.actionId -> "Adapter returned an action ID that does not match the dispatched request."
            result.outputData.size > MAX_OUTPUT_FIELDS -> "Adapter output contains too many fields."
            result.outputData.any { (key, value) ->
                key.isBlank() || key.length > MAX_OUTPUT_KEY_LENGTH ||
                    (value is String && value.length > MAX_OUTPUT_VALUE_LENGTH) ||
                    (value != null && value !is String && value !is Number && value !is Boolean)
            } -> "Adapter output contains an unsupported or overlong value."
            else -> null
        }
        return if (invalidReason == null) result else StructuredActionResult.failure(
            actionId = request.actionId,
            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, invalidReason)
        )
    }

    private fun validateParameters(
        schema: CapabilityActionSchema,
        parameters: Map<String, Any?>
    ): String? {
        if (parameters.size > schema.maxParameters) return "Action contains more parameters than its schema permits."
        if (!schema.allowAdditionalParameters && parameters.keys.any { it !in schema.parameters }) {
            return "Action contains an undeclared parameter."
        }
        schema.parameters.forEach { (name, definition) ->
            if (definition.required && (!parameters.containsKey(name) || parameters[name] == null)) {
                return "Required parameter '$name' is missing."
            }
            val value = parameters[name] ?: return@forEach
            val valid = when (definition.type) {
                CapabilityParameterType.STRING -> value is String &&
                    value.length <= definition.maxLength &&
                    (value.isNotBlank() || !definition.required) &&
                    (definition.allowedValues.isEmpty() || definition.allowedValues.any { it.equals(value, ignoreCase = true) })
                CapabilityParameterType.BOOLEAN -> value is Boolean
                CapabilityParameterType.INTEGER -> value is Number && value.toDouble().isFinite() &&
                    value.toDouble() % 1.0 == 0.0
                CapabilityParameterType.NUMBER -> value is Number && value.toDouble().isFinite()
            }
            if (!valid) return "Parameter '$name' does not match its declared ${definition.type} schema."
            if (value is Number) {
                if (definition.minNumber != null && value.toDouble() < definition.minNumber) {
                    return "Parameter '$name' is below its allowed minimum."
                }
                if (definition.maxNumber != null && value.toDouble() > definition.maxNumber) {
                    return "Parameter '$name' exceeds its allowed maximum."
                }
            }
        }
        return null
    }

    fun size(): Int = adapters.size

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "unknown").take(180)

    private companion object {
        const val MAX_OUTPUT_FIELDS = 32
        const val MAX_OUTPUT_KEY_LENGTH = 80
        const val MAX_OUTPUT_VALUE_LENGTH = 4_000
    }
}
