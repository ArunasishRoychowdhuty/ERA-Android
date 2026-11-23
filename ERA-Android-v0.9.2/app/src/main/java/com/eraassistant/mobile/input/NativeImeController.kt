package com.eraassistant.mobile.input

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager

/**
 * Requests the currently selected Android system IME for an already focused, user-visible
 * editor. This does not read keyboard contents, install a keyboard, or inject credentials.
 */
data class NativeImeResult(
    val requestAccepted: Boolean,
    val visibleAtCheck: Boolean,
    val message: String
)

class NativeImeController(context: Context) {
    private val appContext = context.applicationContext

    fun showFor(view: View): NativeImeResult {
        if (!view.isAttachedToWindow) {
            return NativeImeResult(false, false, "The editor is not attached to a visible window.")
        }
        view.requestFocus()
        if (!view.hasFocus()) {
            return NativeImeResult(false, isVisible(view), "The editor could not obtain focus.")
        }
        val manager = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return NativeImeResult(false, isVisible(view), "Android's input-method service is unavailable.")
        val accepted = runCatching {
            manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }.getOrDefault(false)
        val visible = isVisible(view)
        return NativeImeResult(
            requestAccepted = accepted,
            visibleAtCheck = visible,
            message = when {
                visible -> "The Android system keyboard is visible."
                accepted -> "Android accepted the keyboard request; visibility has not yet been confirmed."
                else -> "Android did not accept the keyboard request. The user can tap the field to open it."
            }
        )
    }

    fun hideFrom(view: View): Boolean {
        if (!view.isAttachedToWindow) return false
        val manager = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        return runCatching { manager.hideSoftInputFromWindow(view.windowToken, 0) }.getOrDefault(false)
    }

    fun isVisible(view: View): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                .getOrDefault(false)
}
