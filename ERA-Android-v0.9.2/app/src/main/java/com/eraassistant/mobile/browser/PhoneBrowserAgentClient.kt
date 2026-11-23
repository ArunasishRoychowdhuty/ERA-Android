package com.eraassistant.mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.autofill.AutofillManager
import com.eraassistant.mobile.input.NativeImeController
import com.eraassistant.mobile.input.NativeImeResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhoneBrowserActionResult(
    val status: String,
    val action: String,
    val ref: String,
    val note: String,
    val followUpSnapshot: PhoneBrowserSnapshot? = null,
    val privateDetails: String = "",
    val verified: Boolean = false
)

/**
 * ERA-owned multi-tab WebView browser and semantic automation runtime.
 * It controls only WebViews hosted by ERA, never the separate Chrome application.
 */
class PhoneBrowserAgentClient(
    private val context: Context,
    private val onBrowserState: (String, String, String, Boolean, String) -> Unit =
        { _, _, _, _, _ -> }
) {
    private data class BrowserTab(
        val targetId: String,
        val webView: WebView,
        var loading: Boolean = false
    )

    private val tabs = mutableListOf<BrowserTab>()
    private val latestSnapshots = mutableMapOf<String, PhoneBrowserSnapshot>()
    private var nextTabNumber = 1
    private var activeTargetId = ""
    private var destroyed = false
    private var renderProcessRecoveryInProgress = false

    val webView: WebView
        get() = currentWebView()

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Create the ERA browser on the Android main thread."
        }
        val first = createTabInternal()
        activeTargetId = first.targetId
    }

    fun currentWebView(): WebView {
        checkAlive()
        return tabFor(activeTargetId)?.webView
            ?: throw IllegalStateException("The active ERA browser tab is unavailable.")
    }

    fun activeTargetId(): String = activeTargetId

    /**
     * Invokes Android's native Autofill service for the active WebView.
     * ERA never reads, stores or forwards the credential values returned by the service.
     */
    suspend fun requestSystemAutofill(targetId: String = activeTargetId): String = withContext(Dispatchers.Main) {
        checkAlive()
        val manager = context.getSystemService(AutofillManager::class.java)
            ?: return@withContext "Android Autofill service is unavailable on this device."
        if (!manager.isEnabled) {
            return@withContext "Enable an Autofill/password-manager service in Android Settings, then try again."
        }
        val view = requireTab(targetId).webView
        manager.requestAutofill(view)
        "Android Autofill request sent to the selected ERA browser tab. Choose a credential in the system UI; ERA does not receive its value."
    }

    /**
     * Focuses one fresh, ordinary editable DOM field and asks Android to display the
     * currently selected system keyboard. ERA never receives keystrokes entered by the user.
     */
    suspend fun focusAndShowSystemKeyboard(targetId: String, ref: String): NativeImeResult =
        withContext(Dispatchers.Main) {
            checkAlive()
            val tab = requireTab(targetId)
            val snapshot = latestSnapshots[targetId]
                ?: throw IllegalStateException("Capture a fresh semantic snapshot first.")
            require(snapshot.targetId == targetId && snapshot.nodes.any { it.ref == ref }) {
                "The semantic ref is not in the latest ERA browser snapshot."
            }
            val node = snapshot.nodes.first { it.ref == ref }
            val rejection = PhoneBrowserAgentPolicy.textEntryRejection(node, "")
            if (rejection != null) throw SecurityException(rejection)
            require(PhoneBrowserAgentPolicy.canFill(node)) {
                "The selected target is not a visible, ordinary editable field."
            }

            val focusResult = evaluateJson(
                tab.webView,
                actionScript(
                    targetId = targetId,
                    snapshotId = snapshot.snapshotId,
                    ref = ref,
                    action = "focus",
                    text = "",
                    confirmed = false,
                    includePrivateDetails = false
                )
            )
            require(focusResult.optString("status") == "FOCUS_READY") {
                "The webpage did not confirm focus on the selected field."
            }
            tab.webView.requestFocus()
            NativeImeController(tab.webView.context).showFor(tab.webView)
        }

    fun newTab(input: String = ""): String {
        checkMainThread()
        checkAlive()
        require(tabs.size < MAX_TABS) { "ERA supports up to $MAX_TABS in-app tabs at once." }
        val tab = createTabInternal()
        activeTargetId = tab.targetId
        if (input.isNotBlank()) tab.webView.loadUrl(normalizeAddress(input))
        publishSessionState(tab, "New ERA browser tab")
        return tab.targetId
    }

    fun selectSession(targetId: String) {
        checkMainThread()
        checkAlive()
        require(tabFor(targetId) != null) { "The selected ERA browser tab is unavailable." }
        activeTargetId = targetId
        val tab = requireNotNull(tabFor(targetId))
        publishSessionState(tab, "Selected ERA browser tab")
    }

    fun closeSession(targetId: String): Boolean {
        checkMainThread()
        checkAlive()
        val tab = tabFor(targetId) ?: return false
        latestSnapshots.remove(targetId)
        if (tabs.size == 1) {
            tab.webView.stopLoading()
            tab.webView.loadUrl("about:blank")
            tab.loading = false
            if (activeTargetId == targetId) publishSessionState(tab, "Last browser tab cleared")
            return true
        }
        tabs.remove(tab)
        tab.webView.stopLoading()
        tab.webView.removeAllViews()
        tab.webView.destroy()
        if (activeTargetId == targetId) activeTargetId = tabs.last().targetId
        publishSessionState(requireNotNull(tabFor(activeTargetId)), "Browser tab closed")
        return true
    }

    fun sessionsNow(includePrivateDetails: Boolean = false, confirmed: Boolean = false): List<PhoneBrowserSession> {
        checkMainThread()
        checkAlive()
        require(!includePrivateDetails || confirmed) {
            "Full page URL details require one-time confirmation."
        }
        return tabs.mapIndexed { index, tab ->
            val snapshot = latestSnapshots[tab.targetId]
            PhoneBrowserSession(
                tab = index,
                targetId = tab.targetId,
                url = if (includePrivateDetails) tab.webView.url.orEmpty() else sanitizeUrl(tab.webView.url.orEmpty()),
                title = sanitizeText(tab.webView.title.orEmpty(), 160),
                frameCount = snapshot?.frameCount ?: 1,
                active = tab.targetId == activeTargetId,
                loading = tab.loading
            )
        }
    }

    suspend fun testConnection(): String {
        checkAlive()
        return withContext(Dispatchers.Main) {
            "READY · ERA built-in WebView · ${tabs.size}/$MAX_TABS tabs"
        }
    }

    suspend fun navigate(input: String): String {
        val url = normalizeAddress(input)
        withContext(Dispatchers.Main) {
            checkAlive()
            val tab = requireNotNull(tabFor(activeTargetId))
            latestSnapshots.remove(tab.targetId)
            tab.webView.loadUrl(url)
        }
        return url
    }

    suspend fun goBack(targetId: String = activeTargetId): Boolean = withContext(Dispatchers.Main) {
        val view = requireTab(targetId).webView
        if (!view.canGoBack()) return@withContext false
        latestSnapshots.remove(targetId)
        view.goBack()
        true
    }

    suspend fun goForward(targetId: String = activeTargetId): Boolean = withContext(Dispatchers.Main) {
        val view = requireTab(targetId).webView
        if (!view.canGoForward()) return@withContext false
        latestSnapshots.remove(targetId)
        view.goForward()
        true
    }

    suspend fun reload(targetId: String = activeTargetId) = withContext(Dispatchers.Main) {
        latestSnapshots.remove(targetId)
        requireTab(targetId).webView.reload()
    }

    suspend fun stop(targetId: String = activeTargetId) {
        withContext(Dispatchers.Main) {
            val tab = requireTab(targetId)
            tab.webView.stopLoading()
            tab.loading = false
        }
    }

    suspend fun listSessions(
        includePrivateDetails: Boolean = false,
        confirmed: Boolean = false
    ): List<PhoneBrowserSession> = withContext(Dispatchers.Main) {
        sessionsNow(includePrivateDetails, confirmed)
    }

    suspend fun captureSnapshot(
        targetId: String,
        includePrivateDetails: Boolean = false,
        confirmed: Boolean = false
    ): PhoneBrowserSnapshot {
        require(!includePrivateDetails || confirmed) {
            "Private DOM values and attributes require one-time confirmation."
        }
        val tab = withContext(Dispatchers.Main) { requireTab(targetId) }
        val root = evaluateJson(tab.webView, snapshotScript(includePrivateDetails, targetId))
        val snapshot = parseSnapshot(root, includePrivateDetails)
        require(snapshot.targetId == targetId) { "The browser returned a snapshot for a different tab." }
        latestSnapshots[targetId] = snapshot
        return snapshot
    }

    suspend fun performAction(
        targetId: String,
        ref: String,
        action: String,
        text: String = "",
        confirmed: Boolean = false,
        includePrivateDetails: Boolean = false
    ): PhoneBrowserActionResult {
        val normalized = action.lowercase()
        require(normalized in SUPPORTED_ACTIONS) { "Unsupported browser action." }
        val tab = withContext(Dispatchers.Main) { requireTab(targetId) }
        val snapshot = latestSnapshots[targetId]
            ?: throw IllegalStateException("Capture a fresh semantic snapshot first.")
        require(snapshot.targetId == targetId && snapshot.nodes.any { it.ref == ref }) {
            "The semantic ref is not in the latest ERA browser snapshot."
        }
        val node = snapshot.nodes.first { it.ref == ref }
        if (normalized in setOf("fill", "type")) {
            val rejection = PhoneBrowserAgentPolicy.textEntryRejection(node, text)
            if (rejection != null) throw SecurityException(rejection)
        }
        val permitted = when (normalized) {
            "read" -> PhoneBrowserAgentPolicy.canRead(node)
            "click" -> PhoneBrowserAgentPolicy.canClick(node)
            "fill", "type" -> PhoneBrowserAgentPolicy.canFill(node)
            "press" -> PhoneBrowserAgentPolicy.canPress(node)
            "check", "uncheck" -> PhoneBrowserAgentPolicy.canCheck(node)
            "select" -> PhoneBrowserAgentPolicy.canSelect(node)
            "scroll", "scroll_forward", "scroll_backward" -> node.visible
            else -> false
        }
        require(permitted) { "The selected element is no longer visible, enabled or suitable for this action." }
        if (PhoneBrowserAgentPolicy.requiresConfirmation(node, normalized) && !confirmed) {
            throw IllegalStateException("This selected browser action needs a one-time user confirmation.")
        }
        if (normalized == "read" && includePrivateDetails && !confirmed) {
            throw IllegalStateException("Private element details require one-time user confirmation.")
        }
        when (normalized) {
            "fill", "type" -> require(text.isNotEmpty() && text.length <= 4_000) {
                "Text must contain 1–4000 characters."
            }
            "press" -> require(text.uppercase() in ALLOWED_KEYS) {
                "Supported keys: ENTER, TAB, ESCAPE, BACKSPACE, DELETE, SPACE and arrow keys."
            }
            "select" -> require(text.isNotBlank() && text.length <= 240) {
                "Enter the option value or label to select."
            }
        }

        val result = evaluateJson(
            tab.webView,
            actionScript(
                targetId = targetId,
                snapshotId = snapshot.snapshotId,
                ref = ref,
                action = normalized,
                text = text,
                confirmed = confirmed,
                includePrivateDetails = includePrivateDetails
            )
        )
        if (normalized == "press" && result.optString("status") == "PRESS_READY") {
            dispatchKey(tab.webView, text)
        }
        if (normalized in setOf("click", "fill", "type", "press", "check", "uncheck", "select", "scroll", "scroll_forward", "scroll_backward")) {
            delay(250)
        }
        val status = result.optString("status", "ACTION_COMPLETED")
        val verified = result.optBoolean("verified", false)
        val note = result.optString(
            "note",
            if (normalized == "read") "Element details read from the current page."
            else "Action dispatched. Capture a fresh snapshot to inspect the resulting page state."
        )
        val privateDetails = result.optJSONObject("privateDetails")?.toString(2).orEmpty()
        val followUp = if (normalized != "read") {
            runCatching { captureSnapshot(targetId, false) }.getOrNull()
        } else null
        return PhoneBrowserActionResult(
            status = status,
            action = normalized,
            ref = ref,
            note = note,
            followUpSnapshot = followUp,
            privateDetails = privateDetails,
            verified = verified
        )
    }

    fun onPause() {
        if (!destroyed) tabs.forEach { it.webView.onPause() }
    }

    fun onResume() {
        if (!destroyed) tabs.forEach { it.webView.onResume() }
    }

    fun goBack() {
        if (destroyed) return
        val tab = tabFor(activeTargetId) ?: return
        tab.webView.post {
            if (!destroyed && tab.webView.canGoBack()) {
                latestSnapshots.remove(tab.targetId)
                tab.webView.goBack()
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        val cleanup = {
            tabs.toList().forEach { tab ->
                runCatching {
                    tab.webView.stopLoading()
                    tab.webView.loadUrl("about:blank")
                    tab.webView.removeAllViews()
                    tab.webView.destroy()
                }
            }
            tabs.clear()
            latestSnapshots.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
        else tabs.firstOrNull()?.webView?.post { cleanup() }
    }

    private fun createTabInternal(): BrowserTab {
        val targetId = "era-webview-${nextTabNumber++}"
        val view = WebView(context)
        val tab = BrowserTab(targetId, view)
        tabs += tab
        configureWebView(tab)
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(tab: BrowserTab) {
        val view = tab.webView
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.isSaveEnabled = true
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "https" -> false
                    "http" -> {
                        webView.loadUrl(uri.buildUpon().scheme("https").build().toString())
                        true
                    }
                    else -> true
                }
            }

            override fun onPageStarted(webView: WebView, url: String, favicon: Bitmap?) {
                tab.loading = true
                latestSnapshots.remove(tab.targetId)
                notifyState(tab, "Page loading")
            }

            override fun onPageFinished(webView: WebView, url: String) {
                tab.loading = false
                notifyState(tab, "Page ready")
            }

            override fun onReceivedError(
                webView: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    tab.loading = false
                    notifyState(tab, "Page load failed: " + error.description.toString().take(180))
                }
            }

            override fun onRenderProcessGone(webView: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (!renderProcessRecoveryInProgress && !destroyed) {
                    renderProcessRecoveryInProgress = true
                    tabs.toList().forEach { broken ->
                        latestSnapshots.remove(broken.targetId)
                        runCatching {
                            broken.webView.stopLoading()
                            broken.webView.removeAllViews()
                            broken.webView.destroy()
                        }
                    }
                    tabs.clear()
                    val replacement = createTabInternal()
                    activeTargetId = replacement.targetId
                    renderProcessRecoveryInProgress = false
                    onBrowserState(
                        replacement.targetId, "", "", false,
                        "WebView renderer stopped; ERA created a fresh browser tab. Reopen the page."
                    )
                }
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(webView: WebView, title: String?) {
                notifyState(tab, "Browser title updated")
            }

            override fun onProgressChanged(webView: WebView, newProgress: Int) {
                tab.loading = newProgress < 100
                if (newProgress >= 100) notifyState(tab, "Page ready")
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.deny()
            }

            override fun onCreateWindow(
                webView: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean = false
        }
    }

    private fun notifyState(tab: BrowserTab, message: String) {
        onBrowserState(tab.targetId, tab.webView.title.orEmpty(), tab.webView.url.orEmpty(), tab.loading, message)
    }

    private fun publishSessionState(tab: BrowserTab, message: String) {
        onBrowserState(tab.targetId, tab.webView.title.orEmpty(), tab.webView.url.orEmpty(), tab.loading, message)
    }

    private fun tabFor(targetId: String): BrowserTab? = tabs.firstOrNull { it.targetId == targetId }

    private fun requireTab(targetId: String): BrowserTab {
        checkAlive()
        return tabFor(targetId) ?: throw IllegalStateException("The selected ERA browser tab is unavailable.")
    }

    private fun checkAlive() {
        check(!destroyed) { "The ERA in-app browser has been closed." }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Browser tab changes must run on the Android main thread."
        }
    }

    private suspend fun evaluateJson(view: WebView, script: String): JSONObject = withContext(Dispatchers.Main) {
        checkAlive()
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { encoded ->
                if (!continuation.isActive) return@evaluateJavascript
                try {
                    val decoded = JSONTokener(encoded ?: "null").nextValue() as? String
                        ?: throw IllegalStateException("The browser returned an empty JavaScript result.")
                    continuation.resume(JSONObject(decoded))
                } catch (error: Exception) {
                    continuation.resumeWithException(
                        IllegalStateException("Could not parse the in-app browser result: " +
                            (error.message ?: "invalid response"))
                    )
                }
            }
        }
    }

    private suspend fun dispatchKey(view: WebView, keyName: String) = withContext(Dispatchers.Main) {
        val keyCode = KEY_CODES[keyName.uppercase()]
            ?: throw IllegalArgumentException("Unsupported key.")
        val now = SystemClock.uptimeMillis()
        view.requestFocus()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        view.dispatchKeyEvent(KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun normalizeAddress(input: String): String {
        val value = input.trim()
        require(value.isNotBlank() && value.length <= 2_048) { "Enter a web address or search phrase." }
        val candidate = when {
            value.startsWith("https://", true) -> value
            value.startsWith("http://", true) -> "https://" + value.substring(7)
            value.contains(' ') || !value.contains('.') ->
                "https://www.google.com/search?q=" + URLEncoder.encode(value, Charsets.UTF_8.name())
            else -> "https://" + value
        }
        val uri = Uri.parse(candidate)
        require(uri.scheme == "https") { "Only HTTPS webpages can be opened. HTTP addresses are upgraded to HTTPS." }
        require(!uri.host.isNullOrBlank()) { "The web address is invalid." }
        require(uri.userInfo.isNullOrBlank()) { "URLs containing embedded usernames or passwords are not accepted." }
        return candidate
    }

    private fun snapshotScript(includePrivate: Boolean, targetId: String): String {
        val privateFlag = includePrivate.toString()
        return """
        (() => {
          'use strict';
          const includePrivate = __PRIVATE__;
          const targetId = __TARGET__;
          const MAX_NODES = 400;
          const MAX_DEPTH = 10;
          const snapshotId = 'sera' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
          const refs = Object.create(null);
          const nodes = [];
          const refByElement = new WeakMap();
          let frameCount = 0;
          let truncated = false;
          const norm = (value, limit = 180) => String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, limit);
          const safeText = value => norm(value)
            .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[EMAIL]')
            .replace(/(?<!\d)\+?\d[\d ()-]{6,}\d(?!\d)/g, '[PHONE]')
            .replace(/\b(?:AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{12,})\b/g, '[REDACTED]')
            .replace(/\bBearer\s+\S+/gi, 'Bearer [REDACTED]');
          const safeUrl = value => {
            try { const u = new URL(value); return u.origin + u.pathname; }
            catch { return ''; }
          };
          const visible = el => {
            try {
              if (el.hidden || el.closest('[hidden],[inert],[aria-hidden="true"]')) return false;
              const s = el.ownerDocument.defaultView.getComputedStyle(el);
              return s.display !== 'none' && s.visibility !== 'hidden' &&
                s.visibility !== 'collapse' && Number(s.opacity || 1) !== 0 &&
                el.getClientRects().length > 0;
            } catch { return false; }
          };
          const roleOf = el => {
            const explicit = (el.getAttribute('role') || '').trim().split(/\s+/)[0];
            if (explicit) return explicit;
            const tag = el.localName;
            const type = (el.getAttribute('type') || '').toLowerCase();
            if (el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('contenteditable') === '') return 'textbox';
            if (tag === 'a' && el.hasAttribute('href')) return 'link';
            if (tag === 'button' || tag === 'summary') return 'button';
            if (tag === 'textarea') return 'textbox';
            if (tag === 'select') return el.multiple ? 'listbox' : 'combobox';
            if (tag === 'input') {
              if (['submit','button','reset','image'].includes(type)) return 'button';
              if (type === 'checkbox') return 'checkbox';
              if (type === 'radio') return 'radio';
              if (type === 'range') return 'slider';
              if (type === 'number') return 'spinbutton';
              if (type === 'search') return 'searchbox';
              if (type === 'hidden') return 'none';
              return 'textbox';
            }
            if (/^h[1-6]$/.test(tag)) return 'heading';
            if (tag === 'nav') return 'navigation';
            if (tag === 'main') return 'main';
            if (tag === 'form') return 'form';
            if (tag === 'dialog') return 'dialog';
            if (tag === 'ul' || tag === 'ol') return 'list';
            if (tag === 'li') return 'listitem';
            if (tag === 'img') return 'img';
            if (tag === 'iframe') return 'iframe';
            if (tag === 'label') return 'label';
            if (tag === 'p') return 'paragraph';
            if (tag === 'header') return 'banner';
            if (tag === 'footer') return 'contentinfo';
            if (tag === 'aside') return 'complementary';
            if (tag === 'article') return 'article';
            if (tag === 'table') return 'table';
            if (tag === 'tr') return 'row';
            if (tag === 'th') return el.scope === 'row' ? 'rowheader' : 'columnheader';
            if (tag === 'td') return 'cell';
            if (tag === 'progress') return 'progressbar';
            if (tag === 'meter') return 'meter';
            if (tag === 'output') return 'status';
            return 'generic';
          };
          const referencedText = (el, attr) => {
            const ids = norm(el.getAttribute(attr) || '').split(/\s+/).filter(Boolean);
            return ids.map(id => el.ownerDocument.getElementById(id))
              .filter(Boolean).map(x => norm(x.innerText || x.textContent)).filter(Boolean).join(' ');
          };
          const nameOf = el => {
            const aria = el.getAttribute('aria-label');
            if (aria) return norm(aria);
            const labelled = referencedText(el, 'aria-labelledby');
            if (labelled) return norm(labelled);
            if (el.labels && el.labels.length) {
              const label = Array.from(el.labels).map(x => norm(x.innerText || x.textContent)).filter(Boolean).join(' ');
              if (label) return norm(label);
            }
            const title = el.getAttribute('title');
            if (title) return norm(title);
            if (el.localName === 'img') return norm(el.alt || '');
            if (el.localName === 'input') {
              const type = (el.getAttribute('type') || '').toLowerCase();
              if (['submit','button','reset'].includes(type)) return norm(el.value);
            }
            const text = norm(el.innerText || el.textContent);
            if (text) return text;
            return norm(el.getAttribute('placeholder') || '');
          };
          const descriptionOf = el => norm(
            el.getAttribute('aria-description') || referencedText(el, 'aria-describedby')
          );
          const fingerprint = el => ({
            tag: el.localName, id: el.id || '', name: el.getAttribute('name') || '',
            role: el.getAttribute('role') || '', type: el.getAttribute('type') || '',
            placeholder: el.getAttribute('placeholder') || '', href: el.getAttribute('href') || '',
            autocomplete: el.getAttribute('autocomplete') || '', nameText: nameOf(el)
          });
          const interactive = new Set(['button','link','checkbox','radio','switch','tab','menuitem','option','combobox','listbox','textbox','searchbox','spinbutton','slider']);
          const structural = new Set(['main','navigation','banner','contentinfo','complementary','article','form','dialog','list','listitem','heading','paragraph','img','iframe','table','row','cell','columnheader','rowheader','status','alert','progressbar','meter']);
          const visit = (root, doc, frameUrl, depth, inheritedParentRef) => {
            if (depth > MAX_DEPTH || nodes.length >= MAX_NODES) { truncated = true; return; }
            let elements;
            try { elements = Array.from(root.querySelectorAll('*')); } catch { return; }
            for (const el of elements) {
              if (nodes.length >= MAX_NODES) { truncated = true; break; }
              if (!visible(el)) continue;
              const tag = el.localName, type = (el.getAttribute('type') || '').toLowerCase();
              const role = roleOf(el);
              const contentEditable = Boolean(el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('contenteditable') === '');
              const editable = tag === 'textarea' ||
                (tag === 'input' && !['hidden','button','submit','reset','checkbox','radio','file','image'].includes(type)) ||
                contentEditable || ['textbox','searchbox','spinbutton'].includes(role);
              const clickable = interactive.has(role) || ['a','button','summary'].includes(tag) ||
                (tag === 'input' && ['button','submit','reset','checkbox','radio'].includes(type)) ||
                el.hasAttribute('onclick') || el.hasAttribute('aria-haspopup');
              const focusable = el.tabIndex >= 0 || clickable || editable;
              const name = nameOf(el);
              const semantic = clickable || editable || structural.has(role) || Boolean(el.shadowRoot) ||
                (name && el.children.length === 0 && !['script','style','noscript'].includes(tag));
              if (semantic && !['none','presentation'].includes(role)) {
                let parentRef = inheritedParentRef;
                let ancestor = el.parentElement;
                while (ancestor) {
                  const ancestorRef = refByElement.get(ancestor);
                  if (ancestorRef) { parentRef = ancestorRef; break; }
                  ancestor = ancestor.parentElement;
                }
                const ref = '@' + snapshotId + ':e' + (nodes.length + 1);
                const fp = fingerprint(el);
                const autocomplete = el.getAttribute('autocomplete') || '';
                const sensitive = type === 'password' ||
                  /one-time-code|current-password|new-password|cc-number|cc-csc/i.test(autocomplete) ||
                  /password|passcode|otp|one.?time|verification.?code|security.?code|api.?key|api.?token|access.?token|secret|pin|cvv|cvc|card.?number/i.test(
                    [name, fp.id, fp.name, fp.placeholder, autocomplete].join(' ')
                  );
                const consequential = type === 'submit' ||
                  /send|publish|submit|delete|remove|purchase|checkout|place order|buy now|pay|payment|transfer|withdraw|sign in|log in|apply|confirm|authorize|subscribe|unsubscribe|cancel|পাঠাও|পাঠান|প্রকাশ|জমা|মুছ|পেমেন্ট|কেনা|অর্ডার/i.test(name);
                const expandedRaw = el.getAttribute('aria-expanded');
                const checkedRaw = el.getAttribute('aria-checked');
                const selectedRaw = el.getAttribute('aria-selected');
                const item = {
                  ref, tag, role,
                  name: includePrivate ? name : safeText(sensitive ? 'Sensitive field' : name),
                  description: includePrivate ? descriptionOf(el) : safeText(descriptionOf(el)),
                  clickable: Boolean(clickable), editable: Boolean(editable),
                  focusable: Boolean(focusable), sensitive, consequential, visible: true,
                  parentRef, depth: parentRef ? Math.min(depth + 1, MAX_DEPTH) : 0,
                  customDropdown: ['combobox','listbox'].includes(role) || el.hasAttribute('aria-haspopup'),
                  state: {
                    disabled: Boolean(el.disabled || el.getAttribute('aria-disabled') === 'true'),
                    checked: ('checked' in el) ? Boolean(el.checked) : checkedRaw === 'true' ? true : checkedRaw === 'false' ? false : null,
                    selected: ('selected' in el) ? Boolean(el.selected) : selectedRaw === 'true' ? true : selectedRaw === 'false' ? false : null,
                    expanded: expandedRaw === 'true' ? true : expandedRaw === 'false' ? false : null,
                    required: Boolean(el.required || el.getAttribute('aria-required') === 'true'),
                    readOnly: Boolean(el.readOnly || el.getAttribute('aria-readonly') === 'true'),
                    invalid: el.getAttribute('aria-invalid') === 'true',
                    focused: doc.activeElement === el,
                    level: /^h[1-6]$/.test(tag) ? Number(tag[1]) : Number(el.getAttribute('aria-level') || 0) || null
                  },
                  frameUrl: includePrivate ? frameUrl : safeUrl(frameUrl)
                };
                if (includePrivate) {
                  item.value = ('value' in el) ? norm(el.value, 800) :
                    (contentEditable ? norm(el.innerText || el.textContent, 800) : null);
                  item.attributes = {
                    id: fp.id, name: fp.name, role: fp.role, type: fp.type,
                    placeholder: fp.placeholder, href: el.href || '', autocomplete
                  };
                }
                refs[ref] = { element: el, document: doc, frameUrl, fingerprint: fp, snapshotId };
                refByElement.set(el, ref);
                nodes.push(item);
              }
              const childParentRef = refByElement.get(el) || inheritedParentRef;
              if (el.shadowRoot) visit(el.shadowRoot, doc, frameUrl, depth + 1, childParentRef);
              if (tag === 'iframe' || tag === 'frame') {
                try {
                  const childDoc = el.contentDocument;
                  if (childDoc && childDoc.documentElement) {
                    frameCount++;
                    visit(childDoc, childDoc, childDoc.location.href, depth + 1, childParentRef);
                  }
                } catch { /* Cross-origin frame content is browser-isolated. */ }
              }
            }
          };
          visit(document, document, location.href, 0, '');
          window.__ERA_BROWSER_AGENT__ = { snapshotId, targetId, url: location.href, refs };
          return JSON.stringify({
            targetId, snapshotId,
            url: includePrivate ? location.href : safeUrl(location.href),
            title: safeText(document.title, 160),
            nodeCount: nodes.length, complete: !truncated, truncated,
            frameCount: frameCount + 1,
            accessibility: {
              totalNodes: nodes.length, complete: !truncated,
              frames: [{ status: 'DOM_ACCESSIBLE_SEMANTIC', nodeCount: nodes.length }]
            },
            tree: nodes
          });
        })()
        """.trimIndent()
            .replace("__PRIVATE__", privateFlag)
            .replace("__TARGET__", JSONObject.quote(targetId))
    }

    private fun actionScript(
        targetId: String,
        snapshotId: String,
        ref: String,
        action: String,
        text: String,
        confirmed: Boolean,
        includePrivateDetails: Boolean
    ): String = """
        (() => {
          'use strict';
          const agent = window.__ERA_BROWSER_AGENT__;
          const ref = __REF__;
          const action = __ACTION__;
          const confirmed = __CONFIRMED__;
          const includePrivate = __PRIVATE__;
          const text = __TEXT__;
          const targetId = __TARGET__;
          const snapshotId = __SNAPSHOT__;
          if (!agent || agent.targetId !== targetId || agent.snapshotId !== snapshotId ||
              !agent.refs || !agent.refs[ref]) {
            throw new Error('STALE_REF: capture a fresh snapshot for the selected tab.');
          }
          if (agent.url !== location.href) throw new Error('STALE_REF: the page URL changed. Capture a fresh snapshot.');
          const entry = agent.refs[ref], el = entry.element, doc = entry.document;
          if (!el || !el.isConnected || !doc || doc.location.href !== entry.frameUrl) {
            throw new Error('STALE_REF: the page or frame changed. Capture a fresh snapshot.');
          }
          const norm = value => String(value ?? '').replace(/\s+/g, ' ').trim();
          const currentName = () => {
            let value = '';
            const aria = el.getAttribute('aria-label');
            const ids = norm(el.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean);
            if (aria) value = aria;
            else if (ids.length) {
              const labelled = ids.map(id => doc.getElementById(id)).filter(Boolean)
                .map(x => norm(x.innerText || x.textContent)).join(' ');
              if (labelled) value = labelled;
            }
            if (!value && el.labels && el.labels.length) {
              value = Array.from(el.labels).map(x => x.innerText || x.textContent).join(' ');
            }
            if (!value && el.getAttribute('title')) value = el.getAttribute('title');
            if (!value && el.localName === 'img') value = el.alt || '';
            if (!value && el.localName === 'input' &&
                ['submit','button','reset'].includes((el.getAttribute('type') || '').toLowerCase())) value = el.value;
            if (!value) value = el.innerText || el.textContent || el.getAttribute('placeholder') || '';
            return norm(value).slice(0, 180);
          };
          const current = {
            tag: el.localName, id: el.id || '', name: el.getAttribute('name') || '',
            role: el.getAttribute('role') || '', type: el.getAttribute('type') || '',
            placeholder: el.getAttribute('placeholder') || '', href: el.getAttribute('href') || '',
            autocomplete: el.getAttribute('autocomplete') || '', nameText: currentName()
          };
          for (const key of ['tag','id','name','role','type','placeholder','href','autocomplete','nameText']) {
            if (entry.fingerprint[key] !== current[key]) throw new Error('STALE_REF: element identity changed (' + key + ').');
          }
          const currentStyle = doc.defaultView.getComputedStyle(el);
          const currentRect = el.getBoundingClientRect();
          const currentlyVisible = !el.hidden &&
            !el.closest('[hidden],[inert],[aria-hidden="true"]') &&
            currentStyle.display !== 'none' &&
            currentStyle.visibility !== 'hidden' &&
            currentStyle.visibility !== 'collapse' &&
            Number(currentStyle.opacity || 1) !== 0 &&
            currentRect.width > 0 && currentRect.height > 0 &&
            el.getClientRects().length > 0;
          if (!currentlyVisible) throw new Error('STALE_REF: the selected element is no longer visible.');
          const disabled = el.disabled || el.getAttribute('aria-disabled') === 'true';
          const readOnly = el.readOnly || el.getAttribute('aria-readonly') === 'true';
          const sensitive = current.type.toLowerCase() === 'password' ||
            /one-time-code|current-password|new-password|cc-number|cc-csc/i.test(current.autocomplete) ||
            /password|passcode|otp|one.?time|verification.?code|security.?code|api.?key|api.?token|access.?token|secret|pin|cvv|cvc|card.?number/i.test(
              [current.nameText,current.id,current.name,current.placeholder,current.autocomplete].join(' ')
            );
          const consequential = current.type.toLowerCase() === 'submit' ||
            /send|publish|submit|delete|remove|purchase|checkout|place order|buy now|pay|payment|transfer|withdraw|sign in|log in|apply|confirm|authorize|subscribe|unsubscribe|cancel|পাঠাও|পাঠান|প্রকাশ|জমা|মুছ|পেমেন্ট|কেনা|অর্ডার/i.test(current.nameText);
          const privateRead = action === 'read' && (sensitive || el.isContentEditable ||
            ['input','textarea','select'].includes(el.localName) || includePrivate);
          if (['fill','type','focus'].includes(action) && sensitive) {
            throw new Error('BLOCKED_SENSITIVE_FIELD: ERA cannot enter or focus credential, verification or payment fields. Use the website sign-in flow or Android Autofill.');
          }
          if (((action === 'click' || action === 'check' || action === 'uncheck' || action === 'select') && consequential ||
              action === 'press' || privateRead || includePrivate) && !confirmed) {
            throw new Error('This browser action requires one-time confirmation.');
          }
          if (action !== 'read' && action !== 'scroll' && disabled) throw new Error('Element is disabled.');
          if (action === 'focus') {
            const editable = el.isContentEditable || ['input','textarea'].includes(el.localName) ||
              ['textbox','searchbox','spinbutton','combobox'].includes(current.role);
            if (!editable) throw new Error('Selected element is not an editable field.');
            if (readOnly || sensitive) throw new Error('Sensitive or read-only fields cannot be focused by ERA.');
            el.focus();
            if (doc.activeElement !== el) throw new Error('The webpage did not focus the selected field.');
            return JSON.stringify({ status: 'FOCUS_READY', note: 'The selected ordinary field is focused. Android system IME may now be requested.' });
          }
          if (action === 'read') {
            const output = {
              status: 'READ_OK',
              note: 'Element identity, accessible name and visible state read from current page.'
            };
            if (includePrivate && confirmed) {
              output.privateDetails = {
                tag: current.tag, role: current.role, name: current.nameText,
                value: ('value' in el) ? el.value : (el.isContentEditable ? el.innerText : null),
                id: current.id, fieldName: current.name, type: current.type,
                placeholder: current.placeholder, href: el.href || '',
                checked: ('checked' in el) ? Boolean(el.checked) : null,
                selected: ('selected' in el) ? Boolean(el.selected) : null,
                disabled: Boolean(disabled), readOnly: Boolean(readOnly)
              };
            }
            return JSON.stringify(output);
          }
          if (action === 'click') {
            if (readOnly) throw new Error('Element is read-only.');
            const href = el.getAttribute('href') || '';
            if (/^(javascript|data|file|content|intent|tel|mailto):/i.test(href)) {
              throw new Error('Non-web navigation schemes are blocked.');
            }
            const rect = el.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) throw new Error('Element has no visible DOM box.');
            const hit = doc.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
            if (!hit || (hit !== el && !el.contains(hit) && !el.getRootNode().host?.contains(hit))) {
              throw new Error('CLICK_BLOCKED: another element covers the target center.');
            }
            el.click();
            return JSON.stringify({
              status: 'CLICK_DISPATCHED',
              note: 'One click event was dispatched after a visible hit-test. Check the next snapshot for the result.',
              verified: false
            });
          }
          if (action === 'fill' || action === 'type') {
            const editable = el.isContentEditable || ['input','textarea'].includes(el.localName) ||
              ['textbox','searchbox','spinbutton','combobox'].includes(current.role);
            if (!editable) throw new Error('Selected element is not an editable field.');
            if (readOnly) throw new Error('Field is read-only.');
            el.focus();
            const next = action === 'type'
              ? (('value' in el) ? String(el.value || '') : String(el.innerText || '')) + text
              : text;
            if (el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('contenteditable') === '') {
              el.textContent = next;
            } else if ('value' in el) {
              const proto = el.localName === 'textarea' ? HTMLTextAreaElement.prototype :
                el.localName === 'select' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
              const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
              if (setter) setter.call(el, next); else el.value = next;
            } else {
              throw new Error('Selected element is not editable.');
            }
            try {
              const InputCtor = doc.defaultView.InputEvent || doc.defaultView.Event;
              el.dispatchEvent(new InputCtor('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
            } catch { el.dispatchEvent(new doc.defaultView.Event('input', { bubbles: true, composed: true })); }
            el.dispatchEvent(new doc.defaultView.Event('change', { bubbles: true }));
            const actual = ('value' in el) ? String(el.value) : String(el.innerText || el.textContent || '');
            const verified = actual === next;
            if (!verified) throw new Error('STATE_UNVERIFIED: the field did not retain the requested value.');
            return JSON.stringify({
              status: action.toUpperCase() + '_VERIFIED', characters: text.length, verified: true,
              note: 'The current field value matched the requested text; the value itself was not returned.'
            });
          }
          if (action === 'press') {
            const allowed = ['ENTER','TAB','ESCAPE','BACKSPACE','DELETE','SPACE','ARROWUP','ARROWDOWN','ARROWLEFT','ARROWRIGHT'];
            if (!allowed.includes(String(text).toUpperCase())) throw new Error('Unsupported key.');
            el.focus();
            return JSON.stringify({ status: 'PRESS_READY', note: 'Focused the selected element for a native key event.' });
          }
          if (action === 'check' || action === 'uncheck') {
            if (!['checkbox','radio','switch'].includes(current.role) &&
                !(current.tag === 'input' && ['checkbox','radio'].includes(current.type.toLowerCase()))) {
              throw new Error('Selected element is not a checkbox, radio or switch.');
            }
            const wanted = action === 'check';
            if (current.type.toLowerCase() === 'radio' && !wanted) {
              throw new Error('A radio button cannot be unchecked directly.');
            }
            const readChecked = () => ('checked' in el)
              ? Boolean(el.checked)
              : el.getAttribute('aria-checked') === 'true';
            const before = readChecked();
            if (before !== wanted) el.click();
            const actual = readChecked();
            const verified = actual === wanted;
            return JSON.stringify({
              status: verified ? 'STATE_VERIFIED' : 'STATE_DISPATCHED',
              verified, checked: actual,
              note: verified ? 'The requested checked state is now visible.' :
                'The control received one click, but its state did not update synchronously. Inspect the follow-up snapshot.'
            });
          }
          if (action === 'select') {
            if (current.tag === 'select') {
              const options = Array.from(el.options || []);
              const option = options.find(o => o.value === text || norm(o.label || o.textContent).toLowerCase() === norm(text).toLowerCase());
              if (!option) throw new Error('Requested option was not found in this select element.');
              option.selected = true;
              el.dispatchEvent(new doc.defaultView.Event('input', { bubbles: true }));
              el.dispatchEvent(new doc.defaultView.Event('change', { bubbles: true }));
              const verified = Array.from(el.selectedOptions || []).includes(option);
              if (!verified) throw new Error('STATE_UNVERIFIED: requested option was not selected.');
              return JSON.stringify({ status: 'SELECTED_VERIFIED', verified: true, note: 'Native select option selected and verified.' });
            }
            const expanded = el.getAttribute('aria-expanded') === 'true';
            if (!expanded) el.click();
            const candidates = Array.from(doc.querySelectorAll('[role="option"],[role="menuitem"],li,[data-value]'));
            const query = norm(text).toLowerCase();
            const option = candidates.find(item => {
              const value = norm(item.getAttribute('data-value') || item.getAttribute('value') || '').toLowerCase();
              const label = norm(item.getAttribute('aria-label') || item.innerText || item.textContent).toLowerCase();
              return value === query || label === query || label.includes(query);
            });
            if (!option) throw new Error('Requested custom dropdown option was not found.');
            option.click();
            const state = option.getAttribute('aria-selected') === 'true' ||
              option.getAttribute('aria-checked') === 'true' ||
              /(^|\s)(selected|active|checked|is-selected|is-active)(\s|$)/i.test(option.className || '');
            const triggerText = norm(('value' in el) ? el.value : (el.innerText || el.textContent)).toLowerCase();
            const verified = state || triggerText.includes(query);
            return JSON.stringify({
              status: verified ? 'CUSTOM_OPTION_VERIFIED' : 'CUSTOM_OPTION_DISPATCHED',
              verified, note: verified ? 'Custom dropdown selection was observed.' : 'Option click was sent; verify with a fresh snapshot.'
            });
          }
          if (action === 'scroll') {
            el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
            return JSON.stringify({ status: 'SCROLLED', verified: true, note: 'Selected element was scrolled into view.' });
          }
          if (action === 'scroll_forward' || action === 'scroll_backward') {
            const sign = action === 'scroll_forward' ? 1 : -1;
            const canScroll = node => {
              if (!node || node === doc.documentElement || node === doc.body) return false;
              const style = doc.defaultView.getComputedStyle(node);
              const overflow = style.overflowY;
              return node.scrollHeight > node.clientHeight + 1 &&
                ['auto','scroll','overlay'].includes(overflow);
            };
            let container = el;
            while (container && !canScroll(container)) container = container.parentElement;
            const before = container
              ? container.scrollTop
              : (doc.defaultView.scrollY || doc.documentElement.scrollTop || 0);
            const distance = container
              ? Math.max(120, Math.floor(container.clientHeight * 0.8))
              : Math.max(120, Math.floor(doc.defaultView.innerHeight * 0.8));
            if (container) container.scrollBy({ top: sign * distance, behavior: 'instant' });
            else doc.defaultView.scrollBy({ top: sign * distance, behavior: 'instant' });
            const after = container
              ? container.scrollTop
              : (doc.defaultView.scrollY || doc.documentElement.scrollTop || 0);
            const changed = after !== before;
            return JSON.stringify({
              status: changed ? 'SCROLL_DISPATCHED' : 'SCROLL_BOUNDARY',
              verified: changed,
              direction: sign > 0 ? 'forward' : 'backward',
              note: changed
                ? 'The selected scroll container or page viewport moved in the requested direction.'
                : 'The selected scroll container or page viewport did not move; it may be at a boundary.'
            });
          }
          throw new Error('Unsupported browser action.');
        })()
        """
        .trimIndent()
        .replace("__REF__", JSONObject.quote(ref))
        .replace("__ACTION__", JSONObject.quote(action))
        .replace("__CONFIRMED__", confirmed.toString())
        .replace("__PRIVATE__", includePrivateDetails.toString())
        .replace("__TEXT__", JSONObject.quote(text))
        .replace("__TARGET__", JSONObject.quote(targetId))
        .replace("__SNAPSHOT__", JSONObject.quote(snapshotId))

    private fun parseSnapshot(root: JSONObject, includePrivateDetails: Boolean): PhoneBrowserSnapshot {
        val tree = root.optJSONArray("tree") ?: JSONArray()
        val nodes = (0 until tree.length()).mapNotNull { index ->
            val item = tree.optJSONObject(index) ?: return@mapNotNull null
            val state = item.optJSONObject("state") ?: JSONObject()
            val attrs = item.optJSONObject("attributes")
            PhoneBrowserNode(
                ref = item.optString("ref"),
                tag = item.optString("tag"),
                role = item.optString("role"),
                name = item.optString("name"),
                clickable = item.optBoolean("clickable"),
                editable = item.optBoolean("editable"),
                sensitive = item.optBoolean("sensitive"),
                consequential = item.optBoolean("consequential"),
                visible = item.optBoolean("visible"),
                disabled = state.optBoolean("disabled"),
                value = if (includePrivateDetails && !item.isNull("value")) item.optString("value") else null,
                attributes = if (includePrivateDetails && attrs != null) {
                    attrs.keys().asSequence().take(24).associateWith { attrs.optString(it).take(500) }
                } else emptyMap(),
                frameUrl = item.optString("frameUrl"),
                parentRef = item.optString("parentRef"),
                depth = item.optInt("depth", 0).coerceIn(0, 10),
                description = item.optString("description"),
                focusable = item.optBoolean("focusable"),
                checked = nullableBoolean(state, "checked"),
                selected = nullableBoolean(state, "selected"),
                expanded = nullableBoolean(state, "expanded"),
                required = state.optBoolean("required"),
                readOnly = state.optBoolean("readOnly"),
                invalid = state.optBoolean("invalid"),
                focused = state.optBoolean("focused"),
                level = if (state.isNull("level")) null else state.optInt("level"),
                customDropdown = item.optBoolean("customDropdown")
            )
        }
        val access = root.optJSONObject("accessibility") ?: JSONObject()
        val frames = access.optJSONArray("frames") ?: JSONArray()
        val summary = (0 until frames.length()).mapNotNull { index ->
            frames.optJSONObject(index)?.let {
                it.optString("status") + " · " + it.optInt("nodeCount") + " semantic nodes"
            }
        }.joinToString(" | ")
        return PhoneBrowserSnapshot(
            targetId = root.optString("targetId"),
            url = root.optString("url"),
            title = root.optString("title"),
            nodeCount = root.optInt("nodeCount", nodes.size),
            complete = root.optBoolean("complete", false),
            truncated = root.optBoolean("truncated", false),
            nodes = nodes,
            accessibilitySummary = summary,
            includesPrivateDetails = includePrivateDetails,
            snapshotId = root.optString("snapshotId"),
            frameCount = root.optInt("frameCount", 1)
        )
    }

    private fun nullableBoolean(json: JSONObject, key: String): Boolean? =
        if (!json.has(key) || json.isNull(key)) null else json.optBoolean(key)

    private fun sanitizeUrl(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return ""
        uri.buildUpon().clearQuery().fragment(null).encodedAuthority(uri.host).build().toString().take(240)
    }.getOrDefault("")

    private fun sanitizeText(value: String, max: Int): String = value
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[EMAIL]")
        .replace(Regex("(?<!\\d)\\+?\\d[\\d ()-]{6,}\\d(?!\\d)"), "[PHONE]")
        .replace(Regex("\\b(?:AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{12,})\\b"), "[REDACTED]")
        .replace(Regex("\\bBearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
        .take(max)

    private companion object {
        const val MAX_TABS = 5
        val SUPPORTED_ACTIONS = setOf("read", "click", "fill", "type", "press", "check", "uncheck", "select", "scroll", "scroll_forward", "scroll_backward")
        val ALLOWED_KEYS = setOf("ENTER", "TAB", "ESCAPE", "BACKSPACE", "DELETE", "SPACE", "ARROWUP", "ARROWDOWN", "ARROWLEFT", "ARROWRIGHT")
        val KEY_CODES = mapOf(
            "ENTER" to KeyEvent.KEYCODE_ENTER,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
            "BACKSPACE" to KeyEvent.KEYCODE_DEL,
            "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
            "SPACE" to KeyEvent.KEYCODE_SPACE,
            "ARROWUP" to KeyEvent.KEYCODE_DPAD_UP,
            "ARROWDOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "ARROWLEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "ARROWRIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }
}
