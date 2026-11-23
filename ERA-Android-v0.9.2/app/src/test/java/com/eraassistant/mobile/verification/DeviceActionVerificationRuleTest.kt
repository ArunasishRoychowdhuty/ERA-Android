package com.eraassistant.mobile.verification

import com.eraassistant.mobile.capability.StructuredActionResult
import com.eraassistant.mobile.observation.NormalizedObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceActionVerificationRuleTest {
    private val rule = DeviceActionVerificationRule()
    private val engine = VerificationEngine(listOf(rule))

    private fun verify(criterion: String, metadata: Map<String, String>) =
        engine.verify(
            actionId = "device-action-test",
            expectedCriteria = listOf(criterion),
            preObservation = null,
            postObservation = NormalizedObservation.fromSystemState("system queried", metadata),
            actionResult = StructuredActionResult.success("device-action-test")
        )

    @Test fun torchMatchesActualCallbackState() {
        val report = verify("era.device.torch.enabled=true", mapOf("torch.enabled" to "true"))
        assertEquals(VerificationStatus.VERIFIED, report.overallStatus)
    }

    @Test fun torchMismatchDoesNotBecomeVerified() {
        val report = verify("era.device.torch.enabled=true", mapOf("torch.enabled" to "false"))
        assertEquals(VerificationStatus.RETRY_REQUIRED, report.overallStatus)
    }

    @Test fun missingTorchStateIsUnavailableNotAFalseFailureOrSuccess() {
        val report = verify("era.device.torch.enabled=true", emptyMap())
        assertEquals(VerificationStatus.VERIFICATION_UNAVAILABLE, report.overallStatus)
    }

    @Test fun volumePercentUsesResultingAudioManagerIndex() {
        val report = verify(
            "era.device.volume.percent|stream=MEDIA|percent=50",
            mapOf("volume.after" to "7", "volume.max" to "15", "volume.stream" to "MEDIA")
        )
        assertEquals(VerificationStatus.VERIFIED, report.overallStatus)
    }

    @Test fun volumeAdjustmentChecksDirectionNotAdapterMessage() {
        val report = verify(
            "era.device.volume.adjust|stream=MEDIA|direction=UP",
            mapOf("volume.before" to "4", "volume.after" to "5", "volume.max" to "15")
        )
        assertEquals(VerificationStatus.VERIFIED, report.overallStatus)
    }

    @Test fun appLaunchRemainsUserVerifiableWithoutUsageAccess() {
        val report = verify(
            "era.device.app.launch|name=Chrome",
            mapOf("app.package" to "com.android.chrome")
        )
        assertEquals(VerificationStatus.VERIFICATION_UNAVAILABLE, report.overallStatus)
    }
}
