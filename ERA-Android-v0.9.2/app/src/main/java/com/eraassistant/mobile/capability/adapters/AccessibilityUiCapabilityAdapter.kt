package com.eraassistant.mobile.capability.adapters

import android.content.Context
import android.os.SystemClock
import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedElement
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.observation.ObservationSource
import com.eraassistant.mobile.screen.PendingUiActionStore
import com.eraassistant.mobile.screen.UiElement
import com.eraassistant.mobile.screen.UiScreenSnapshot
import com.eraassistant.mobile.screen.UiSnapshotFingerprint
import com.eraassistant.mobile.screen.UiTreeJson
import org.json.JSONObject

/**
 * Adapter integrating Android Accessibility UI automation and inspection.
 * Supports screen observation capture, selector validation, and one-shot snapshot-bound
 * action approval with user overlay dispatch.
 */
class AccessibilityUiCapabilityAdapter(
    private val context: Context,
    private val isServiceEnabled: () -> Boolean,
    private val getLatestSnapshot: () -> UiScreenSnapshot?,
    private val pendingActionStore: PendingUiActionStore = PendingUiActionStore(context)
) : CapabilityAdapter {

    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId.ACCESSIBILITY_UI,
        name = "Android Accessibility UI Automation",
        description = "Inspects Android screen hierarchy and approves snapshot-bound semantic UI actions (click, text entry, scroll) requiring user overlay tap.",
        category = CapabilityCategory.ACCESSIBILITY_AUTOMATION,
        supportedActions = setOf(
            ACTION_CAPTURE_SCREEN_HIERARCHY,
            ACTION_APPROVE_UI_CLICK,
            ACTION_APPROVE_UI_TYPE,
            ACTION_APPROVE_UI_SCROLL,
            ACTION_CANCEL_PENDING_ACTION
        ),
        requiredPermissions = setOf("android.permission.BIND_ACCESSIBILITY_SERVICE"),
        executionConstraints = setOf(
            "Requires user-accepted screen inspection consent",
            "Requires enabled EraAccessibilityService",
            "One-shot action approved against exact snapshot fingerprint",
            "Dispatched only when user taps floating overlay in target app"
        ),
        worksOffline = true,
        actionSchemas = CapabilitySchemas.accessibilityUi()
    )

    override suspend fun checkAvailability(): CapabilityAvailability {
        val prefs = context.getSharedPreferences("era_preferences", Context.MODE_PRIVATE)
        val consented = prefs.getBoolean("accessibility_consent_accepted", false)
        if (!consented) {
            return CapabilityAvailability.missing("Screen inspection disclosure must be accepted in ERA.")
        }
        if (!isServiceEnabled()) {
            return CapabilityAvailability.missing(
                "ERA Accessibility Service is not enabled in Android Settings.",
                setOf("android.permission.BIND_ACCESSIBILITY_SERVICE")
            )
        }
        return CapabilityAvailability.AVAILABLE
    }

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
        when (request.actionName) {
            ACTION_CAPTURE_SCREEN_HIERARCHY -> {
                val snapshot = getLatestSnapshot()
                    ?: return ExecutionError(ExecutionErrorCategory.STALE_STATE, "No UI snapshot currently captured.")
                if (!isSnapshotFresh(snapshot)) {
                    return ExecutionError(ExecutionErrorCategory.STALE_STATE, "Accessibility snapshot is stale. Capture the current screen again.")
                }
            }
            ACTION_APPROVE_UI_CLICK, ACTION_APPROVE_UI_TYPE, ACTION_APPROVE_UI_SCROLL -> {
                val selector = request.param<String>("selector")
                if (selector.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'selector' is required.")
                }
                val snapshot = getLatestSnapshot()
                    ?: return ExecutionError(ExecutionErrorCategory.STALE_STATE, "No UI snapshot available to bind this action.")
                if (!isSnapshotFresh(snapshot)) {
                    return ExecutionError(ExecutionErrorCategory.STALE_STATE, "Accessibility snapshot is stale. Capture the current screen again.")
                }
                val expectedPackage = request.param<String>("expectedPackage") ?: snapshot.packageName
                if (expectedPackage != snapshot.packageName) {
                    return ExecutionError(ExecutionErrorCategory.PRECONDITION_FAILED, "Expected package '$expectedPackage' doesn't match snapshot package '${snapshot.packageName}'.")
                }
            }
        }
        return null
    }

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            when (request.actionName) {
                ACTION_CAPTURE_SCREEN_HIERARCHY -> {
                    val snapshot = getLatestSnapshot()
                        ?: return StructuredActionResult.failure(
                            request.actionId,
                            ExecutionError(ExecutionErrorCategory.STALE_STATE, "No UI snapshot currently captured"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    val obs = normalizeSnapshot(snapshot)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf(
                            "packageName" to (snapshot.packageName ?: "unknown"),
                            "nodeCount" to snapshot.nodeCount,
                            "hash" to computeFingerprint(snapshot)
                        ),
                        observation = obs,
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_APPROVE_UI_CLICK -> {
                    val selector = request.stringParam("selector")
                    val snapshot = getLatestSnapshot()!!
                    val fingerprint = computeFingerprint(snapshot)
                    val approved = pendingActionStore.approve(
                        selectorText = selector,
                        action = PendingUiActionStore.ACTION_CLICK,
                        payload = "",
                        expectedPackage = snapshot.packageName,
                        resourceId = "",
                        reviewedObservationId = fingerprint
                    )
                    if (approved) {
                        StructuredActionResult.waitingUser(
                            actionId = request.actionId,
                            message = "Click approved for '$selector' in ${snapshot.packageName}. Switch to the app and tap the ERA overlay to dispatch.",
                            outputData = mapOf("selector" to selector, "action" to "CLICK"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    } else {
                        StructuredActionResult.failure(
                            actionId = request.actionId,
                            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "PendingUiActionStore rejected click approval"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                }
                ACTION_APPROVE_UI_TYPE -> {
                    val selector = request.stringParam("selector")
                    val text = request.stringParam("text")
                    val snapshot = getLatestSnapshot()!!
                    val fingerprint = computeFingerprint(snapshot)
                    val approved = pendingActionStore.approve(
                        selectorText = selector,
                        action = PendingUiActionStore.ACTION_TYPE_TEXT,
                        payload = text,
                        expectedPackage = snapshot.packageName,
                        resourceId = "",
                        reviewedObservationId = fingerprint
                    )
                    if (approved) {
                        StructuredActionResult.waitingUser(
                            actionId = request.actionId,
                            message = "Text input approved for '$selector' in ${snapshot.packageName}. Switch to the app and tap the ERA overlay to dispatch.",
                            outputData = mapOf("selector" to selector, "action" to "TYPE_TEXT"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    } else {
                        StructuredActionResult.failure(
                            actionId = request.actionId,
                            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "PendingUiActionStore rejected text approval"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                }
                ACTION_APPROVE_UI_SCROLL -> {
                    val selector = request.stringParam("selector")
                    val forward = request.boolParam("forward", true)
                    val snapshot = getLatestSnapshot()!!
                    val fingerprint = computeFingerprint(snapshot)
                    val action = if (forward) PendingUiActionStore.ACTION_SCROLL_FORWARD else PendingUiActionStore.ACTION_SCROLL_BACKWARD
                    val approved = pendingActionStore.approve(
                        selectorText = selector,
                        action = action,
                        payload = "",
                        expectedPackage = snapshot.packageName,
                        resourceId = "",
                        reviewedObservationId = fingerprint
                    )
                    if (approved) {
                        StructuredActionResult.waitingUser(
                            actionId = request.actionId,
                            message = "Scroll approved for '$selector' in ${snapshot.packageName}. Switch to the app and tap the ERA overlay to dispatch.",
                            outputData = mapOf("selector" to selector, "action" to action),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    } else {
                        StructuredActionResult.failure(
                            actionId = request.actionId,
                            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "PendingUiActionStore rejected scroll approval"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                }
                ACTION_CANCEL_PENDING_ACTION -> {
                    val reason = request.stringParam("reason", "Cancelled by agent.")
                    pendingActionStore.clearPending(reason)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("cancelled" to true, "reason" to reason),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                else -> StructuredActionResult.failure(
                    actionId = request.actionId,
                    error = ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Action '${request.actionName}' not supported by Accessibility UI adapter"),
                    durationMs = SystemClock.elapsedRealtime() - start
                )
            }
        } catch (t: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "Accessibility UI error: ${t.message}", cause = t),
                durationMs = SystemClock.elapsedRealtime() - start
            )
        }
    }

    private fun isSnapshotFresh(snapshot: UiScreenSnapshot, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val age = nowEpochMs - snapshot.capturedAtEpochMs
        return snapshot.capturedAtEpochMs > 0L && age in 0..MAX_SNAPSHOT_AGE_MS
    }

    private fun flattenElements(root: UiElement): List<UiElement> = buildList {
        fun visit(el: UiElement) {
            add(el)
            el.children.forEach(::visit)
        }
        visit(root)
    }

    private fun computeFingerprint(snapshot: UiScreenSnapshot): String = runCatching {
        val encoded = JSONObject(UiTreeJson.encode(snapshot))
        UiSnapshotFingerprint.of(
            packageName = snapshot.packageName,
            canonicalRootJson = encoded.getJSONObject("root").toString(),
            nodeCount = snapshot.nodeCount,
            truncated = snapshot.truncated
        )
    }.getOrDefault("0".repeat(64))

    private fun normalizeSnapshot(snapshot: UiScreenSnapshot): NormalizedObservation {
        val allNodes = flattenElements(snapshot.root)
        val elements = allNodes.take(NormalizedObservation.MAX_ELEMENTS).map { node ->
            NormalizedElement(
                elementId = node.nodeKey.take(NormalizedElement.MAX_ID_LENGTH),
                role = (node.className?.substringAfterLast('.') ?: node.role).take(NormalizedElement.MAX_ROLE_LENGTH),
                label = (node.text?.ifBlank { null } ?: node.contentDescription?.ifBlank { null } ?: node.hintText?.ifBlank { null })?.take(NormalizedElement.MAX_TEXT_LENGTH),
                text = if (node.password) null else node.text?.ifBlank { null }?.take(NormalizedElement.MAX_TEXT_LENGTH),
                resourceId = node.resourceId?.ifBlank { null }?.take(NormalizedElement.MAX_RESOURCE_ID_LENGTH),
                isClickable = node.clickable,
                isEditable = node.editable,
                isScrollable = node.scrollable,
                isFocused = node.focused,
                isEnabled = node.enabled,
                isVisible = node.visible,
                isPassword = node.password,
                depth = 0
            )
        }
        val fingerprint = computeFingerprint(snapshot)
        val target = snapshot.packageName?.take(NormalizedObservation.MAX_TARGET_LENGTH)
        val title = (snapshot.windowTitle ?: snapshot.packageName)?.take(NormalizedObservation.MAX_TITLE_LENGTH)
        val summary = "Android Accessibility hierarchy (${elements.size} elements)"
        val boundedElements = NormalizedObservation.boundElements(elements, summary, target, title)
        return NormalizedObservation(
            observationId = "obs_a11y_${fingerprint.take(16)}_${snapshot.capturedAtEpochMs}".take(160),
            source = ObservationSource.ANDROID_ACCESSIBILITY,
            capturedAtEpochMs = snapshot.capturedAtEpochMs,
            targetPackageOrUrl = target,
            titleOrHeader = title,
            summary = summary,
            elements = boundedElements,
            totalElementsCount = snapshot.nodeCount,
            isTruncated = snapshot.truncated || boundedElements.size < elements.size
        )
    }

    companion object {
        private const val MAX_SNAPSHOT_AGE_MS = 15_000L
        const val ACTION_CAPTURE_SCREEN_HIERARCHY = "accessibility.screen.capture"
        const val ACTION_APPROVE_UI_CLICK = "accessibility.ui.approve_click"
        const val ACTION_APPROVE_UI_TYPE = "accessibility.ui.approve_type"
        const val ACTION_APPROVE_UI_SCROLL = "accessibility.ui.approve_scroll"
        const val ACTION_CANCEL_PENDING_ACTION = "accessibility.ui.cancel_pending"
    }
}
