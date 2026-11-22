package com.eraassistant.mobile.capability.adapters

import android.content.Context
import android.os.SystemClock
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
        return try {
            val message = when (request.actionName) {
                ACTION_SET_TORCH -> {
                    val enabled = request.boolParam("enabled", false)
                    controller.setTorch(enabled)
                }
                ACTION_ADJUST_VOLUME -> {
                    val streamName = request.stringParam("stream", "MEDIA")
                    val stream = runCatching { VolumeStream.valueOf(streamName) }.getOrDefault(VolumeStream.MEDIA)
                    val dirName = request.stringParam("direction", "")
                    if (dirName.isNotBlank()) {
                        val dir = if (dirName.equals("UP", ignoreCase = true)) VolumeDirection.UP else VolumeDirection.DOWN
                        controller.adjustVolume(stream, dir)
                    } else {
                        val increase = request.boolParam("increase", true)
                        controller.adjustVolume(increase)
                    }
                }
                ACTION_SET_VOLUME_PERCENT -> {
                    val streamName = request.stringParam("stream", "MEDIA")
                    val stream = runCatching { VolumeStream.valueOf(streamName) }.getOrDefault(VolumeStream.MEDIA)
                    val percent = request.intParam("percent", 50)
                    controller.setVolumePercent(stream, percent)
                }
                ACTION_LAUNCH_APP -> {
                    val packageName = request.stringParam("packageName", "")
                    val appName = request.stringParam("appName", "")
                    if (packageName.isNotBlank()) {
                        controller.launchApp(listOf(packageName), appName.ifBlank { packageName })
                    } else {
                        controller.launchAppByName(appName)
                    }
                }
                ACTION_OPEN_SETTINGS -> {
                    val action = request.stringParam("settingsAction", "android.settings.SETTINGS")
                    val label = request.stringParam("label", "সেটিংস")
                    controller.openSettings(action, label)
                }
                ACTION_OPEN_CAMERA -> controller.openCamera()
                ACTION_OPEN_DIALER -> {
                    val number = request.stringParam("phoneNumber", "")
                    controller.openDialer(number)
                }
                ACTION_CREATE_SMS_DRAFT -> {
                    val text = request.stringParam("message", "")
                    controller.createSmsDraft(text)
                }
                ACTION_SHARE_WHATSAPP_DRAFT -> {
                    val text = request.stringParam("message", "")
                    controller.shareWhatsAppDraft(text)
                }
                ACTION_WEB_SEARCH -> controller.openWebSearch(request.stringParam("query"))
                ACTION_MAP_SEARCH -> controller.openMapSearch(request.stringParam("query"))
                ACTION_OPEN_WEBSITE -> controller.openWebsite(request.stringParam("url"))
                else -> {
                    return StructuredActionResult.failure(
                        actionId = request.actionId,
                        error = ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Action '${request.actionName}' not supported by AndroidDeviceCapabilityAdapter"),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
            }
            val duration = SystemClock.elapsedRealtime() - start
            val observation = NormalizedObservation.fromSystemState(message)
            StructuredActionResult.success(
                actionId = request.actionId,
                outputData = mapOf("message" to message),
                observation = observation,
                durationMs = duration
            )
        } catch (t: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(
                    ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE,
                    "AndroidDeviceController execution error: ${t.message}",
                    cause = t
                ),
                durationMs = SystemClock.elapsedRealtime() - start
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
