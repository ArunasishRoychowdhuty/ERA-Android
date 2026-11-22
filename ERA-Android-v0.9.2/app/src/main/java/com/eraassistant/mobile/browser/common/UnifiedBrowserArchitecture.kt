package com.eraassistant.mobile.browser.common

import com.eraassistant.mobile.browser.PhoneBrowserAgentClient
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
 * High-level classification of supported browser execution targets.
 */
enum class BrowserTargetKind {
    /**
     * ERA-owned Android WebView sandbox.
     * Full multi-tab, DOM semantic trees, Shadow DOM, same-origin frames, synthetic events.
     */
    NATIVE_WEBVIEW,

    /**
     * Standalone Google Chrome Android application.
     * Android AccessibilityService automation only.
     * DOES NOT HAVE DOM, CDP, COOKIE, OR PRIVATE PROFILE ACCESS.
     */
    EXTERNAL_CHROME
}

/**
 * Prototype browser target abstraction. Current production routing registers Native WebView
 * and External Chrome adapters directly in CapabilityRegistry; this abstraction has no
 * production consumer yet and must not be represented as the active browser dispatch path.
 */
interface UnifiedBrowserTarget {
    val kind: BrowserTargetKind
    val displayName: String
    val technicalBoundaries: String

    suspend fun isAvailable(): Boolean
    suspend fun inspectCurrentState(includePrivate: Boolean = false, confirmed: Boolean = false): NormalizedObservation
    suspend fun navigate(url: String): Result<String>
    suspend fun click(identifier: String, confirmed: Boolean = false): Result<String>
    suspend fun typeText(identifier: String, text: String, confirmed: Boolean = false): Result<String>
    suspend fun scroll(identifier: String?, forward: Boolean = true): Result<String>
}

/**
 * Unified target implementation for ERA's in-app multi-tab WebView.
 */
class NativeWebViewTarget(
    private val clientProvider: () -> PhoneBrowserAgentClient?
) : UnifiedBrowserTarget {
    override val kind: BrowserTargetKind = BrowserTargetKind.NATIVE_WEBVIEW
    override val displayName: String = "ERA Multi-Tab Native WebView"
    override val technicalBoundaries: String =
        "Direct DOM engine inspection, Shadow DOM, same-origin iframe traversal, synthetic DOM event dispatch. Sandboxed within ERA."

    override suspend fun isAvailable(): Boolean = clientProvider() != null

    override suspend fun inspectCurrentState(includePrivate: Boolean, confirmed: Boolean): NormalizedObservation {
        val client = clientProvider() ?: throw IllegalStateException("Native WebView is not initialized.")
        val targetId = client.activeTargetId()
        val snapshot = client.captureSnapshot(targetId = targetId, includePrivateDetails = includePrivate, confirmed = confirmed)
        val elements = snapshot.nodes.take(NormalizedObservation.MAX_ELEMENTS).map { node ->
            NormalizedElement(
                elementId = node.ref.take(NormalizedElement.MAX_ID_LENGTH),
                role = node.role.take(NormalizedElement.MAX_ROLE_LENGTH),
                label = (node.name.ifBlank { null } ?: node.description.ifBlank { null })?.take(NormalizedElement.MAX_TEXT_LENGTH),
                text = if (node.sensitive) null else node.value?.ifBlank { null }?.take(NormalizedElement.MAX_TEXT_LENGTH),
                resourceId = node.attributes["id"]?.take(NormalizedElement.MAX_RESOURCE_ID_LENGTH),
                isClickable = node.clickable,
                isEditable = node.editable,
                isScrollable = node.role.equals("scrollbar", ignoreCase = true) || node.attributes.containsKey("data-scrollable"),
                isFocused = node.focused,
                isEnabled = !node.disabled,
                isVisible = node.visible,
                isPassword = node.sensitive,
                depth = node.depth
            )
        }
        val now = System.currentTimeMillis()
        val obsId = if (snapshot.snapshotId.isNotBlank()) "obs_wv_${snapshot.snapshotId}" else "obs_wv_$now"
        val target = snapshot.url.take(NormalizedObservation.MAX_TARGET_LENGTH)
        val title = snapshot.title.take(NormalizedObservation.MAX_TITLE_LENGTH)
        val summary = "WebView DOM snapshot (${elements.size} elements)"
        val boundedElements = NormalizedObservation.boundElements(elements, summary, target, title)
        return NormalizedObservation(
            observationId = obsId.take(160),
            source = ObservationSource.WEBVIEW_DOM,
            capturedAtEpochMs = now,
            targetPackageOrUrl = target,
            titleOrHeader = title,
            summary = summary,
            elements = boundedElements,
            totalElementsCount = snapshot.nodeCount,
            isTruncated = snapshot.truncated || boundedElements.size < elements.size
        )
    }

    override suspend fun navigate(url: String): Result<String> = runCatching {
        val client = clientProvider() ?: throw IllegalStateException("Native WebView unavailable.")
        val targetId = client.activeTargetId()
        val res = client.performAction(targetId, "@e0", "navigate", text = url)
        if (res.status == "SUCCESS" || res.status == "ACTION_COMPLETED") res.note else throw IllegalStateException(res.note)
    }

    override suspend fun click(identifier: String, confirmed: Boolean): Result<String> = runCatching {
        val client = clientProvider() ?: throw IllegalStateException("Native WebView unavailable.")
        val targetId = client.activeTargetId()
        val res = client.performAction(targetId, identifier, "click", confirmed = confirmed)
        if (res.status == "SUCCESS" || res.status == "ACTION_COMPLETED") res.note else throw IllegalStateException(res.note)
    }

    override suspend fun typeText(identifier: String, text: String, confirmed: Boolean): Result<String> = runCatching {
        val client = clientProvider() ?: throw IllegalStateException("Native WebView unavailable.")
        val targetId = client.activeTargetId()
        val res = client.performAction(targetId, identifier, "fill", text = text, confirmed = confirmed)
        if (res.status == "SUCCESS" || res.status == "ACTION_COMPLETED") res.note else throw IllegalStateException(res.note)
    }

    override suspend fun scroll(identifier: String?, forward: Boolean): Result<String> = runCatching {
        val client = clientProvider() ?: throw IllegalStateException("Native WebView unavailable.")
        val targetId = client.activeTargetId()
        val ref = identifier ?: "@e0"
        val directionAction = if (forward) "scroll_forward" else "scroll_backward"
        val res = client.performAction(targetId, ref, directionAction, confirmed = false)
        if (res.status in setOf("SCROLL_DISPATCHED", "SCROLL_BOUNDARY", "SUCCESS", "ACTION_COMPLETED")) {
            res.note
        } else {
            throw IllegalStateException(res.note)
        }
    }
}

