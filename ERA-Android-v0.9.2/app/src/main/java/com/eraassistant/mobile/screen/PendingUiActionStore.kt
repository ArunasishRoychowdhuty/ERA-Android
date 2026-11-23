package com.eraassistant.mobile.screen

import android.content.Context
import com.eraassistant.mobile.productivity.SecurePayloadCipher
import java.security.MessageDigest

/** One-shot user-approved action request. It expires quickly and is consumed once. */
class PendingUiActionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = SecurePayloadCipher()

    fun approve(
        selectorText: String,
        action: String,
        payload: String = "",
        expectedPackage: String? = null,
        resourceId: String = "",
        reviewedObservationId: String
    ): Boolean {
        clearApprovalPayload(result = null)
        val safeSelector = selectorText.trim().take(MAX_SELECTOR_LENGTH)
        val safeResourceId = resourceId.trim().take(MAX_SELECTOR_LENGTH)
        if (safeSelector.isBlank() && safeResourceId.isBlank()) return false
        if (reviewedObservationId.isBlank() || reviewedObservationId.length != 64 || !reviewedObservationId.matches(Regex("[0-9a-f]{64}"))) return false
        if (action !in ALLOWED_ACTIONS) return false
        if (action == ACTION_TYPE_TEXT) {
            if (payload.isBlank()) return false
            val sensitiveReason = SensitiveInputPolicy.rejectionReason(
                label = safeSelector,
                resourceId = safeResourceId,
                hint = null,
                role = null,
                payload = payload
            )
            if (sensitiveReason != null) {
                setResult(sensitiveReason)
                return false
            }
        }
        val encryptedPayload = runCatching { cipher.encrypt(payload.take(MAX_PAYLOAD_LENGTH)) }.getOrNull()
            ?: return false
        prefs.edit()
            .putString(KEY_SELECTOR, safeSelector)
            .putString(KEY_RESOURCE_ID, safeResourceId)
            .putString(KEY_ACTION, action)
            .putString(KEY_PAYLOAD, encryptedPayload)
            .putString(KEY_PACKAGE, expectedPackage)
            .putString(KEY_OBSERVATION_ID, reviewedObservationId)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_CONSEQUENCE_APPROVED, false)
            .remove(KEY_CONSEQUENCE_BINDING)
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
        val encryptedPayload = prefs.getString(KEY_PAYLOAD, null) ?: run {
            clearPending("Approved action payload is missing. Nothing was run.")
            return null
        }
        val payload = runCatching { cipher.decrypt(encryptedPayload) }.getOrNull() ?: run {
            clearPending("Approved action payload could not be decrypted. Nothing was run.")
            return null
        }
        return PendingUiAction(
            selectorText = selector,
            resourceId = prefs.getString(KEY_RESOURCE_ID, "").orEmpty(),
            action = action,
            payload = payload,
            expectedPackage = prefs.getString(KEY_PACKAGE, null),
            reviewedObservationId = observationId,
            createdAtEpochMs = createdAt
        )
    }

    /**
     * A second, process-local voice approval is bound to the same package, target,
     * action and reviewed observation. It cannot authorize a different screen or replay.
     */
    fun armConsequenceApproval(binding: String): Boolean {
        val pending = pending() ?: return false
        if (binding != consequenceBinding(pending)) return false
        prefs.edit()
            .putString(KEY_CONSEQUENCE_BINDING, hash(binding))
            .putBoolean(KEY_CONSEQUENCE_APPROVED, false)
            .apply()
        return true
    }

    fun confirmConsequenceApproval(binding: String): Boolean {
        val pending = pending() ?: return false
        val expected = prefs.getString(KEY_CONSEQUENCE_BINDING, null)
        if (expected == null || expected != hash(binding) || binding != consequenceBinding(pending)) return false
        prefs.edit().putBoolean(KEY_CONSEQUENCE_APPROVED, true).apply()
        return true
    }

    fun hasConsequenceApproval(pending: PendingUiAction): Boolean {
        val binding = consequenceBinding(pending)
        return pendingStillValid(pending) &&
            prefs.getBoolean(KEY_CONSEQUENCE_APPROVED, false) &&
            prefs.getString(KEY_CONSEQUENCE_BINDING, null) == hash(binding)
    }

    fun consumeConsequenceApproval(pending: PendingUiAction): Boolean {
        val approved = hasConsequenceApproval(pending)
        prefs.edit()
            .remove(KEY_CONSEQUENCE_BINDING)
            .putBoolean(KEY_CONSEQUENCE_APPROVED, false)
            .apply()
        return approved
    }

    /** Called at process start: approvals never survive application process death. */
    fun revokeConsequenceApproval() {
        prefs.edit()
            .remove(KEY_CONSEQUENCE_BINDING)
            .putBoolean(KEY_CONSEQUENCE_APPROVED, false)
            .apply()
    }

    fun consequenceBinding(pending: PendingUiAction): String = listOf(
        pending.expectedPackage.orEmpty(),
        pending.selectorText,
        pending.resourceId,
        pending.action,
        pending.reviewedObservationId
    ).joinToString("\u001f")

    private fun pendingStillValid(pending: PendingUiAction): Boolean {
        val current = pending()
        return current != null &&
            current.selectorText == pending.selectorText &&
            current.resourceId == pending.resourceId &&
            current.action == pending.action &&
            current.expectedPackage == pending.expectedPackage &&
            current.reviewedObservationId == pending.reviewedObservationId &&
            current.createdAtEpochMs == pending.createdAtEpochMs
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
            .remove(KEY_CONSEQUENCE_BINDING)
            .putBoolean(KEY_CONSEQUENCE_APPROVED, false)
        if (result != null) editor.putString(KEY_RESULT, result)
        editor.apply()
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun setResult(result: String) { prefs.edit().putString(KEY_RESULT, result.take(500)).apply() }
    fun result(): String = prefs.getString(KEY_RESULT, "").orEmpty()
    fun isPending(): Boolean = prefs.getBoolean(KEY_PENDING, false)
    fun isAwaitingConsequenceApproval(): Boolean = isPending() && !prefs.getBoolean(KEY_CONSEQUENCE_APPROVED, false) &&
        prefs.contains(KEY_CONSEQUENCE_BINDING)

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
        private const val KEY_CONSEQUENCE_BINDING = "consequence_binding"
        const val KEY_CONSEQUENCE_APPROVED = "consequence_approved"
        private const val MAX_SELECTOR_LENGTH = 160
        private const val MAX_PAYLOAD_LENGTH = 1000
        private const val MAX_AGE_MS = 120_000L
        private val ALLOWED_ACTIONS = setOf(ACTION_CLICK, ACTION_TYPE_TEXT, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)
    }
}
