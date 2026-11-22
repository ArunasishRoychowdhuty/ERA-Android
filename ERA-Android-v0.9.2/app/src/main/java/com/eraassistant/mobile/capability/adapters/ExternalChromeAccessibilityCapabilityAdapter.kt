package com.eraassistant.mobile.capability.adapters

import android.content.Context
import android.os.SystemClock
import com.eraassistant.mobile.accessibility.ExternalChromeAgentPolicy
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
 * Adapter integrating External Chrome Target (foreground Android Accessibility-based).
 * Maintains strict honesty about its capabilities: foreground Accessibility inspection only,
 * never claims DOM, CDP, cookies, or hidden page state.
 */
class ExternalChromeAccessibilityCapabilityAdapter(
    private val context: Context,
    private val isChromeTargetActive: () -> Boolean,
    private val getLatestSnapshot: () -> UiScreenSnapshot?,
    private val pendingActionStore: PendingUiActionStore = PendingUiActionStore(context)
) : CapabilityAdapter {

    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId.EXTERNAL_CHROME,
        name = "External Chrome Accessibility Target",
        description = "Inspects and interacts with foreground Google Chrome via Android AccessibilityService. Does not access DOM, CDP, cookies, or private data.",
        category = CapabilityCategory.ACCESSIBILITY_AUTOMATION,
        supportedActions = setOf(
            ACTION_CHROME_INSPECT_VISIBLE_TREE,
            ACTION_CHROME_APPROVE_CLICK,
            ACTION_CHROME_APPROVE_TYPE,
            ACTION_CHROME_APPROVE_SCROLL
        ),
        requiredPermissions = setOf("android.permission.BIND_ACCESSIBILITY_SERVICE"),
        executionConstraints = setOf(
            "Chrome app must be in the foreground",
            "Screen inspection consent required",
            "User must tap ERA floating overlay to dispatch action",
            "No direct DOM/CDP access; accessibility nodes only"
        ),
        worksOffline = true,
        actionSchemas = CapabilitySchemas.externalChrome()
    )

    override suspend fun checkAvailability(): CapabilityAvailability {
        if (!isChromeTargetActive()) {
            return CapabilityAvailability.missing("External Chrome Target mode is disabled in ERA settings.")
        }
        val prefs = context.getSharedPreferences("era_preferences", Context.MODE_PRIVATE)
        val consented = prefs.getBoolean("accessibility_consent_accepted", false)
        if (!consented) {
            return CapabilityAvailability.missing("Screen inspection consent has not been accepted.")
        }
        return CapabilityAvailability.AVAILABLE
    }

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
        val snapshot = getLatestSnapshot()
            ?: return ExecutionError(ExecutionErrorCategory.STALE_STATE, "No Chrome accessibility snapshot available. Tap 'Capture UI' in Chrome first.")
        if (!isSnapshotFresh(snapshot)) {
            return ExecutionError(ExecutionErrorCategory.STALE_STATE, "Chrome Accessibility snapshot is stale. Capture the current foreground screen again.")
        }

        if (!ExternalChromeAgentPolicy.isSupportedPackage(snapshot.packageName)) {
            return ExecutionError(ExecutionErrorCategory.PRECONDITION_FAILED, "Foreground app '${snapshot.packageName}' is not a supported Chrome package.")
        }

        when (request.actionName) {
            ACTION_CHROME_APPROVE_CLICK, ACTION_CHROME_APPROVE_TYPE, ACTION_CHROME_APPROVE_SCROLL -> {
                val selector = request.param<String>("selector")
                if (selector.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'selector' (visible text or resourceId) is required.")
                }
            }
        }
        return null
    }

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            when (request.actionName) {
                ACTION_CHROME_INSPECT_VISIBLE_TREE -> {
                    val snapshot = getLatestSnapshot()
                        ?: return StructuredActionResult.failure(
                            request.actionId,
                            ExecutionError(ExecutionErrorCategory.STALE_STATE, "No Chrome snapshot available"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    val obs = normalizeChromeSnapshot(snapshot)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf(
                            "packageName" to (snapshot.packageName ?: "unknown"),
                            "nodeCount" to snapshot.nodeCount,
                            "truncated" to snapshot.truncated
                        ),
                        observation = obs,
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_CHROME_APPROVE_CLICK -> {
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
                            message = "Click approved for '$selector' in Chrome. Switch to Chrome and tap the ERA overlay to dispatch.",
                            outputData = mapOf("selector" to selector, "action" to "CLICK"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    } else {
                        StructuredActionResult.failure(
                            actionId = request.actionId,
                            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "Failed to register approval in PendingUiActionStore"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                }
                ACTION_CHROME_APPROVE_TYPE -> {
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
                            message = "Text input approved for '$selector' in Chrome. Switch to Chrome and tap the ERA overlay to dispatch.",
                            outputData = mapOf("selector" to selector, "action" to "TYPE_TEXT"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    } else {
                        StructuredActionResult.failure(
                            actionId = request.actionId,
                            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "Failed to register approval in PendingUiActionStore"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                }
                ACTION_CHROME_APPROVE_SCROLL -> {
                    val selector = request.stringParam("selector")
                    val forward = request.boolParam("forward", true)
                    val snapshot = getLatestSnapshot()!!
                    val fingerprint = computeFingerprint(snapshot)
                    val scrollAction = if (forward) PendingUiActionStore.ACTION_SCROLL_FORWARD else PendingUiActionStore.ACTION_SCROLL_BACKWARD
                    val approved = pendingActionStore.approve(
                        selectorText = selector,
                        action = scrollAction,
                        payload = "",
                        expectedPackage = snapshot.packageName,
                        resourceId = "",
                        reviewedObservationId = fingerprint
                    )
                    if (approved) {
                        StructuredActionResult.waitingUser(
                            actionId = request.actionId,
                            message = "Scroll approved for '$selector' in Chrome. Switch to Chrome and tap the ERA overlay to dispatch.",
                            outputData = mapOf("selector" to selector, "action" to scrollAction),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    } else {
                        StructuredActionResult.failure(
                            actionId = request.actionId,
                            error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "Failed to register approval in PendingUiActionStore"),
                            durationMs = SystemClock.elapsedRealtime() - start
                        )
                    }
                }
                else -> StructuredActionResult.failure(
                    actionId = request.actionId,
                    error = ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Action '${request.actionName}' not supported by Chrome adapter"),
                    durationMs = SystemClock.elapsedRealtime() - start
                )
            }
        } catch (t: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "Chrome target error: ${t.message}", cause = t),
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

    private fun normalizeChromeSnapshot(snapshot: UiScreenSnapshot): NormalizedObservation {
        val allNodes = flattenElements(snapshot.root)
        val elements = allNodes.take(NormalizedObservation.MAX_ELEMENTS).map { node ->
            NormalizedElement(
                elementId = node.nodeKey.take(NormalizedElement.MAX_ID_LENGTH),
                role = (node.className?.substringAfterLast('.') ?: node.role).take(NormalizedElement.MAX_ROLE_LENGTH),
                label = ExternalChromeAgentPolicy.sanitizeVisibleText(
                    snapshot.packageName,
                    node.text?.ifBlank { null } ?: node.contentDescription?.ifBlank { null } ?: node.hintText?.ifBlank { null }
                )?.take(NormalizedElement.MAX_TEXT_LENGTH),
                text = if (ExternalChromeAgentPolicy.shouldOmitNodeText(snapshot.packageName, node.editable, node.password, node.resourceId, node.text)) null else ExternalChromeAgentPolicy.sanitizeVisibleText(snapshot.packageName, node.text)?.take(NormalizedElement.MAX_TEXT_LENGTH),
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
        val title = (snapshot.windowTitle ?: "Chrome Accessibility View").take(NormalizedObservation.MAX_TITLE_LENGTH)
        val summary = "Chrome Accessibility snapshot (${elements.size} nodes)"
        val boundedElements = NormalizedObservation.boundElements(elements, summary, target, title)
        return NormalizedObservation(
            observationId = "obs_chrome_${fingerprint.take(16)}_${snapshot.capturedAtEpochMs}".take(160),
            source = ObservationSource.CHROME_ACCESSIBILITY,
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
        const val ACTION_CHROME_INSPECT_VISIBLE_TREE = "browser.chrome.inspect_visible_tree"
        const val ACTION_CHROME_APPROVE_CLICK = "browser.chrome.approve_click"
        const val ACTION_CHROME_APPROVE_TYPE = "browser.chrome.approve_type"
        const val ACTION_CHROME_APPROVE_SCROLL = "browser.chrome.approve_scroll"
    }
}
