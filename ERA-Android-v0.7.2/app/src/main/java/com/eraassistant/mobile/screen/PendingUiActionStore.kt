package com.eraassistant.mobile.screen

import android.content.Context

/** One-shot user-approved action request. It expires quickly and is consumed once. */
class PendingUiActionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun approve(
        selectorText: String,
        action: String,
        payload: String = "",
        expectedPackage: String? = null,
        resourceId: String = "",
        reviewedObservationId: String
    ): Boolean {
        // A new approval attempt supersedes any older one, even when this attempt is invalid.
        clearApprovalPayload(result = null)
        val safeSelector = selectorText.trim().take(MAX_SELECTOR_LENGTH)
        val safeResourceId = resourceId.trim().take(MAX_SELECTOR_LENGTH)
        if (safeSelector.isBlank() && safeResourceId.isBlank()) return false
        if (reviewedObservationId.isBlank() || reviewedObservationId.length != 64 || !reviewedObservationId.matches(Regex("[0-9a-f]{64}"))) return false
        if (action !in ALLOWED_ACTIONS) return false
        if (action == ACTION_TYPE_TEXT && payload.isBlank()) return false
        prefs.edit()
            .putString(KEY_SELECTOR, safeSelector)
            .putString(KEY_RESOURCE_ID, safeResourceId)
            .putString(KEY_ACTION, action)
            .putString(KEY_PAYLOAD, payload.take(MAX_PAYLOAD_LENGTH))
            .putString(KEY_PACKAGE, expectedPackage)
            .putString(KEY_OBSERVATION_ID, reviewedObservationId)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_RESULT, "")
            .apply()
        return true
    }

    fun pending(): PendingUiAction? {
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)
        if (System.currentTimeMillis() - createdAt !in 0..MAX_AGE_MS) {
            clearPending("Approved action expired. Approve it again from ERA.")
            return null
        }
        val selector = prefs.getString(KEY_SELECTOR, null) ?: run {
            clearPending("Approved action data is incomplete. Approve again.")
            return null
        }
        val action = prefs.getString(KEY_ACTION, null) ?: run {
            clearPending("Approved action data is incomplete. Approve again.")
            return null
        }
        val observationId = prefs.getString(KEY_OBSERVATION_ID, null)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: run {
            clearPending("Approval is not bound to a valid screen snapshot. Approve again.")
            return null
        }
        return PendingUiAction(
            selectorText = selector,
            resourceId = prefs.getString(KEY_RESOURCE_ID, "").orEmpty(),
            action = action,
            payload = prefs.getString(KEY_PAYLOAD, "").orEmpty(),
            expectedPackage = prefs.getString(KEY_PACKAGE, null),
            reviewedObservationId = observationId,
            createdAtEpochMs = createdAt
        )
    }

    /** Remove the approved payload before dispatch so a consumed action cannot be replayed. */
    fun consume() { clearApprovalPayload(result = null) }

    fun clearPending(result: String) { clearApprovalPayload(result) }

    private fun clearApprovalPayload(result: String?) {
        val editor = prefs.edit()
            .putBoolean(KEY_PENDING, false)
            .remove(KEY_SELECTOR)
            .remove(KEY_RESOURCE_ID)
            .remove(KEY_ACTION)
            .remove(KEY_PAYLOAD)
            .remove(KEY_PACKAGE)
            .remove(KEY_OBSERVATION_ID)
            .remove(KEY_CREATED_AT)
        if (result != null) editor.putString(KEY_RESULT, result)
        editor.apply()
    }
    fun setResult(result: String) { prefs.edit().putString(KEY_RESULT, result).apply() }
    fun result(): String = prefs.getString(KEY_RESULT, "").orEmpty()
    fun isPending(): Boolean = prefs.getBoolean(KEY_PENDING, false)

    data class PendingUiAction(
        val selectorText: String,
        val resourceId: String,
        val action: String,
        val payload: String,
        val expectedPackage: String?,
        val reviewedObservationId: String,
        val createdAtEpochMs: Long
    )

    companion object {
        const val PREFS = "era_pending_ui_action"
        const val ACTION_CLICK = "click"
        const val ACTION_TYPE_TEXT = "type_text"
        const val ACTION_SCROLL_FORWARD = "scroll_forward"
        const val ACTION_SCROLL_BACKWARD = "scroll_backward"
        private const val KEY_SELECTOR = "selector_text"
        private const val KEY_RESOURCE_ID = "resource_id"
        private const val KEY_ACTION = "action"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_PACKAGE = "expected_package"
        private const val KEY_OBSERVATION_ID = "reviewed_observation_id"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_PENDING = "pending"
        private const val KEY_RESULT = "result"
        private const val MAX_SELECTOR_LENGTH = 160
        private const val MAX_PAYLOAD_LENGTH = 1000
        private const val MAX_AGE_MS = 120_000L
        private val ALLOWED_ACTIONS = setOf(ACTION_CLICK, ACTION_TYPE_TEXT, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)
    }
}
