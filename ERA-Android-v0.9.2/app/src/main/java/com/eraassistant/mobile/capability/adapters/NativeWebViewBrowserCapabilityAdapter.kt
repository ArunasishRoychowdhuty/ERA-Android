package com.eraassistant.mobile.capability.adapters

import android.os.SystemClock
import com.eraassistant.mobile.browser.PhoneBrowserAgentClient
import com.eraassistant.mobile.browser.PhoneBrowserSnapshot
import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedElement
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.observation.ObservationSource

/**
 * Adapter integrating ERA's in-app multi-tab WebView Browser Agent into the unified capability interface.
 * Preserves multi-tab handling, DOM semantic snapshots, shadow DOM, same-origin iframe traversal,
 * action APIs, and stale-reference validation.
 */
class NativeWebViewBrowserCapabilityAdapter(
    private val clientProvider: () -> PhoneBrowserAgentClient?
) : CapabilityAdapter {

    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId.NATIVE_WEBVIEW,
        name = "ERA In-App WebView Browser",
        description = "Automates web navigation, tab management, semantic DOM inspection, clicks, text input, dropdowns, and form interactions inside ERA's native WebView sandbox.",
        category = CapabilityCategory.WEB_BROWSER,
        supportedActions = setOf(
            ACTION_NAVIGATE,
            ACTION_SNAPSHOT,
            ACTION_CLICK,
            ACTION_FILL,
            ACTION_TYPE,
            ACTION_PRESS_KEY,
            ACTION_CHECK,
            ACTION_UNCHECK,
            ACTION_SELECT_OPTION,
            ACTION_SCROLL_INTO_VIEW,
            ACTION_NEW_TAB,
            ACTION_CLOSE_TAB,
            ACTION_SWITCH_TAB,
            ACTION_RELOAD,
            ACTION_GO_BACK,
            ACTION_GO_FORWARD
        ),
        requiredPermissions = emptySet(),
        executionConstraints = setOf(
            "Controls only pages inside ERA's WebView",
            "HTTPS pages only",
            "Up to 5 simultaneous tabs",
            "Consequential actions and sensitive fills require user approval"
        ),
        worksOffline = false,
        actionSchemas = CapabilitySchemas.nativeWebView()
    )

    override suspend fun checkAvailability(): CapabilityAvailability {
        val client = clientProvider()
            ?: return CapabilityAvailability.missing("WebView Browser Agent is not initialized in the active Activity.")
        return try {
            client.testConnection()
            CapabilityAvailability.AVAILABLE
        } catch (e: Exception) {
            CapabilityAvailability.missing("WebView connection failed: ${e.message}")
        }
    }

    override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? {
        val client = clientProvider()
            ?: return ExecutionError(ExecutionErrorCategory.ENVIRONMENT_ERROR, "WebView Browser Agent is uninitialized.")

        when (request.actionName) {
            ACTION_NAVIGATE -> {
                val url = request.param<String>("url")
                if (url.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'url' is required.")
                }
            }
            ACTION_CLICK, ACTION_FILL, ACTION_TYPE, ACTION_PRESS_KEY,
            ACTION_CHECK, ACTION_UNCHECK, ACTION_SELECT_OPTION, ACTION_SCROLL_INTO_VIEW -> {
                val ref = request.param<String>("ref")
                if (ref.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'ref' (e.g. '@e1') is required for element actions.")
                }
                if (request.actionName == ACTION_FILL && request.param<String>("text") == null) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'text' is required for fill action.")
                }
                if (request.actionName == ACTION_SELECT_OPTION && request.param<String>("value") == null) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'value' is required for select option action.")
                }
            }
            ACTION_SWITCH_TAB, ACTION_CLOSE_TAB -> {
                val targetId = request.param<String>("targetId")
                if (targetId.isNullOrBlank()) {
                    return ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Parameter 'targetId' is required.")
                }
            }
        }
        return null
    }

    override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
        val start = SystemClock.elapsedRealtime()
        val client = clientProvider()
            ?: return StructuredActionResult.failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.ENVIRONMENT_ERROR, "WebView Browser Agent unavailable"),
                durationMs = 0L
            )

        return try {
            val confirmed = request.approvalGranted
            val activeId = request.stringParam("targetId").ifBlank { client.activeTargetId() }

            when (request.actionName) {
                ACTION_NAVIGATE -> {
                    val url = request.stringParam("url")
                    val result = client.navigate(url)
                    val snapshot = runCatching { client.captureSnapshot(activeId, false, false) }.getOrNull()
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("message" to result, "url" to url),
                        observation = snapshot?.let { normalizeSnapshot(it) },
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_SNAPSHOT -> {
                    val includePrivate = request.boolParam("includePrivate", false)
                    val snapshot = client.captureSnapshot(activeId, includePrivateDetails = includePrivate, confirmed = confirmed)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("nodeCount" to snapshot.nodes.size, "url" to snapshot.url, "title" to snapshot.title),
                        observation = normalizeSnapshot(snapshot),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_CLICK -> {
                    val ref = request.stringParam("ref")
                    val result = client.performAction(activeId, ref, "click", confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_FILL -> {
                    val ref = request.stringParam("ref")
                    val text = request.stringParam("text")
                    val result = client.performAction(activeId, ref, "fill", text = text, confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_TYPE -> {
                    val ref = request.stringParam("ref")
                    val text = request.stringParam("text")
                    val result = client.performAction(activeId, ref, "type", text = text, confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_PRESS_KEY -> {
                    val ref = request.stringParam("ref")
                    val key = request.stringParam("key", "ENTER")
                    val result = client.performAction(activeId, ref, "press", text = key, confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_CHECK -> {
                    val ref = request.stringParam("ref")
                    val result = client.performAction(activeId, ref, "check", confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_UNCHECK -> {
                    val ref = request.stringParam("ref")
                    val result = client.performAction(activeId, ref, "uncheck", confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_SELECT_OPTION -> {
                    val ref = request.stringParam("ref")
                    val value = request.stringParam("value")
                    val result = client.performAction(activeId, ref, "select", text = value, confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_SCROLL_INTO_VIEW -> {
                    val ref = request.stringParam("ref")
                    val result = client.performAction(activeId, ref, "scroll", confirmed = confirmed)
                    createActionResult(request.actionId, result, start)
                }
                ACTION_NEW_TAB -> {
                    val input = request.stringParam("url", "")
                    val targetId = client.newTab(input)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("targetId" to targetId),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_CLOSE_TAB -> {
                    val targetId = request.stringParam("targetId", activeId)
                    val closed = client.closeSession(targetId)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("closed" to closed),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_SWITCH_TAB -> {
                    val targetId = request.stringParam("targetId")
                    client.selectSession(targetId)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("activeTargetId" to targetId),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_RELOAD -> {
                    client.reload(activeId)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("message" to "Reload initiated"),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_GO_BACK -> {
                    val success = client.goBack(activeId)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("success" to success),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                ACTION_GO_FORWARD -> {
                    val success = client.goForward(activeId)
                    StructuredActionResult.success(
                        actionId = request.actionId,
                        outputData = mapOf("success" to success),
                        durationMs = SystemClock.elapsedRealtime() - start
                    )
                }
                else -> StructuredActionResult.failure(
                    request.actionId,
                    ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Action '${request.actionName}' not supported by WebView adapter"),
                    durationMs = SystemClock.elapsedRealtime() - start
                )
            }
        } catch (t: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE, "WebView execution failed: ${t.message}", cause = t),
                durationMs = SystemClock.elapsedRealtime() - start
            )
        }
    }

    private fun createActionResult(
        actionId: String,
        result: com.eraassistant.mobile.browser.PhoneBrowserActionResult,
        startTime: Long
    ): StructuredActionResult {
        val duration = SystemClock.elapsedRealtime() - startTime
        val obs = result.followUpSnapshot?.let { normalizeSnapshot(it) }
        return if (result.status == "SUCCESS" || result.status == "ACTION_COMPLETED") {
            StructuredActionResult.success(
                actionId = actionId,
                outputData = mapOf(
                    "status" to result.status,
                    "action" to result.action,
                    "ref" to result.ref,
                    "note" to result.note,
                    "verified" to result.verified
                ),
                observation = obs,
                durationMs = duration
            )
        } else {
            StructuredActionResult.failure(
                actionId = actionId,
                error = ExecutionError(
                    ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE,
                    "Browser action ${result.action} on ${result.ref} reported ${result.status}: ${result.note}"
                ),
                observation = obs,
                durationMs = duration
            )
        }
    }

    private fun normalizeSnapshot(snapshot: PhoneBrowserSnapshot): NormalizedObservation {
        val elements = snapshot.nodes.take(NormalizedObservation.MAX_ELEMENTS).map { node ->
            NormalizedElement(
                elementId = node.ref.take(NormalizedElement.MAX_ID_LENGTH),
                role = node.role.take(NormalizedElement.MAX_ROLE_LENGTH),
                label = node.name.ifBlank { null }?.take(NormalizedElement.MAX_TEXT_LENGTH),
                text = if (node.sensitive) null else node.description.ifBlank { null }?.take(NormalizedElement.MAX_TEXT_LENGTH),
                isClickable = node.clickable,
                isEditable = node.editable,
                isScrollable = node.visible,
                isFocused = node.focused,
                isEnabled = !node.disabled,
                isVisible = node.visible,
                isPassword = node.sensitive,
                depth = node.depth,
                parentId = node.parentRef.ifBlank { null }?.take(NormalizedElement.MAX_ID_LENGTH),
                attributes = buildMap {
                    node.value?.let { if (it.isNotBlank() && !node.sensitive) put("value", it.take(NormalizedElement.MAX_ATTRIBUTE_VALUE_LENGTH)) }
                    node.attributes["href"]?.let { put("href", it.take(NormalizedElement.MAX_ATTRIBUTE_VALUE_LENGTH)) }
                    if (node.checked != null) put("checked", node.checked.toString())
                }
            )
        }
        val target = snapshot.url.take(NormalizedObservation.MAX_TARGET_LENGTH)
        val title = snapshot.title.take(NormalizedObservation.MAX_TITLE_LENGTH)
        val summary = "WebView DOM snapshot (${elements.size} nodes, ${snapshot.frameCount} frames)"
        val boundedElements = NormalizedObservation.boundElements(elements, summary, target, title)
        return NormalizedObservation(
            observationId = "obs_wv_${snapshot.snapshotId}".take(160),
            source = ObservationSource.WEBVIEW_DOM,
            capturedAtEpochMs = System.currentTimeMillis(),
            targetPackageOrUrl = target,
            titleOrHeader = title,
            summary = summary,
            elements = boundedElements,
            totalElementsCount = snapshot.nodes.size,
            isTruncated = snapshot.truncated || boundedElements.size < elements.size
        )
    }

    companion object {
        const val ACTION_NAVIGATE = "browser.webview.navigate"
        const val ACTION_SNAPSHOT = "browser.webview.snapshot"
        const val ACTION_CLICK = "browser.webview.click"
        const val ACTION_FILL = "browser.webview.fill"
        const val ACTION_TYPE = "browser.webview.type"
        const val ACTION_PRESS_KEY = "browser.webview.press_key"
        const val ACTION_CHECK = "browser.webview.check"
        const val ACTION_UNCHECK = "browser.webview.uncheck"
        const val ACTION_SELECT_OPTION = "browser.webview.select_option"
        const val ACTION_SCROLL_INTO_VIEW = "browser.webview.scroll_into_view"
        const val ACTION_NEW_TAB = "browser.webview.tab.new"
        const val ACTION_CLOSE_TAB = "browser.webview.tab.close"
        const val ACTION_SWITCH_TAB = "browser.webview.tab.switch"
        const val ACTION_RELOAD = "browser.webview.reload"
        const val ACTION_GO_BACK = "browser.webview.go_back"
        const val ACTION_GO_FORWARD = "browser.webview.go_forward"
    }
}
