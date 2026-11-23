package com.eraassistant.mobile.capability.adapters

import android.os.SystemClock
import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.observation.ObservationSource
import com.eraassistant.mobile.screen.VisualFrame
import com.eraassistant.mobile.screen.VisualFrameStore

/**
 * Adapter for visual screenshot/camera frame inspection and RAM frame lifecycle.
 */
class VisualInspectionCapabilityAdapter(
    private val getLatestVisualFrame: () -> VisualFrame? = { VisualFrameStore.current() }
) : CapabilityAdapter {

    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId.VISUAL_INSPECTION,
        name = "Visual UI & Screen Inspection",
        description = "Provides access to explicitly captured screenshot frames stored in process RAM with privacy downscaling and explicit lifecycle management.",
        category = CapabilityCategory.VISUAL_UNDERSTANDING,
        supportedActions = setOf(
            ACTION_INSPECT_FRAME,
            ACTION_CLEAR_FRAME
        ),
        requiredPermissions = emptySet(),
        executionConstraints = setOf(
            "Captures only when user explicitly long-presses ERA overlay or provides camera frame",
            "Held in RAM only, no automatic disk storage",
            "Downscaled to 1440px longest side"
        ),
        worksOffline = true,
        actionSchemas = CapabilitySchemas.visualInspection()
    )

    override suspend fun checkAvailability(): CapabilityAvailability {
        val frame = getLatestVisualFrame()
        return if (frame != null && !frame.bitmap.isRecycled) {
            CapabilityAvailability.AVAILABLE
        } else {
            CapabilityAvailability.missing("No un-recycled visual frame is currently in process RAM.")
        }
    }

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? = null

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            when (request.actionName) {
                ACTION_INSPECT_FRAME -> {
                    val frame = getLatestVisualFrame()
                        ?: return StructuredActionResult.failure(
                            request.actionId,
                            ExecutionError(ExecutionErrorCategory.STALE_STATE, "No visual frame available in RAM"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    if (frame.bitmap.isRecycled) {
                        return StructuredActionResult.failure(
                            request.actionId,
                            ExecutionError(ExecutionErrorCategory.STALE_STATE, "Visual frame bitmap was recycled"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                    val w = frame.bitmap.width
                    val h = frame.bitmap.height
                    val pkg = frame.sourcePackage ?: "unknown"
                    val obs = NormalizedObservation(
                        observationId = "obs_vis_${frame.frameId}".take(160),
                        source = ObservationSource.VISUAL_SCREENSHOT,
                        capturedAtEpochMs = frame.capturedAtEpochMs,
                        targetPackageOrUrl = pkg,
                        summary = "Visual screenshot frame (${w}x${h})",
                        rawMetadata = mapOf(
                            "frameId" to frame.frameId,
                            "width" to w.toString(),
                            "height" to h.toString(),
                            "sourcePackage" to pkg
                        )
                    )
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf(
                            "frameId" to frame.frameId,
                            "width" to w,
                            "height" to h,
                            "sourcePackage" to pkg,
                            "capturedAt" to frame.capturedAtEpochMs
                        ),
                        observation = obs,
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_CLEAR_FRAME -> {
                    VisualFrameStore.clear()
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("cleared" to true),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                else -> StructuredActionResult.failure(
                    actionId = request.actionId,
                    error = ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Action '${request.actionName}' not supported by VisualInspection adapter"),
                    durationMs = SystemClock.elapsedRealtime() - start
                )
            }
        } catch (t: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "VisualInspection error: ${t.message}", cause = t),
                durationMs = SystemClock.elapsedRealtime() - start
            )
        }
    }

    companion object {
        const val ACTION_INSPECT_FRAME = "screen.visual.inspect_frame"
        const val ACTION_CLEAR_FRAME = "screen.visual.clear_frame"
    }
}
