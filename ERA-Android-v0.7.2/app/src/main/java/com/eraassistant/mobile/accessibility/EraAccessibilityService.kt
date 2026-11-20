package com.eraassistant.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.eraassistant.mobile.screen.PendingUiActionStore
import com.eraassistant.mobile.screen.ScreenBounds
import com.eraassistant.mobile.screen.SemanticUiSelector
import com.eraassistant.mobile.screen.SemanticUiSelectorEngine
import com.eraassistant.mobile.screen.SelectorResolution
import com.eraassistant.mobile.screen.UiActionProposal
import com.eraassistant.mobile.screen.UiActionProposalValidator
import com.eraassistant.mobile.screen.UiElement
import com.eraassistant.mobile.screen.UiScreenSnapshot
import com.eraassistant.mobile.screen.UiSnapshotDiffEngine
import com.eraassistant.mobile.screen.UiSnapshotDiffJson
import com.eraassistant.mobile.screen.UiTreeJson
import com.eraassistant.mobile.screen.UiSnapshotFingerprint
import org.json.JSONObject
import com.eraassistant.mobile.screen.SemanticUiAction

/** Optional screen observer and one-shot, user-approved semantic UI action service. */
class EraAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var inspectButton: TextView? = null
    private var nodeCounter = 0
    private var truncated = false
    private var previousSnapshot: UiScreenSnapshot? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoCaptureRunnable = Runnable { captureCurrentWindow(showToast = false) }
    private lateinit var pendingActionStore: PendingUiActionStore
    private lateinit var actionPrefs: SharedPreferences
    private lateinit var actionPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var appPrefs: SharedPreferences
    private lateinit var appPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    private var pendingVerification: PendingVerification? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        pendingActionStore = PendingUiActionStore(this)
        actionPrefs = getSharedPreferences(PendingUiActionStore.PREFS, MODE_PRIVATE)
        actionPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateOverlayLabel() }
        actionPrefs.registerOnSharedPreferenceChangeListener(actionPrefsListener)
        appPrefs = getSharedPreferences("era_preferences", MODE_PRIVATE)
        appPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACCESSIBILITY_CONSENT && !isScreenInspectionConsented()) {
                handler.removeCallbacks(autoCaptureRunnable)
                previousSnapshot = null
                pendingVerification = null
                if (::pendingActionStore.isInitialized) pendingActionStore.clearPending("Screen-inspection consent revoked. Pending action cancelled.")
                saveCaptureError("Screen-inspection consent has been revoked.")
            }
        }
        appPrefs.registerOnSharedPreferenceChangeListener(appPrefsListener)
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 250
        }
        addInspectorOverlay()
        updateOverlayLabel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        val preferences = getSharedPreferences("era_preferences", MODE_PRIVATE)
        val consented = preferences.getBoolean(KEY_ACCESSIBILITY_CONSENT, false)
        val autoObserve = preferences.getBoolean(KEY_AUTO_OBSERVE, false)
        if (!consented || !autoObserve) return
        val supported = event.eventType in setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        )
        if (!supported || isDeviceLocked()) return
        handler.removeCallbacks(autoCaptureRunnable)
        handler.postDelayed(autoCaptureRunnable, AUTO_CAPTURE_DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeInspectorOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::actionPrefs.isInitialized && ::actionPrefsListener.isInitialized) {
            actionPrefs.unregisterOnSharedPreferenceChangeListener(actionPrefsListener)
        }
        if (::appPrefs.isInitialized && ::appPrefsListener.isInitialized) {
            appPrefs.unregisterOnSharedPreferenceChangeListener(appPrefsListener)
        }
        removeInspectorOverlay()
        super.onDestroy()
    }

    private fun addInspectorOverlay() {
        if (inspectButton != null) return
        val button = TextView(this).apply {
            text = "ERA · Capture UI"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6212938.toInt())
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFF6EA8FE.toInt())
            }
            contentDescription = "Capture UI or run one approved ERA semantic action"
            setOnClickListener {
                if (::pendingActionStore.isInitialized && pendingActionStore.isPending()) executePendingUiAction()
                else captureCurrentWindow(showToast = true)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(12)
            y = dp(112)
        }
        runCatching {
            windowManager?.addView(button, params)
            inspectButton = button
        }.onFailure {
            Toast.makeText(this, "ERA screen inspector overlay unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOverlayLabel() {
        inspectButton?.text = if (::pendingActionStore.isInitialized && pendingActionStore.isPending()) {
            "ERA · Run approved action"
        } else "ERA · Capture UI"
    }

    private fun removeInspectorOverlay() {
        val current = inspectButton ?: return
        runCatching { windowManager?.removeView(current) }
        inspectButton = null
    }

    private fun isScreenInspectionConsented(): Boolean =
        getSharedPreferences("era_preferences", MODE_PRIVATE).getBoolean(KEY_ACCESSIBILITY_CONSENT, false)

    private fun isDeviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun captureCurrentWindow(showToast: Boolean) {
        if (!isScreenInspectionConsented()) {
            saveCaptureError("Accept the screen-inspection disclosure in ERA before capturing screen content.")
            if (showToast) Toast.makeText(this, "Screen-inspection consent is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (isDeviceLocked()) {
            saveCaptureError("Screen inspection is unavailable while the phone is locked.")
            if (showToast) Toast.makeText(this, "Unlock the phone before inspecting a screen", Toast.LENGTH_SHORT).show()
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            saveCaptureError("No active accessible window. Open an app screen and try again.")
            if (showToast) Toast.makeText(this, "No accessible screen found", Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = buildSnapshot(root) ?: return
        verifyPreviousAction(snapshot)
        storeSnapshot(snapshot)
        if (showToast) Toast.makeText(this, "ERA captured ${snapshot.nodeCount} UI elements", Toast.LENGTH_SHORT).show()
    }

    private fun executePendingUiAction() {
        if (!isScreenInspectionConsented()) {
            pendingActionStore.clearPending("Screen-inspection consent is not active. Action was not run.")
            return
        }
        if (isDeviceLocked()) {
            pendingActionStore.clearPending("Unlock the phone before running an approved UI action.")
            Toast.makeText(this, "Unlock the phone first", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = pendingActionStore.pending()
        if (pending == null) {
            updateOverlayLabel()
            captureCurrentWindow(showToast = true)
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            pendingActionStore.clearPending("No active accessible window. Action was not run.")
            return
        }
        val snapshot = buildSnapshot(root)
        if (snapshot == null) {
            pendingActionStore.clearPending("Could not inspect the current screen. Action was not run.")
            return
        }
        if (!pending.expectedPackage.isNullOrBlank() &&
            !pending.expectedPackage.equals(snapshot.packageName, ignoreCase = true)
        ) {
            storeSnapshot(snapshot)
            pendingActionStore.clearPending("Current app differs from the app you reviewed. Action was not run; inspect and approve again.")
            Toast.makeText(this, "Wrong app: action cancelled", Toast.LENGTH_LONG).show()
            return
        }

        // Approval is valid only for the exact serialized accessibility tree the user reviewed.
        // A package match alone is not enough: any observed UI change cancels the one-shot action.
        val freshObservationId = runCatching {
            val encoded = JSONObject(UiTreeJson.encode(snapshot))
            UiSnapshotFingerprint.of(
                packageName = snapshot.packageName,
                canonicalRootJson = encoded.getJSONObject("root").toString(),
                nodeCount = snapshot.nodeCount,
                truncated = snapshot.truncated
            )
        }.getOrNull()
        if (freshObservationId == null || freshObservationId != pending.reviewedObservationId) {
            storeSnapshot(snapshot)
            pendingActionStore.clearPending("The screen changed after approval. Nothing was run; review the new screen and approve again.")
            Toast.makeText(this, "Screen changed: action cancelled", Toast.LENGTH_LONG).show()
            updateOverlayLabel()
            return
        }

        val semanticAction = when (pending.action) {
            PendingUiActionStore.ACTION_CLICK -> SemanticUiAction.CLICK
            PendingUiActionStore.ACTION_TYPE_TEXT -> SemanticUiAction.TYPE_TEXT
            PendingUiActionStore.ACTION_SCROLL_FORWARD -> SemanticUiAction.SCROLL_FORWARD
            PendingUiActionStore.ACTION_SCROLL_BACKWARD -> SemanticUiAction.SCROLL_BACKWARD
            else -> {
                pendingActionStore.clearPending("Unsupported action. Nothing was run.")
                return
            }
        }
        val selector = SemanticUiSelector(
            resourceId = pending.resourceId.takeIf(String::isNotBlank),
            text = pending.selectorText.takeIf(String::isNotBlank),
            clickable = if (semanticAction == SemanticUiAction.CLICK) true else null,
            editable = if (semanticAction == SemanticUiAction.TYPE_TEXT) true else null,
            enabled = true,
            exactText = true
        )
        val proposal = UiActionProposal(selector, semanticAction, pending.payload, "Explicit one-shot user approval in ERA.")
        val target = when (val validation = UiActionProposalValidator.validate(snapshot.root, proposal)) {
            is com.eraassistant.mobile.screen.UiActionValidation.ReadyForUserReview -> validation.target
            com.eraassistant.mobile.screen.UiActionValidation.NotFound -> {
                storeSnapshot(snapshot)
                pendingActionStore.clearPending("Target '${pending.selectorText}' was not found. Nothing was run.")
                return
            }
            com.eraassistant.mobile.screen.UiActionValidation.NeedsDisambiguation -> {
                storeSnapshot(snapshot)
                pendingActionStore.clearPending("More than one element matched '${pending.selectorText}'. Nothing was run.")
                return
            }
            is com.eraassistant.mobile.screen.UiActionValidation.Rejected -> {
                storeSnapshot(snapshot)
                pendingActionStore.clearPending("Action rejected: ${validation.reason}")
                return
            }
        }

        if (semanticAction == SemanticUiAction.CLICK && isConsequentialControl(target.text.orEmpty() + " " + target.contentDescription.orEmpty() + " " + target.hintText.orEmpty() + " " + target.resourceId.orEmpty() + " " + target.role)) {
            storeSnapshot(snapshot)
            pendingActionStore.clearPending("This control appears consequential. ERA leaves it for a separate manual tap.")
            return
        }

        // The pending approval is consumed before execution: retries require a new user approval.
        pendingActionStore.consume()
        val liveNode = nodeAtFreshPath(root, target.nodeKey)
        if (liveNode == null || !liveNode.isVisibleToUser || !liveNode.isEnabled) {
            pendingActionStore.setResult("The target changed after observation. Action was not run; approve again.")
            storeSnapshot(snapshot)
            updateOverlayLabel()
            return
        }
        val performed = runCatching {
            when (semanticAction) {
                SemanticUiAction.CLICK -> liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SemanticUiAction.TYPE_TEXT -> {
                    if (liveNode.isPassword || !liveNode.isEditable) false
                    else {
                        liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pending.payload)
                        }
                        liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
                SemanticUiAction.SCROLL_FORWARD -> liveNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SemanticUiAction.SCROLL_BACKWARD -> liveNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                else -> false
            }
        }.getOrDefault(false)
        val result = if (performed) {
            "Approved ${semanticAction.name.lowercase()} action dispatched to '${pending.selectorText}'. Verifying new screen…"
        } else {
            "Android rejected the ${semanticAction.name.lowercase()} action. Nothing further was attempted."
        }
        pendingActionStore.setResult(result)
        if (performed) pendingVerification = PendingVerification(semanticAction, pending.payload, snapshot)
        updateOverlayLabel()
        Toast.makeText(this, result, Toast.LENGTH_LONG).show()
        if (performed) handler.postDelayed({ captureCurrentWindow(showToast = false) }, POST_ACTION_VERIFY_DELAY_MS)
        else storeSnapshot(snapshot)
    }

    private data class PendingVerification(
        val action: SemanticUiAction,
        val expectedText: String,
        val beforeSnapshot: UiScreenSnapshot
    )

    private fun verifyPreviousAction(snapshot: UiScreenSnapshot) {
        val pending = pendingVerification ?: return
        pendingVerification = null
        val allText = buildString {
            fun visit(element: UiElement) {
                append(element.text.orEmpty()).append(' ')
                append(element.contentDescription.orEmpty()).append(' ')
                element.children.forEach(::visit)
            }
            visit(snapshot.root)
        }
        val verified = when (pending.action) {
            SemanticUiAction.TYPE_TEXT -> pending.expectedText.isNotBlank() && allText.contains(pending.expectedText, ignoreCase = true)
            SemanticUiAction.CLICK, SemanticUiAction.SCROLL_FORWARD, SemanticUiAction.SCROLL_BACKWARD ->
                !UiSnapshotDiffEngine.diff(pending.beforeSnapshot.root, snapshot.root).isEmpty
            else -> false
        }
        val message = when {
            pending.action == SemanticUiAction.TYPE_TEXT && verified -> "Verified: requested text is present in the fresh structured UI snapshot."
            pending.action in setOf(SemanticUiAction.CLICK, SemanticUiAction.SCROLL_FORWARD, SemanticUiAction.SCROLL_BACKWARD) && verified -> "Observed a UI tree change after the approved action. Inspect the new snapshot to confirm the intended result."
            else -> "Action was dispatched, but the expected result was not observable. Inspect the screen; ERA did not retry automatically."
        }
        pendingActionStore.setResult(message)
    }

    private fun isConsequentialControl(value: String): Boolean {
        val lower = value.lowercase().trim()
        val normalized = lower.replace(Regex("[_-]+"), " ")
        val risky = listOf(
            "send", "publish", "post", "submit", "pay", "payment", "purchase", "buy", "checkout", "delete", "remove",
            "share", "transfer", "call", "confirm", "allow", "grant", "install", "place order", "subscribe",
            "পাঠান", "পাঠিয়ে দিন", "পোস্ট", "প্রকাশ", "জমা দিন", "দাখিল", "পেমেন্ট", "পরিশোধ", "কিনুন",
            "মুছে ফেল", "ডিলিট", "ট্রান্সফার", "নিশ্চিত করুন", "শেয়ার", "কল", "অনুমতি দিন", "ইনস্টল", "অর্ডার", "স্থানান্তর"
        )
        return risky.any { normalized == it || normalized.contains(it) }
    }

    private fun buildSnapshot(root: AccessibilityNodeInfo): UiScreenSnapshot? {
        nodeCounter = 0
        truncated = false
        val rootElement = buildElement(root, path = "0", parentKey = null, depth = 0)
        if (rootElement == null) {
            saveCaptureError("The active screen did not expose an accessible UI tree.")
            return null
        }
        return UiScreenSnapshot(
            capturedAtEpochMs = System.currentTimeMillis(),
            packageName = root.packageName?.toString(),
            windowTitle = runCatching { windows.firstOrNull { it.isActive }?.title?.toString() }.getOrNull()
                ?.takeIf(String::isNotBlank) ?: root.contentDescription?.toString()?.takeIf(String::isNotBlank),
            root = rootElement,
            nodeCount = nodeCounter,
            truncated = truncated
        )
    }

    private fun storeSnapshot(snapshot: UiScreenSnapshot) {
        val json = UiTreeJson.encode(snapshot)
        val previous = previousSnapshot
        val diffJson = previous?.takeIf { it.packageName == snapshot.packageName }
            ?.let { UiSnapshotDiffJson.encode(UiSnapshotDiffEngine.diff(it.root, snapshot.root)) }
        previousSnapshot = snapshot
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_JSON, json)
            .putString(KEY_DIFF, diffJson)
            .putLong(KEY_CAPTURED_AT, snapshot.capturedAtEpochMs)
            .putString(KEY_PACKAGE, snapshot.packageName)
            .remove(KEY_ERROR)
            .apply()
    }

    /** Resolve by the just-captured path only; old paths and coordinates are never reused. */
    private fun nodeAtFreshPath(root: AccessibilityNodeInfo, nodeKey: String): AccessibilityNodeInfo? {
        val parts = nodeKey.split('.').drop(1).mapNotNull(String::toIntOrNull)
        var current = root
        for (index in parts) {
            if (index !in 0 until current.childCount) return null
            current = runCatching { current.getChild(index) }.getOrNull() ?: return null
        }
        return current
    }

    private fun buildElement(
        node: AccessibilityNodeInfo,
        path: String,
        parentKey: String?,
        depth: Int,
        inheritedPassword: Boolean = false
    ): UiElement? {
        if (nodeCounter >= MAX_NODES || depth > MAX_DEPTH) {
            truncated = true
            return null
        }
        nodeCounter += 1
        val key = path
        val password = inheritedPassword || node.isPassword
        val rect = Rect()
        node.getBoundsInScreen(rect)
        // Password fields remain redacted. Other visible text handling is unchanged in this release.
        val safeText = if (password) null else node.text?.toString()?.take(MAX_TEXT_LENGTH)?.takeIf(String::isNotBlank)
        val safeDescription = if (password) null else node.contentDescription?.toString()?.take(MAX_TEXT_LENGTH)?.takeIf(String::isNotBlank)
        val safeHint = if (password) null else node.hintText?.toString()?.take(MAX_TEXT_LENGTH)?.takeIf(String::isNotBlank)
        val actionNames = node.actionList.mapNotNull { action -> actionName(action.id) }.distinct()
        val children = ArrayList<UiElement>()
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val childElement = buildElement(child, "$path.$index", key, depth + 1, password)
            if (childElement != null) children += childElement
            if (nodeCounter >= MAX_NODES) {
                truncated = true
                break
            }
        }
        val className = node.className?.toString()
        return UiElement(
            nodeKey = key,
            parentKey = parentKey,
            resourceId = node.viewIdResourceName?.takeIf(String::isNotBlank),
            role = inferRole(className, node.isEditable, node.isClickable, node.isCheckable, safeText, safeDescription),
            text = safeText,
            contentDescription = safeDescription,
            hintText = safeHint,
            className = className,
            packageName = node.packageName?.toString(),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            focusable = node.isFocusable,
            focused = node.isFocused,
            enabled = node.isEnabled,
            selected = node.isSelected,
            checkable = node.isCheckable,
            checked = node.isChecked,
            scrollable = node.isScrollable,
            password = password,
            visible = node.isVisibleToUser,
            link = className?.contains("link", ignoreCase = true) == true ||
                (node.isClickable && looksLikeLink(safeText ?: safeDescription.orEmpty())),
            actions = actionNames,
            bounds = ScreenBounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children
        )
    }

    private fun inferRole(
        className: String?, editable: Boolean, clickable: Boolean,
        checkable: Boolean, text: String?, description: String?
    ): String {
        val name = className.orEmpty().lowercase()
        return when {
            editable || name.contains("edittext") || name.contains("textfield") -> "input"
            name.contains("button") -> "button"
            name.contains("checkbox") -> "checkbox"
            name.contains("radiobutton") -> "radio"
            name.contains("switch") || name.contains("toggle") -> "switch"
            name.contains("spinner") || name.contains("dropdown") -> "combobox"
            name.contains("seekbar") || name.contains("slider") -> "slider"
            name.contains("recyclerview") || name.contains("listview") -> "list"
            name.contains("webview") -> "webview"
            name.contains("image") -> if (clickable) "image_button" else "image"
            name.contains("text") && clickable && looksLikeLink(text ?: description.orEmpty()) -> "link"
            name.contains("text") -> "text"
            checkable -> "checkable"
            clickable -> "button_or_control"
            else -> "container"
        }
    }

    private fun looksLikeLink(value: String): Boolean =
        value.startsWith("https://", true) || value.startsWith("http://", true) || value.contains("www.", true)

    private fun actionName(id: Int): String? = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "accessibility_focus"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "clear_accessibility_focus"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"
        AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_selection"
        else -> null
    }

    private fun saveCaptureError(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_ERROR, message)
            .remove(KEY_JSON)
            .remove(KEY_DIFF)
            .apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS = "era_ui_snapshot"
        const val KEY_JSON = "latest_json"
        const val KEY_CAPTURED_AT = "captured_at"
        const val KEY_PACKAGE = "package_name"
        const val KEY_ERROR = "capture_error"
        const val KEY_DIFF = "latest_diff"
        const val KEY_AUTO_OBSERVE = "auto_observe"
        const val KEY_ACCESSIBILITY_CONSENT = "accessibility_consent_accepted"
        const val MAX_NODES = 800
        const val MAX_DEPTH = 30
        const val MAX_TEXT_LENGTH = 300
        const val AUTO_CAPTURE_DEBOUNCE_MS = 450L
        const val POST_ACTION_VERIFY_DELAY_MS = 700L
    }
}
