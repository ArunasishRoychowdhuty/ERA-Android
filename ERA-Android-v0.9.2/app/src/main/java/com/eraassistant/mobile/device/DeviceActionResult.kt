package com.eraassistant.mobile.device

/** Typed outcome of a local Android device operation. A message never determines the status. */
enum class DeviceActionStatus {
    SUCCESS,
    FAILURE,
    UNSUPPORTED,
    UNAVAILABLE,
    CANCELLED
}

enum class DeviceActionErrorCode {
    NONE,
    UNSUPPORTED_ACTION,
    HARDWARE_UNAVAILABLE,
    APP_NOT_INSTALLED,
    APP_AMBIGUOUS,
    ACTIVITY_NOT_FOUND,
    PERMISSION_DENIED,
    SECURITY_RESTRICTED,
    INVALID_ARGUMENT,
    PLATFORM_ERROR,
    CANCELLED
}

data class DeviceActionResult(
    val status: DeviceActionStatus,
    val message: String,
    val errorCode: DeviceActionErrorCode = DeviceActionErrorCode.NONE,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(message.length <= 1_000)
        require(metadata.size <= 24)
        require(metadata.all { (key, value) -> key.isNotBlank() && key.length <= 80 && value.length <= 500 })
        require((status == DeviceActionStatus.SUCCESS) == (errorCode == DeviceActionErrorCode.NONE))
    }

    companion object {
        fun success(message: String, metadata: Map<String, String> = emptyMap()) =
            DeviceActionResult(DeviceActionStatus.SUCCESS, message, metadata = metadata)

        fun failure(message: String, code: DeviceActionErrorCode = DeviceActionErrorCode.PLATFORM_ERROR) =
            DeviceActionResult(DeviceActionStatus.FAILURE, message, code)

        fun unsupported(message: String, code: DeviceActionErrorCode = DeviceActionErrorCode.UNSUPPORTED_ACTION) =
            DeviceActionResult(DeviceActionStatus.UNSUPPORTED, message, code)

        fun unavailable(message: String, code: DeviceActionErrorCode = DeviceActionErrorCode.ACTIVITY_NOT_FOUND) =
            DeviceActionResult(DeviceActionStatus.UNAVAILABLE, message, code)

        fun cancelled(message: String = "Action cancelled.") =
            DeviceActionResult(DeviceActionStatus.CANCELLED, message, DeviceActionErrorCode.CANCELLED)
    }
}
