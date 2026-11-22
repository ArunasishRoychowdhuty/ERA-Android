package com.eraassistant.mobile.capability

import com.eraassistant.mobile.observation.NormalizedObservation

/**
 * Unique identifier for an execution capability.
 */
data class CapabilityId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 64) { "CapabilityId must be between 1 and 64 characters." }
        require(VALID_ID_REGEX.matches(value)) { "CapabilityId '$value' must contain only alphanumeric characters, dots, underscores, or hyphens." }
    }

    override fun toString(): String = value

    companion object {
        private val VALID_ID_REGEX = Regex("^[a-zA-Z0-9._-]+$")
        val ANDROID_DEVICE = CapabilityId("android.device.controller")
        val NATIVE_WEBVIEW = CapabilityId("browser.native.webview")
        val EXTERNAL_CHROME = CapabilityId("browser.external.chrome")
        val ACCESSIBILITY_UI = CapabilityId("android.accessibility.ui")
        val VISUAL_INSPECTION = CapabilityId("screen.visual.inspection")
        val SEARCH_AND_RECOGNITION = CapabilityId("search.product.recognition")
        val FILE_STORAGE = CapabilityId("android.storage.saf")
    }
}

/**
 * Standard classification of capabilities.
 */
enum class CapabilityCategory {
    DEVICE_CONTROL,
    WEB_BROWSER,
    ACCESSIBILITY_AUTOMATION,
    VISUAL_UNDERSTANDING,
    INFORMATION_SEARCH,
    FILE_STORAGE,
    EXTERNAL_API
}

/**
 * Failure categories for structured action execution.
 */
enum class ExecutionErrorCategory {
    INVALID_REQUEST,
    UNSUPPORTED_CAPABILITY,
    PRECONDITION_FAILED,
    IDEMPOTENCY_CONFLICT,
    TIMEOUT,
    PERMISSION_DENIED,
    ENVIRONMENT_ERROR,
    STALE_STATE,
    RECOVERY_REQUIRED,
    CONSEQUENTIAL_APPROVAL_REQUIRED,
    ADAPTER_EXECUTION_FAILURE
}

/**
 * Typed execution error with recovery and retry semantics.
 */
data class ExecutionError(
    val category: ExecutionErrorCategory,
    val message: String,
    val retryable: Boolean = false,
    val detail: String? = null,
    val cause: Throwable? = null
) {
    override fun toString(): String = "[$category] $message${detail?.let { " ($it)" } ?: ""}"
}

/**
 * Risk classification for an action request.
 */
enum class ActionRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Structured request sent to a capability adapter.
 */
data class StructuredActionRequest(
    val actionId: String,
    val idempotencyKey: String,
    val capabilityId: CapabilityId,
    val actionName: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val targetObservationId: String? = null,
    val timeoutMs: Long = 15_000L,
    val requiresUserApproval: Boolean = false,
    val approvalGranted: Boolean = false,
    val approvalReceiptId: String? = null,
    val riskLevel: ActionRiskLevel = ActionRiskLevel.LOW,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(actionId.isNotBlank() && actionId.length <= 128) { "Action ID must be between 1 and 128 characters." }
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160) { "Idempotency key must be between 1 and 160 characters." }
        require(actionName.isNotBlank() && actionName.length <= 64) { "Action name must be between 1 and 64 characters." }
        require(timeoutMs in 100L..300_000L) { "Timeout must be between 100ms and 5 minutes." }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> param(key: String): T? = parameters[key] as? T

    fun stringParam(key: String, default: String = ""): String =
        parameters[key]?.toString() ?: default

    fun boolParam(key: String, default: Boolean = false): Boolean =
        (parameters[key] as? Boolean) ?: parameters[key]?.toString()?.toBooleanStrictOrNull() ?: default

    fun intParam(key: String, default: Int = 0): Int =
        (parameters[key] as? Number)?.toInt() ?: parameters[key]?.toString()?.toIntOrNull() ?: default
}

/**
 * Status of a structured action result.
 */
enum class ActionResultStatus {
    SUCCESS,
    FAILURE,
    WAITING_USER,
    CANCELLED
}

/**
 * Structured result returned by a capability adapter.
 */
data class StructuredActionResult(
    val actionId: String,
    val status: ActionResultStatus,
    val outputData: Map<String, Any?> = emptyMap(),
    val observation: NormalizedObservation? = null,
    val error: ExecutionError? = null,
    val executionDurationMs: Long = 0L,
    val completedAtEpochMs: Long = System.currentTimeMillis()
) {
    val isSuccess: Boolean get() = status == ActionResultStatus.SUCCESS
    val isFailure: Boolean get() = status == ActionResultStatus.FAILURE

    companion object {
        fun success(
            actionId: String,
            outputData: Map<String, Any?> = emptyMap(),
            observation: NormalizedObservation? = null,
            durationMs: Long = 0L
        ): StructuredActionResult = StructuredActionResult(
            actionId = actionId,
            status = ActionResultStatus.SUCCESS,
            outputData = outputData,
            observation = observation,
            executionDurationMs = durationMs
        )

        fun failure(
            actionId: String,
            error: ExecutionError,
            observation: NormalizedObservation? = null,
            durationMs: Long = 0L
        ): StructuredActionResult = StructuredActionResult(
            actionId = actionId,
            status = ActionResultStatus.FAILURE,
            outputData = emptyMap(),
            observation = observation,
            error = error,
            executionDurationMs = durationMs
        )

        fun waitingUser(
            actionId: String,
            message: String,
            outputData: Map<String, Any?> = emptyMap(),
            durationMs: Long = 0L
        ): StructuredActionResult = StructuredActionResult(
            actionId = actionId,
            status = ActionResultStatus.WAITING_USER,
            outputData = outputData + ("userPrompt" to message),
            executionDurationMs = durationMs
        )

        fun cancelled(
            actionId: String,
            reason: String = "Action cancelled by user or coordinator.",
            durationMs: Long = 0L
        ): StructuredActionResult = StructuredActionResult(
            actionId = actionId,
            status = ActionResultStatus.CANCELLED,
            error = ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, reason),
            executionDurationMs = durationMs
        )
    }
}

/**
 * Metadata descriptor for a capability.
 */
data class CapabilityDescriptor(
    val id: CapabilityId,
    val name: String,
    val description: String,
    val category: CapabilityCategory,
    val supportedActions: Set<String>,
    val requiredPermissions: Set<String> = emptySet(),
    val executionConstraints: Set<String> = emptySet(),
    val worksOffline: Boolean = true,
    val actionSchemas: Map<String, CapabilityActionSchema> = emptyMap()
)

/**
 * Core interface for every execution engine capability adapter.
 */
interface CapabilityAdapter {
    val descriptor: CapabilityDescriptor

    /**
     * Checks whether this capability is currently available in the environment.
     */
    suspend fun checkAvailability(): CapabilityAvailability

    /**
     * Validates whether a given action request can be executed by this adapter.
     */
    fun validatePreconditions(request: StructuredActionRequest): ExecutionError?

    /**
     * Executes the requested action.
     */
    suspend fun execute(request: StructuredActionRequest): StructuredActionResult
}

/**
 * Availability status of a capability.
 */
data class CapabilityAvailability(
    val isAvailable: Boolean,
    val missingPermissions: Set<String> = emptySet(),
    val unavailableReason: String? = null
) {
    companion object {
        val AVAILABLE = CapabilityAvailability(true)
        fun missing(reason: String, permissions: Set<String> = emptySet()): CapabilityAvailability =
            CapabilityAvailability(false, permissions, reason)
    }
}
