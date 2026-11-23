package com.eraassistant.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionResultTest {
    @Test fun missingTorchIsUnsupportedNotSuccess() {
        val result = DeviceActionResult.unsupported(
            "Flash hardware unavailable.",
            DeviceActionErrorCode.HARDWARE_UNAVAILABLE
        )
        assertEquals(DeviceActionStatus.UNSUPPORTED, result.status)
        assertEquals(DeviceActionErrorCode.HARDWARE_UNAVAILABLE, result.errorCode)
        assertFalse(result.status == DeviceActionStatus.SUCCESS)
    }

    @Test fun missingTargetAppIsUnavailableNotSuccess() {
        val result = DeviceActionResult.unavailable(
            "Target app not installed.",
            DeviceActionErrorCode.APP_NOT_INSTALLED
        )
        assertEquals(DeviceActionStatus.UNAVAILABLE, result.status)
        assertEquals(DeviceActionErrorCode.APP_NOT_INSTALLED, result.errorCode)
    }

    @Test fun permissionAndPlatformFailuresKeepStructuredCodes() {
        val permission = DeviceActionResult.failure("Denied", DeviceActionErrorCode.PERMISSION_DENIED)
        val platform = DeviceActionResult.failure("Platform failure", DeviceActionErrorCode.PLATFORM_ERROR)
        assertEquals(DeviceActionErrorCode.PERMISSION_DENIED, permission.errorCode)
        assertEquals(DeviceActionErrorCode.PLATFORM_ERROR, platform.errorCode)
        assertTrue(permission.status == DeviceActionStatus.FAILURE)
    }

    @Test fun unsupportedActionHasExplicitUnsupportedStatus() {
        val result = DeviceActionResult.unsupported("Unknown action")
        assertEquals(DeviceActionStatus.UNSUPPORTED, result.status)
        assertEquals(DeviceActionErrorCode.UNSUPPORTED_ACTION, result.errorCode)
    }
}
