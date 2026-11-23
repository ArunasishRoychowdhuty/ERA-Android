package com.eraassistant.mobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTargetResolverTest {
    private val frame = VisualFrameIdentity(
        frameId = "frame-1",
        sourcePackage = "com.example.app",
        capturedAtEpochMs = 10_000L,
        frameWidth = 810,
        frameHeight = 1800,
        displayWidth = 1080,
        displayHeight = 2400,
        displayRotation = 0
    )

    private fun input(
        frame: VisualFrameIdentity = this.frame,
        packageName: String = "com.example.app",
        rotation: Int = 0,
        displayWidth: Int = 1080,
        displayHeight: Int = 2400,
        now: Long = 11_000L,
        approved: Boolean = true,
        confidence: Double = 0.97,
        region: NormalizedScreenRegion = NormalizedScreenRegion(0.25, 0.40, 0.75, 0.60)
    ): VisualTargetResolveInput {
        val proposal = VisualTargetProposal(
            frameId = frame.frameId,
            sourcePackage = frame.sourcePackage,
            capturedAtEpochMs = frame.capturedAtEpochMs,
            displayWidth = frame.displayWidth,
            displayHeight = frame.displayHeight,
            displayRotation = frame.displayRotation,
            label = "Continue",
            role = "button",
            region = region,
            confidence = confidence
        )
        return VisualTargetResolveInput(
            frame = frame,
            proposal = proposal,
            currentPackage = packageName,
            currentDisplayWidth = displayWidth,
            currentDisplayHeight = displayHeight,
            currentDisplayRotation = rotation,
            nowEpochMs = now,
            explicitlyApproved = approved
        )
    }

    @Test
    fun validTargetMapsNormalizedScreenshotRegionToDisplayCenter() {
        val result = VisualTargetResolver.resolve(input())
        assertTrue(result is VisualTargetResolution.Accepted)
        val target = (result as VisualTargetResolution.Accepted).target
        assertEquals(540, target.centerXOnDisplay)
        assertEquals(1200, target.centerYOnDisplay)
        assertEquals("frame-1", target.frameId)
    }

    @Test
    fun staleScreenshotIsRejected() {
        val result = VisualTargetResolver.resolve(input(now = 50_000L))
        assertEquals(
            VisualTargetRejection.STALE_FRAME,
            (result as VisualTargetResolution.Rejected).reason
        )
    }

    @Test
    fun appOrOrientationMismatchIsRejected() {
        val wrongApp = VisualTargetResolver.resolve(input(packageName = "com.other.app"))
        assertEquals(
            VisualTargetRejection.PACKAGE_MISMATCH,
            (wrongApp as VisualTargetResolution.Rejected).reason
        )

        val wrongRotation = VisualTargetResolver.resolve(input(rotation = 1))
        assertEquals(
            VisualTargetRejection.DISPLAY_GEOMETRY_CHANGED,
            (wrongRotation as VisualTargetResolution.Rejected).reason
        )
    }

    @Test
    fun lowConfidenceInvalidRegionAndMissingApprovalAreRejected() {
        val lowConfidence = VisualTargetResolver.resolve(input(confidence = 0.80))
        assertEquals(
            VisualTargetRejection.LOW_CONFIDENCE,
            (lowConfidence as VisualTargetResolution.Rejected).reason
        )

        val invalidBounds = VisualTargetResolver.resolve(
            input(region = NormalizedScreenRegion(0.8, 0.8, 0.2, 0.2))
        )
        assertEquals(
            VisualTargetRejection.INVALID_REGION,
            (invalidBounds as VisualTargetResolution.Rejected).reason
        )

        val noApproval = VisualTargetResolver.resolve(input(approved = false))
        assertEquals(
            VisualTargetRejection.USER_APPROVAL_REQUIRED,
            (noApproval as VisualTargetResolution.Rejected).reason
        )
    }

    @Test
    fun changedUiFingerprintRejectsTheVisualTargetEvenWhenFrameAgeIsFresh() {
        val boundFrame = frame.copy(screenFingerprint = "screen-before")
        val base = input(frame = boundFrame)
        val result = VisualTargetResolver.resolve(base.copy(currentUiFingerprint = "screen-after"))
        assertEquals(
            VisualTargetRejection.SCREEN_CHANGED,
            (result as VisualTargetResolution.Rejected).reason
        )
    }

    @Test
    fun proposalBoundToDifferentFrameIsRejected() {
        val base = input()
        val wrongProposal = base.proposal.copy(frameId = "different-frame")
        val result = VisualTargetResolver.resolve(base.copy(proposal = wrongProposal))
        assertEquals(
            VisualTargetRejection.FRAME_ID_MISMATCH,
            (result as VisualTargetResolution.Rejected).reason
        )
    }
}