/**
 * Unified target implementation for External Chrome (foreground Android Accessibility).
 * Truthfully reports limitations: NO DOM, NO CDP, NO COOKIES, ACCESSIBILITY TREE ONLY.
 */
class ExternalChromeTarget(
    private val isChromeActive: () -> Boolean,
    private val snapshotProvider: () -> UiScreenSnapshot?,
    private val pendingActionStore: PendingUiActionStore
) : UnifiedBrowserTarget {
    override val kind: BrowserTargetKind = BrowserTargetKind.EXTERNAL_CHROME
    override val displayName: String = "External Google Chrome (Accessibility View)"
    override val technicalBoundaries: String =
        "FOREGROUND ACCESSIBILITY ONLY. Exposes visible Android accessibility nodes. NO DOM, CDP, cookie, or hidden page state access. Actions require floating overlay tap in Chrome."

    override suspend fun isAvailable(): Boolean = isChromeActive()

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

    override suspend fun inspectCurrentState(includePrivate: Boolean, confirmed: Boolean): NormalizedObservation {
        val snapshot = snapshotProvider()
            ?: throw IllegalStateException("No Chrome Accessibility snapshot captured. Tap ERA overlay in Chrome.")
        val allNodes = flattenElements(snapshot.root)
        val elements = allNodes.take(NormalizedObservation.MAX_ELEMENTS).map { node ->
            NormalizedElement(
                elementId = node.nodeKey,
                role = node.className?.substringAfterLast('.') ?: node.role,
                label = node.text?.ifBlank { null } ?: node.contentDescription?.ifBlank { null } ?: node.hintText?.ifBlank { null },
                text = if (node.password) null else node.text?.ifBlank { null },
                resourceId = node.resourceId?.ifBlank { null },
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
        val title = (snapshot.windowTitle ?: "Google Chrome (Accessibility)").take(NormalizedObservation.MAX_TITLE_LENGTH)
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

    override suspend fun navigate(url: String): Result<String> {
        return Result.failure(
            UnsupportedOperationException("External Chrome does not support direct DOM navigation API via AccessibilityService. User must navigate manually or use Android VIEW intent.")
        )
    }

    override suspend fun click(identifier: String, confirmed: Boolean): Result<String> = runCatching {
        val snapshot = snapshotProvider() ?: throw IllegalStateException("No Chrome snapshot.")
        val fingerprint = computeFingerprint(snapshot)
        val approved = pendingActionStore.approve(
            selectorText = identifier,
            action = PendingUiActionStore.ACTION_CLICK,
            payload = "",
            expectedPackage = snapshot.packageName,
            resourceId = "",
            reviewedObservationId = fingerprint
        )
        if (approved) {
            "Click approved for '$identifier' in Chrome. Switch to Chrome and tap the ERA overlay to dispatch."
        } else {
            throw IllegalStateException("Failed to approve click action for Chrome.")
        }
    }

    override suspend fun typeText(identifier: String, text: String, confirmed: Boolean): Result<String> = runCatching {
        val snapshot = snapshotProvider() ?: throw IllegalStateException("No Chrome snapshot.")
        val fingerprint = computeFingerprint(snapshot)
        val approved = pendingActionStore.approve(
            selectorText = identifier,
            action = PendingUiActionStore.ACTION_TYPE_TEXT,
            payload = text,
            expectedPackage = snapshot.packageName,
            resourceId = "",
            reviewedObservationId = fingerprint
        )
        if (approved) {
            "Text entry approved for '$identifier' in Chrome. Switch to Chrome and tap the ERA overlay to dispatch."
        } else {
            throw IllegalStateException("Failed to approve text entry action for Chrome.")
        }
    }

    override suspend fun scroll(identifier: String?, forward: Boolean): Result<String> = runCatching {
        val snapshot = snapshotProvider() ?: throw IllegalStateException("No Chrome snapshot.")
        val selector = identifier ?: ""
        val scrollAction = if (forward) PendingUiActionStore.ACTION_SCROLL_FORWARD else PendingUiActionStore.ACTION_SCROLL_BACKWARD
        val fingerprint = computeFingerprint(snapshot)
        val approved = pendingActionStore.approve(
            selectorText = selector,
            action = scrollAction,
            payload = "",
            expectedPackage = snapshot.packageName,
            resourceId = "",
            reviewedObservationId = fingerprint
        )
        if (approved) {
            "Scroll approved for '$selector' in Chrome. Switch to Chrome and tap the ERA overlay to dispatch."
        } else {
            throw IllegalStateException("Failed to approve scroll action for Chrome.")
        }
    }
}
