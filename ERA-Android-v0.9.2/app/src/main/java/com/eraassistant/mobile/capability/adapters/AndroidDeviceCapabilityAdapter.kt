package com.eraassistant.mobile.capability.adapters

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import com.eraassistant.mobile.device.DeviceActionStatus
import com.eraassistant.mobile.device.DeviceActionErrorCode
import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.core.VolumeDirection
import com.eraassistant.mobile.core.VolumeStream
import com.eraassistant.mobile.device.DeviceController
import com.eraassistant.mobile.observation.NormalizedObservation

/**
 * Adapter exposing Android device controls:
 * app launch, torch, volume, camera, dialer, settings, SMS draft, WhatsApp draft.
 */
class AndroidDeviceCapabilityAdapter(
    private val context: Context,
    private val controller: DeviceController = DeviceController(context)
) : CapabilityAdapter {

    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId.ANDROID_DEVICE,
        name = "Android Device Controller",
        description = "Controls basic Android system and device features: app launch, flashlight, volume, camera, dialer, settings, and messaging drafts.",
        category = CapabilityCategory.DEVICE_CONTROL,
        supportedActions = setOf(
            ACTION_SET_TORCH,
            ACTION_ADJUST_VOLUME,
            ACTION_SET_VOLUME_PERCENT,
            ACTION_LAUNCH_APP,
            ACTION_OPEN_SETTINGS,
            ACTION_OPEN_CAMERA,
            ACTION_OPEN_DIALER,
            ACTION_CREATE_SMS_DRAFT,
            ACTION_SHARE_WHATSAPP_DRAFT,
            ACTION_WEB_SEARCH,
            ACTION_MAP_SEARCH,
            ACTION_OPEN_WEBSITE
        ),
        requiredPermissions = emptySet(),
        executionConstraints = setOf("Foreground Activity required for Intent launches", "Flashlight hardware required for torch"),
        worksOffline = true,
        actionSchemas = CapabilitySchemas.androidDevice()
    )

    override suspend fun checkAvailability(): CapabilityAvailability =
        CapabilityAvailability.AVAILABLE

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
        when (request.actionName) {
            ACTION_SET_TORCH -> {
                if (request.param<Boolean>("enabled") == null) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'enabled' (Boolean) is required for $ACTION_SET_TORCH.")
                }
            }
            ACTION_ADJUST_VOLUME -> {
                if (request.param<Boolean>("increase") == null && request.param<String>("direction") == null) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'increase' (Boolean) or 'direction' (UP/DOWN) is required.")
                }
            }
            ACTION_SET_VOLUME_PERCENT -> {
                val percent = request.param<Int>("percent")
                if (percent == null || percent !in 0..100) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'percent' (0..100) is required.")
                }
            }
            ACTION_LAUNCH_APP -> {
                val appName = request.param<String>("appName")
                val packageName = request.param<String>("packageName")
                if (appName.isNullOrBlank() && packageName.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Either 'appName' or 'packageName' is required.")
                }
            }
            ACTION_OPEN_DIALER -> {
                val number = request.param<String>("phoneNumber")
                if (number.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'phoneNumber' is required.")
                }
            }
            ACTION_CREATE_SMS_DRAFT -> {
                val message = request.param<String>("message")
                if (message.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'message' is required.")
                }
            }
            ACTION_SHARE_WHATSAPP_DRAFT -> {
                val message = request.param<String>("message")
                if (message.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'message' is required.")
                }
            }
        }
        return null
    }

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val start = SystemClock.elapsedRealtime()
        val typed = controller.executeTyped(request.actionName, request.parameters)
        if (typed.status == DeviceActionStatus.CANCELLED) {
            return StructuredActionResult.cancelled(request.actionId, typed.message, SystemClock.elapsedRealtime() - start)
        }

        // CameraManager delivers torch state through TorchCallback; allow the callback
        // queue to publish the actual value before collecting the post-action observation.
        if (request.actionName == ACTION_SET_TORCH && typed.status == DeviceActionStatus.SUCCESS) {
            delay(180L)
        }
        val metadata = controller.verificationMetadata(request.actionName, typed.metadata)
        val observation = com.eraassistant.mobile.observation.NormalizedObservation.fromSystemState(
            summary = typed.message,
            details = metadata
        )
        val duration = SystemClock.elapsedRealtime() - start
        val output = mapOf(
            "message" to typed.message,
            "deviceActionStatus" to typed.status.name,
            "deviceActionErrorCode" to typed.errorCode.name
        )

        return when (typed.status) {
            DeviceActionStatus.SUCCESS -> StructuredActionResult.success(
                actionId = request.actionId,
                outputData = output,
                observation = observation,
                durationMs = duration
            )
            DeviceActionStatus.UNSUPPORTED -> StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(
                    ExecutionErrorCategory.UNSUPPORTED_CAPABILITY,
                    typed.message,
                    detail = typed.errorCode.name
                ),
                observation = observation,
                durationMs = duration
            )
            DeviceActionStatus.UNAVAILABLE -> StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(
                    when (typed.errorCode) {
                        DeviceActionErrorCode.PERMISSION_DENIED,
                        DeviceActionErrorCode.SECURITY_RESTRICTED -> ExecutionErrorCategory.PERMISSION_DENIED
                        DeviceActionErrorCode.ACTIVITY_NOT_FOUND,
                        DeviceActionErrorCode.APP_NOT_INSTALLED,
                        DeviceActionErrorCode.APP_AMBIGUOUS,
                        DeviceActionErrorCode.HARDWARE_UNAVAILABLE -> ExecutionErrorCategory.PRECONDITION_FAILED
                        else -> ExecutionErrorCategory.ENVIRONMENT_ERROR
                    },
                    typed.message,
                    retryable = false,
                    detail = typed.errorCode.name
                ),
                observation = observation,
                durationMs = duration
            )
            DeviceActionStatus.FAILURE -> StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(
                    if (typed.errorCode == DeviceActionErrorCode.PERMISSION_DENIED ||
                        typed.errorCode == DeviceActionErrorCode.SECURITY_RESTRICTED
                    ) ExecutionErrorCategory.PERMISSION_DENIED else ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE,
                    typed.message,
                    retryable = false,
                    detail = typed.errorCode.name
                ),
                observation = observation,
                durationMs = duration
            )
            DeviceActionStatus.CANCELLED -> StructuredActionResult.cancelled(
                request.actionId, typed.message, duration
            )
        }
    }

    companion object {
        const val ACTION_SET_TORCH = "device.torch.set"
        const val ACTION_ADJUST_VOLUME = "device.volume.adjust"
        const val ACTION_SET_VOLUME_PERCENT = "device.volume.set_percent"
        const val ACTION_LAUNCH_APP = "device.app.launch"
        const val ACTION_OPEN_SETTINGS = "device.settings.open"
        const val ACTION_OPEN_CAMERA = "device.camera.open"
        const val ACTION_OPEN_DIALER = "device.dialer.open"
        const val ACTION_CREATE_SMS_DRAFT = "device.sms.create_draft"
        const val ACTION_SHARE_WHATSAPP_DRAFT = "device.whatsapp.share_draft"
        const val ACTION_WEB_SEARCH = "device.web.search"
        const val ACTION_MAP_SEARCH = "device.maps.search"
        const val ACTION_OPEN_WEBSITE = "device.website.open"
    }
}
