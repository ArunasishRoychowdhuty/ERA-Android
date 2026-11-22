package com.eraassistant.mobile.verification

import com.eraassistant.mobile.capability.ActionResultStatus
import com.eraassistant.mobile.capability.ExecutionError
import com.eraassistant.mobile.capability.ExecutionErrorCategory
import com.eraassistant.mobile.capability.StructuredActionResult
import com.eraassistant.mobile.observation.NormalizedElement
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.observation.ObservationSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VerificationEngineTest {

    private lateinit var verifier: VerificationEngine

    @Before
    fun setup() {
        verifier = VerificationEngine()
    }

    @Test
    fun testSuccessfulVerificationWithMatchingElement() {
        val postObs = NormalizedObservation(
            observationId = "obs_post_1",
            source = ObservationSource.ANDROID_ACCESSIBILITY,
            capturedAtEpochMs = System.currentTimeMillis(),
            targetPackageOrUrl = "com.whatsapp",
            titleOrHeader = "WhatsApp Chat",
            elements = listOf(
                NormalizedElement(
                    elementId = "e1",
                    role = "Button",
                    label = "Send",
                    isClickable = true
                )
            )
        )

        val actionResult = StructuredActionResult.success(
            actionId = "a1",
            outputData = mapOf("message" to "Chat opened")
        )

        val report = verifier.verify(
            actionId = "a1",
            expectedCriteria = listOf("WhatsApp", "Send"),
            preObservation = null,
            postObservation = postObs,
            actionResult = actionResult
        )

        assertEquals(VerificationStatus.VERIFIED, report.overallStatus)
        assertTrue(report.overallStatus.isSuccessful)
        assertEquals(2, report.evidences.size)
        assertTrue(report.evidences.all { it.status == VerificationStatus.VERIFIED })
    }

    @Test
    fun testRetryRequiredWhenElementMissing() {
        val postObs = NormalizedObservation(
            observationId = "obs_post_2",
            source = ObservationSource.ANDROID_ACCESSIBILITY,
            capturedAtEpochMs = System.currentTimeMillis(),
            targetPackageOrUrl = "com.whatsapp",
            titleOrHeader = "WhatsApp Home",
            elements = emptyList()
        )

        val actionResult = StructuredActionResult.success("a2")

        val report = verifier.verify(
            actionId = "a2",
            expectedCriteria = listOf("Recipient Profile"),
            preObservation = null,
            postObservation = postObs,
            actionResult = actionResult,
            retryCount = 0,
            maxRetries = 2
        )

        assertEquals(VerificationStatus.RETRY_REQUIRED, report.overallStatus)
        assertTrue(report.canRetry)
    }

    @Test
    fun testReplanRequiredWhenRetriesExhausted() {
        val postObs = NormalizedObservation(
            observationId = "obs_post_3",
            source = ObservationSource.ANDROID_ACCESSIBILITY,
            capturedAtEpochMs = System.currentTimeMillis(),
            elements = emptyList()
        )

        val actionResult = StructuredActionResult.success("a3")

        val report = verifier.verify(
            actionId = "a3",
            expectedCriteria = listOf("Confirm Dialog"),
            preObservation = null,
            postObservation = postObs,
            actionResult = actionResult,
            retryCount = 2,
            maxRetries = 2
        )

        assertEquals(VerificationStatus.REPLAN_REQUIRED, report.overallStatus)
        assertFalse(report.canRetry)
        assertNotNull(report.suggestedReplanReason)
    }

    @Test
    fun testVerificationUnavailableWhenWaitingUser() {
        val actionResult = StructuredActionResult.waitingUser(
            actionId = "a4",
            message = "Please tap the overlay in Chrome"
        )

        val report = verifier.verify(
            actionId = "a4",
            expectedCriteria = listOf("New Page"),
            preObservation = null,
            postObservation = null,
            actionResult = actionResult
        )

        assertEquals(VerificationStatus.VERIFICATION_UNAVAILABLE, report.overallStatus)
    }

    @Test
    fun testAdapterFailurePropagatesAccurately() {
        val actionResult = StructuredActionResult.failure(
            actionId = "a5",
            error = ExecutionError(
                category = ExecutionErrorCategory.TIMEOUT,
                message = "Operation timed out waiting for DOM element",
                retryable = true
            )
        )

        val report = verifier.verify(
            actionId = "a5",
            expectedCriteria = listOf("Success Notification"),
            preObservation = null,
            postObservation = null,
            actionResult = actionResult,
            retryCount = 0,
            maxRetries = 2
        )

        assertEquals(VerificationStatus.RETRY_REQUIRED, report.overallStatus)
        assertTrue(report.canRetry)
    }
}
