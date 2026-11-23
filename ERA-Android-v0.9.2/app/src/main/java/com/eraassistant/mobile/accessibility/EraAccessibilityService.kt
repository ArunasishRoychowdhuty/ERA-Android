package com.eraassistant.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.util.DisplayMetrics
import android.view.View
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
import com.eraassistant.mobile.screen.VisualFrameStore
import com.eraassistant.mobile.screen.VisualFrameIdentity
import com.eraassistant.mobile.screen.VisualTargetProposal
import com.eraassistant.mobile.screen.VisualTargetResolveInput
import com.eraassistant.mobile.screen.VisualTargetResolver
import com.eraassistant.mobile.screen.VisualTargetResolution
import org.json.JSONObject
import com.eraassistant.mobile.screen.SemanticUiAction
import com.eraassistant.mobile.screen.SensitiveInputPolicy
import com.eraassistant.mobile.execution.AutonomyExecutionPolicy
import com.eraassistant.mobile.execution.AutonomyGateInput
import com.eraassistant.mobile.execution.AutonomyGateDecision
import com.eraassistant.mobile.planner.TaskRisk

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
    private var visualCapturePending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        activeInstance = this
        pendingActionStore = PendingUiActionStore(this)
        actionPrefs = getSharedPreferences(PendingUiActionStore.PREFS, MODE_PRIVATE)
        actionPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            updateOverlayLabel()
            if (key == PendingUiActionStore.KEY_CONSEQUENCE_APPROVED &&
                actionPrefs.getBoolean(PendingUiActionStore.KEY_CONSEQUENCE_APPROVED, false)) {
                // MainActivity moves to the background after announcing OK so the
                // original target app can regain focus. Re-observe only after that transition.
                handler.postDelayed({ executePendingUiAction() }, 1_000L)
            }
        }
        actionPrefs.registerOnSharedPreferenceChangeListener(actionPrefsListener)
        appPrefs = getSharedPreferences("era_preferences", MODE_PRIVATE)
        appPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_EXTERNAL_CHROME_TARGET_MODE) updateOverlayLabel()
            if (key == KEY_ACCESSIBILITY_CONSENT && !isScreenInspectionConsented()) {
                handler.removeCallbacks(autoCaptureRunnable)
                previousSnapshot = null
                pendingVerification = null
                visualCapturePending = false
                VisualFrameStore.clear()
                appPrefs.edit().putBoolean(KEY_EXTERNAL_CHROME_TARGET_MODE, false).apply()
                getSharedPreferences(ExternalChromeAgentPolicy.PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
                if (::pendingActionStore.isInitialized) pendingActionStore.clearPending("Screen-inspection consent revoked. Pending action cancelled.")
                saveCaptureError("Screen-inspection consent has been revoked.")
                saveVisualCaptureStatus("Visual capture cleared because screen-inspection consent was revoked.")
                inspectButton?.visibility = View.VISIBLE
                updateOverlayLabel()
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
        visualCapturePending = false
        VisualFrameStore.clear()
        if (activeInstance === this) activeInstance = null
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
                else if (isExternalChromeTargetMode()) captureExternalChromeWindow(showToast = true)
                else captureCurrentWindow(showToast = true)
            }
            setOnLongClickListener {
                if (::pendingActionStore.isInitialized && pendingActionStore.isPending()) {
                    Toast.makeText(this@EraAccessibilityService, "Review or cancel the approved action before visual capture", Toast.LENGTH_LONG).show()
                } else {
                    captureVisualFrame()
                }
                true
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
            if (pendingActionStore.isAwaitingConsequenceApproval()) "ERA · Waiting for OK"
            else "ERA · Run approved action"
        } else if (isExternalChromeTargetMode()) "ERA · Capture Chrome UI"
        else "ERA · Capture UI"
    }

    private fun removeInspectorOverlay() {
        val current = inspectButton ?: return
        runCatching { windowManager?.removeView(current) }
        inspectButton = null
    }

    private fun isScreenInspectionConsented(): Boolean =
        getSharedPreferences("era_preferences", MODE_PRIVATE).getBoolean(KEY_ACCESSIBILITY_CONSENT, false)

    private fun isExternalChromeTargetMode(): Boolean =
        getSharedPreferences("era_preferences", MODE_PRIVATE).getBoolean(KEY_EXTERNAL_CHROME_TARGET_MODE, false)

    private fun saveExternalChromeStatus(message: String, capturedPackage: String? = null) {
        getSharedPreferences(ExternalChromeAgentPolicy.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(ExternalChromeAgentPolicy.KEY_STATUS, message.take(500))
            .putString(ExternalChromeAgentPolicy.KEY_PACKAGE, capturedPackage)
            .putLong(ExternalChromeAgentPolicy.KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun captureExternalChromeWindow(showToast: Boolean) {
        if (!isScreenInspectionConsented()) {
            saveExternalChromeStatus("Screen-inspection consent is required. No Chrome content was captured.")
            return
        }
        if (isDeviceLocked()) {
            saveExternalChromeStatus("Unlock the phone before capturing Chrome.")
            if (showToast) Toast.makeText(this, "Unlock the phone before inspecting Chrome", Toast.LENGTH_SHORT).show()
            return
        }
        val root = rootInActiveWindow
        val activePackage = root?.packageName?.toString()
        if (root == null || !ExternalChromeAgentPolicy.isSupportedPackage(activePackage)) {
            val message = "Chrome target is active, but Chrome is not the foreground app. Switch to Chrome and tap the ERA overlay."
            saveExternalChromeStatus(message, activePackage)
            if (showToast) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val snapshot = buildSnapshot(root)
        if (snapshot == null) {
            val message = "Chrome did not expose an accessible tree on this screen."
            saveExternalChromeStatus(message, activePackage)
            if (showToast) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        verifyPreviousAction(snapshot)
        storeSnapshot(snapshot)
        val message = "Captured " + snapshot.nodeCount + " visible Chrome accessibility nodes. Editable values and address-bar text are omitted; actions still require a separate ERA approval."
        saveExternalChromeStatus(message, activePackage)
        if (showToast) Toast.makeText(this, "Chrome UI captured", Toast.LENGTH_SHORT).show()
    }

    private fun isDeviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun captureCurrentWindow(showToast: Boolean) {
        if (isExternalChromeTargetMode()) {
            captureExternalChromeWindow(showToast)
            return
        }
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

    /**
     * Visual capture is intentionally opt-in: the user must long-press the ERA
     * overlay while viewing the target app. The bitmap is copied to process
     * memory only and is never persisted to disk by this service.
     */
    private fun captureVisualFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            saveVisualCaptureStatus("Visual capture requires Android 11 or newer.")
            Toast.makeText(this, "Visual capture requires Android 11+", Toast.LENGTH_LONG).show()
            return
        }
        if (!isScreenInspectionConsented()) {
            saveVisualCaptureStatus("Accept the screen-inspection disclosure in ERA before visual capture.")
            Toast.makeText(this, "Screen-inspection consent is required", Toast.LENGTH_LONG).show()
            return
        }
        if (isDeviceLocked()) {
            saveVisualCaptureStatus("Unlock the phone before visual capture.")
            Toast.makeText(this, "Unlock the phone before visual capture", Toast.LENGTH_LONG).show()
            return
        }
        if (visualCapturePending) {
            Toast.makeText(this, "A visual capture is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        val activeRoot = rootInActiveWindow
        if (activeRoot == null) {
            saveVisualCaptureStatus("No active accessible app window. Open the target app and try again.")
            Toast.makeText(this, "No active app window found", Toast.LENGTH_LONG).show()
            return
        }

        val sourcePackage = activeRoot.packageName?.toString()
        val initialSnapshot = buildSnapshot(activeRoot)
        val initialFingerprint = initialSnapshot?.let(::fingerprintSnapshot)
        if (initialFingerprint.isNullOrBlank()) {
            finishVisualCaptureWithError("Could not bind the screenshot to a stable UI observation.")
            return
        }
        visualCapturePending = true
        inspectButton?.visibility = View.INVISIBLE
        Toast.makeText(this, "Capturing one visual frame in memory…", Toast.LENGTH_SHORT).show()

        // Hide the accessibility overlay before capturing the display.
        handler.postDelayed({
            if (!visualCapturePending) return@postDelayed
            if (!isScreenInspectionConsented() || isDeviceLocked()) {
                finishVisualCaptureWithError("Consent changed or the phone was locked. No visual frame was kept.")
                return@postDelayed
            }
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            if (!visualCapturePending || !isScreenInspectionConsented()) {
                                runCatching { screenshot.hardwareBuffer.close() }
                                return
                            }
                            val buffer = screenshot.hardwareBuffer
                            var hardwareBitmap: Bitmap? = null
                            val softwareBitmap = try {
                                hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (_: Throwable) {
                                null
                            } finally {
                                runCatching { hardwareBitmap?.recycle() }
                                runCatching { buffer.close() }
                            }

                            if (softwareBitmap == null) {
                                finishVisualCaptureWithError("Android returned a frame that ERA could not copy into memory.")
                                return
                            }
                            if (!visualCapturePending || !isScreenInspectionConsented() || isDeviceLocked()) {
                                runCatching { softwareBitmap.recycle() }
                                finishVisualCaptureWithError("Consent changed or the phone was locked. No visual frame was kept.")
                                return
                            }

                            val currentRoot = rootInActiveWindow
                            val currentSnapshot = currentRoot?.let(::buildSnapshot)
                            val currentFingerprint = currentSnapshot?.let(::fingerprintSnapshot)
                            if (currentRoot?.packageName?.toString() != sourcePackage ||
                                currentFingerprint != initialFingerprint
                            ) {
                                runCatching { softwareBitmap.recycle() }
                                finishVisualCaptureWithError("The app UI changed during capture. Capture the screen again before visual analysis.")
                                return
                            }
                            VisualFrameStore.replace(
                                bitmap = softwareBitmap,
                                capturedAtEpochMs = System.currentTimeMillis(),
                                sourcePackage = sourcePackage,
                                screenFingerprint = initialFingerprint
                            )
                            visualCapturePending = false
                            inspectButton?.visibility = View.VISIBLE
                            updateOverlayLabel()
                            saveVisualCaptureStatus(
                                "One visual frame captured in memory from ${sourcePackage ?: "the active app"}. No image file was saved and no upload was made. Return to ERA to review or clear it."
                            )
                            Toast.makeText(this@EraAccessibilityService, "Visual frame ready in ERA (memory only)", Toast.LENGTH_LONG).show()
                        }

                        override fun onFailure(errorCode: Int) {
                            val reason = when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Accessibility screenshot access is unavailable."
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Android is enforcing the screenshot rate limit. Wait before trying again."
                                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The requested display is unavailable."
                                ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "The active app window is unavailable."
                                else -> "Android could not capture this screen (error $errorCode)."
                            }
                            finishVisualCaptureWithError(reason)
                        }
                    }
                )
            }.onFailure { error ->
                finishVisualCaptureWithError("Visual capture failed: ${error.message ?: "Android error"}.")
            }
        }, SCREENSHOT_OVERLAY_HIDE_DELAY_MS)
    }

    private fun fingerprintSnapshot(snapshot: UiScreenSnapshot): String? = runCatching {
        val encoded = JSONObject(UiTreeJson.encode(snapshot))
        UiSnapshotFingerprint.of(
            packageName = snapshot.packageName,
            canonicalRootJson = encoded.getJSONObject("root").toString(),
            nodeCount = snapshot.nodeCount,
            truncated = snapshot.truncated
        )
    }.getOrNull()

    /** A visual click is allowed only for the exact, still-current screenshot and accessible tree. */
    private fun dispatchGroundedVisualTap(proposal: VisualTargetProposal): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "Visual gesture dispatch requires Android 7 or newer."
        if (!isScreenInspectionConsented() || isDeviceLocked()) return "Screen consent is missing or the device is locked."
        if (!proposal.confidence.isFinite() || proposal.confidence < VisualTargetResolver.MIN_CONFIDENCE) {
            return "Visual target confidence is too low. Nothing was tapped."
        }
        if (SensitiveInputPolicy.isSensitiveField(label = proposal.label, role = proposal.role)) {
            return "Sensitive target blocked. Nothing was tapped."
        }
        if (isConsequentialControl(proposal.label + " " + proposal.role + " " + proposal.actionHint)) {
            return "Consequential control detected. Complete this action manually after review."
        }
        val frame = VisualFrameStore.current() ?: return "The screenshot frame has expired. Capture it again."
        if (frame.frameId != proposal.frameId) return "The screenshot changed after analysis. Capture and analyze again."
        val root = rootInActiveWindow ?: return "No active target app. Nothing was tapped."
        val packageName = root.packageName?.toString() ?: return "The active app identity is unavailable."
        if (frame.sourcePackage != packageName || proposal.sourcePackage != packageName) {
            return "The active app differs from the analyzed screenshot. Nothing was tapped."
        }
        val snapshot = buildSnapshot(root) ?: return "Could not verify the current screen. Nothing was tapped."
        val currentFingerprint = fingerprintSnapshot(snapshot)
        val display = windowManager?.defaultDisplay ?: return "Display information is unavailable."
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val frameIdentity = VisualFrameIdentity(
            frameId = frame.frameId,
            sourcePackage = frame.sourcePackage ?: return "Screenshot app identity is missing.",
            capturedAtEpochMs = frame.capturedAtEpochMs,
            frameWidth = frame.bitmap.width,
            frameHeight = frame.bitmap.height,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            displayRotation = display.rotation,
            screenFingerprint = frame.screenFingerprint
        )
        val resolution = VisualTargetResolver.resolve(
            VisualTargetResolveInput(
                frame = frameIdentity,
                proposal = proposal,
                currentPackage = packageName,
                currentDisplayWidth = metrics.widthPixels,
                currentDisplayHeight = metrics.heightPixels,
                currentDisplayRotation = display.rotation,
                nowEpochMs = System.currentTimeMillis(),
                explicitlyApproved = true,
                currentUiFingerprint = currentFingerprint
            )
        )
        val target = (resolution as? VisualTargetResolution.Accepted)?.target
            ?: return "Visual target rejected as stale, changed or ambiguous. Capture again."
        val path = Path().apply {
            moveTo(target.centerXOnDisplay.toFloat(), target.centerYOnDisplay.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Toast.makeText(this@EraAccessibilityService, "Approved visual tap dispatched. Rechecking screen…", Toast.LENGTH_LONG).show()
                handler.postDelayed({ captureCurrentWindow(showToast = false) }, POST_ACTION_VERIFY_DELAY_MS)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Toast.makeText(this@EraAccessibilityService, "Android cancelled the visual tap. No retry was attempted.", Toast.LENGTH_LONG).show()
            }
        }, handler)
        return if (accepted) "Android accepted one tap on '" + target.label + "'. The screen will be re-observed; ERA will not retry automatically."
        else "Android rejected the gesture. Nothing further was attempted."
    }

    private fun finishVisualCaptureWithError(message: String) {
        visualCapturePending = false
        inspectButton?.visibility = View.VISIBLE
        updateOverlayLabel()
        saveVisualCaptureStatus(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun saveVisualCaptureStatus(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_VISUAL_STATUS, message)
            .apply()
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

        if (semanticAction == SemanticUiAction.TYPE_TEXT) {
            val sensitiveReason = SensitiveInputPolicy.rejectionReason(target, pending.payload)
            if (sensitiveReason != null) {
                storeSnapshot(snapshot)
                pendingActionStore.clearPending(sensitiveReason)
                updateOverlayLabel()
                Toast.makeText(this, sensitiveReason, Toast.LENGTH_LONG).show()
                return
            }
        }

        val consequentialTarget = isConsequentialControl(
            target.text.orEmpty() + " " + target.contentDescription.orEmpty() + " " +
                target.hintText.orEmpty() + " " + target.resourceId.orEmpty() + " " + target.role
        )
        val risk = when {
            consequentialTarget -> TaskRisk.CRITICAL
            semanticAction == SemanticUiAction.CLICK || semanticAction == SemanticUiAction.TYPE_TEXT -> TaskRisk.HIGH
            else -> TaskRisk.LOW
        }
        val reversible = semanticAction == SemanticUiAction.SCROLL_FORWARD ||
            semanticAction == SemanticUiAction.SCROLL_BACKWARD
        val approvalAgeMs = System.currentTimeMillis() - pending.createdAtEpochMs
        val exactApprovalFresh = approvalAgeMs in 0..MAX_PENDING_APPROVAL_AGE_MS &&
            pending.expectedPackage.equals(snapshot.packageName, ignoreCase = true) &&
            pending.reviewedObservationId == freshObservationId &&
            pending.action == when (semanticAction) {
                SemanticUiAction.CLICK -> PendingUiActionStore.ACTION_CLICK
                SemanticUiAction.TYPE_TEXT -> PendingUiActionStore.ACTION_TYPE_TEXT
                SemanticUiAction.SCROLL_FORWARD -> PendingUiActionStore.ACTION_SCROLL_FORWARD
                SemanticUiAction.SCROLL_BACKWARD -> PendingUiActionStore.ACTION_SCROLL_BACKWARD
                else -> ""
            }
        val decision = AutonomyExecutionPolicy.decide(
            AutonomyGateInput(
                risk = risk,
                reversible = reversible,
                requiresUserApproval = true,
                exactApprovalGranted = true,
                approvalMatchesAction = exactApprovalFresh,
                approvalFresh = exactApprovalFresh,
                observationRequired = true,
                observationFresh = freshObservationId == pending.reviewedObservationId,
                permissionGranted = isScreenInspectionConsented() && !isDeviceLocked(),
                toolAvailable = rootInActiveWindow != null,
                consequentialApprovalGranted = risk != TaskRisk.CRITICAL || pendingActionStore.hasConsequenceApproval(pending),
                allowConsequentialDispatch = true
            )
        )
        when (decision) {
            AutonomyGateDecision.ALLOW_REVERSIBLE_ACTION,
            AutonomyGateDecision.ALLOW_APPROVED_ACTION -> Unit
            AutonomyGateDecision.USER_HANDOFF_REQUIRED -> {
                storeSnapshot(snapshot)
                pendingActionStore.clearPending("This action is consequential. ERA will not dispatch it; complete it manually in the target app.")
                updateOverlayLabel()
                Toast.makeText(this, "Consequential action left for manual completion", Toast.LENGTH_LONG).show()
                return
            }
            AutonomyGateDecision.WAIT_FOR_EXACT_USER_APPROVAL -> {
                if (risk == TaskRisk.CRITICAL && exactApprovalFresh && !pendingActionStore.hasConsequenceApproval(pending)) {
                    pendingActionStore.setResult("Waiting for your final voice confirmation in ERA. Say OK only after reviewing the action summary.")
                    storeSnapshot(snapshot)
                    updateOverlayLabel()
                    Toast.makeText(this, "Waiting for your OK in ERA", Toast.LENGTH_LONG).show()
                } else {
                    pendingActionStore.clearPending("A fresh exact approval is required. Review the current screen and approve again.")
                    storeSnapshot(snapshot)
                    updateOverlayLabel()
                }
                return
            }
            AutonomyGateDecision.BLOCK_INVALID_APPROVAL,
            AutonomyGateDecision.BLOCK_STALE_OBSERVATION -> {
                pendingActionStore.clearPending("Approval or screen observation is stale or does not match. Review and approve again.")
                storeSnapshot(snapshot)
                updateOverlayLabel()
                return
            }
            AutonomyGateDecision.BLOCK_MISSING_PERMISSION -> {
                pendingActionStore.clearPending("Screen-inspection consent is not active. Action was not run.")
                updateOverlayLabel()
                return
            }
            AutonomyGateDecision.BLOCK_TOOL_UNAVAILABLE -> {
                pendingActionStore.clearPending("Accessibility action service is unavailable. Action was not run.")
                updateOverlayLabel()
                return
            }
        }

        // Consequential controls require a separate, exact, fresh voice confirmation.
        if (risk == TaskRisk.CRITICAL && !pendingActionStore.consumeConsequenceApproval(pending)) {
            pendingActionStore.clearPending("Final confirmation was missing or expired. Nothing was run.")
            updateOverlayLabel()
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
        if (pending.beforeSnapshot.packageName != snapshot.packageName) {
            pendingActionStore.setResult("The active app changed after the action; ERA could not verify the result.")
            return
        }
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

    private fun isConsequentialControl(value: String): Boolean =
        com.eraassistant.mobile.screen.ConsequentialActionPolicy.classifyLabel(value) != null

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
        val nodePackage = node.packageName?.toString()
        val resourceId = node.viewIdResourceName?.takeIf(String::isNotBlank)
        val chromeAddressField = ExternalChromeAgentPolicy.isSupportedPackage(nodePackage) &&
            ExternalChromeAgentPolicy.isAddressBarResourceId(resourceId)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        // Editable values are not exported. Chrome's address bar and URL query/fragment
        // data are also omitted so snapshots never carry navigation tokens.
        val rawText = node.text?.toString()
        val safeText = if (ExternalChromeAgentPolicy.shouldOmitNodeText(
                nodePackage, node.isEditable, password, resourceId, rawText
            )) null else ExternalChromeAgentPolicy.sanitizeVisibleText(nodePackage, rawText)
            ?.take(MAX_TEXT_LENGTH)
        val safeDescription = if (password || (ExternalChromeAgentPolicy.isSupportedPackage(nodePackage) && node.isEditable) || chromeAddressField) null
            else ExternalChromeAgentPolicy.sanitizeVisibleText(nodePackage, node.contentDescription?.toString())
                ?.take(MAX_TEXT_LENGTH)
        val safeHint = if (password || chromeAddressField) null
            else ExternalChromeAgentPolicy.sanitizeVisibleText(nodePackage, node.hintText?.toString())
                ?.take(MAX_TEXT_LENGTH)
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
            resourceId = resourceId,
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

    companion object {
        @Volatile private var activeInstance: EraAccessibilityService? = null

        fun dispatchGroundedVisualTap(proposal: VisualTargetProposal): String {
            val service = activeInstance ?: return "ERA Accessibility service is not running."
            if (Looper.myLooper() != Looper.getMainLooper()) return "Visual tap must be requested on the Android main thread."
            return service.dispatchGroundedVisualTap(proposal)
        }

        const val PREFS = "era_ui_snapshot"
        const val KEY_JSON = "latest_json"
        const val KEY_CAPTURED_AT = "captured_at"
        const val KEY_PACKAGE = "package_name"
        const val KEY_ERROR = "capture_error"
        const val KEY_DIFF = "latest_diff"
        const val KEY_VISUAL_STATUS = "visual_capture_status"
        const val KEY_AUTO_OBSERVE = "auto_observe"
        const val KEY_ACCESSIBILITY_CONSENT = "accessibility_consent_accepted"
        const val KEY_EXTERNAL_CHROME_TARGET_MODE = "external_chrome_target_mode"
        const val MAX_NODES = 800
        const val MAX_DEPTH = 30
        const val MAX_TEXT_LENGTH = 300
        const val AUTO_CAPTURE_DEBOUNCE_MS = 450L
        const val MAX_PENDING_APPROVAL_AGE_MS = 120_000L
        const val SCREENSHOT_OVERLAY_HIDE_DELAY_MS = 180L
        const val POST_ACTION_VERIFY_DELAY_MS = 700L
    }
}
