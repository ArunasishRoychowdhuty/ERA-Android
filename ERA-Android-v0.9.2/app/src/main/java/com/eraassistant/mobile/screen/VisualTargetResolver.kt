package com.eraassistant.mobile.screen

import kotlin.math.abs
import kotlin.math.roundToInt

/** Normalized coordinates are relative to the exact screenshot frame, not guessed display pixels. */
data class NormalizedScreenRegion(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    fun isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0.0 && top >= 0.0 && right <= 1.0 && bottom <= 1.0 &&
            right > left && bottom > top &&
            (right - left) >= MIN_REGION_SIZE && (bottom - top) >= MIN_REGION_SIZE

    fun centerX(): Double = (left + right) / 2.0
    fun centerY(): Double = (top + bottom) / 2.0

    companion object {
        private const val MIN_REGION_SIZE = 0.004
    }
}

data class VisualFrameIdentity(
    val frameId: String,
    val sourcePackage: String,
    val capturedAtEpochMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotation: Int,
    val screenFingerprint: String? = null
)

data class VisualTargetProposal(
    val frameId: String,
    val sourcePackage: String,
    val capturedAtEpochMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotation: Int,
    val label: String,
    val role: String,
    val region: NormalizedScreenRegion,
    val confidence: Double,
    val actionHint: String = ""
)

data class VisualTargetResolveInput(
    val frame: VisualFrameIdentity,
    val proposal: VisualTargetProposal,
    val currentPackage: String,
    val currentDisplayWidth: Int,
    val currentDisplayHeight: Int,
    val currentDisplayRotation: Int,
    val nowEpochMs: Long,
    val explicitlyApproved: Boolean,
    val currentUiFingerprint: String? = null
)

enum class VisualTargetRejection {
    FRAME_ID_MISMATCH,
    PACKAGE_MISMATCH,
    STALE_FRAME,
    DISPLAY_GEOMETRY_CHANGED,
    INVALID_REGION,
    LOW_CONFIDENCE,
    USER_APPROVAL_REQUIRED,
    SCREEN_CHANGED
}

data class GroundedVisualTarget(
    val label: String,
    val role: String,
    val centerXOnDisplay: Int,
    val centerYOnDisplay: Int,
    val frameId: String,
    val confidence: Double
)

sealed class VisualTargetResolution {
    data class Accepted(val target: GroundedVisualTarget) : VisualTargetResolution()
    data class Rejected(val reason: VisualTargetRejection) : VisualTargetResolution()
}

/**
 * Validates a vision result against the exact RAM screenshot and current display geometry.
 * The result is only a grounded target proposal; the caller still needs one-shot approval and
 * an independent post-action observation before considering a gesture successful.
 */
object VisualTargetResolver {
    const val MAX_FRAME_AGE_MS = 30_000L
    const val MIN_CONFIDENCE = 0.90
    private const val MAX_ASPECT_RATIO_DELTA = 0.02

    fun resolve(input: VisualTargetResolveInput): VisualTargetResolution {
        val frame = input.frame
        val proposal = input.proposal

        if (proposal.frameId != frame.frameId) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.FRAME_ID_MISMATCH)
        }
        if (frame.sourcePackage != input.currentPackage ||
            proposal.sourcePackage != frame.sourcePackage ||
            proposal.sourcePackage != input.currentPackage
        ) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.PACKAGE_MISMATCH)
        }
        if (proposal.capturedAtEpochMs != frame.capturedAtEpochMs ||
            input.nowEpochMs < frame.capturedAtEpochMs ||
            input.nowEpochMs - frame.capturedAtEpochMs > MAX_FRAME_AGE_MS
        ) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.STALE_FRAME)
        }
        if (frame.screenFingerprint != null && frame.screenFingerprint != input.currentUiFingerprint) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.SCREEN_CHANGED)
        }
        if (frame.frameWidth <= 0 || frame.frameHeight <= 0 ||
            frame.displayWidth <= 0 || frame.displayHeight <= 0 ||
            abs(
                frame.frameWidth.toDouble() / frame.frameHeight -
                    frame.displayWidth.toDouble() / frame.displayHeight
            ) > MAX_ASPECT_RATIO_DELTA ||
            frame.displayWidth != input.currentDisplayWidth ||
            frame.displayHeight != input.currentDisplayHeight ||
            frame.displayRotation != input.currentDisplayRotation ||
            proposal.displayWidth != frame.displayWidth ||
            proposal.displayHeight != frame.displayHeight ||
            proposal.displayRotation != frame.displayRotation
        ) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.DISPLAY_GEOMETRY_CHANGED)
        }
        if (!proposal.region.isValid()) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.INVALID_REGION)
        }
        if (!proposal.confidence.isFinite() || proposal.confidence < MIN_CONFIDENCE) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.LOW_CONFIDENCE)
        }
        if (!input.explicitlyApproved) {
            return VisualTargetResolution.Rejected(VisualTargetRejection.USER_APPROVAL_REQUIRED)
        }

        val x = (proposal.region.centerX() * frame.displayWidth).roundToInt()
            .coerceIn(0, frame.displayWidth - 1)
        val y = (proposal.region.centerY() * frame.displayHeight).roundToInt()
            .coerceIn(0, frame.displayHeight - 1)
        return VisualTargetResolution.Accepted(
            GroundedVisualTarget(
                label = proposal.label.take(160),
                role = proposal.role.take(64),
                centerXOnDisplay = x,
                centerYOnDisplay = y,
                frameId = frame.frameId,
                confidence = proposal.confidence
            )
        )
    }
}
