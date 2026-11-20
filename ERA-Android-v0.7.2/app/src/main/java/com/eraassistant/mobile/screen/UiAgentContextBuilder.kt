package com.eraassistant.mobile.screen

/** Builds a compact, manual-review prompt envelope. No network request is made here. */
object UiAgentContextBuilder {
    fun build(userGoal: String, snapshotJson: String): String {
        val cleanGoal = userGoal.trim().take(2_000)
        val compactSnapshot = UiTreeJson.compactFromEncoded(
            snapshotJson = snapshotJson,
            maxElements = 120,
            maxLabelChars = 100
        )
        return buildString {
            appendLine("You are planning a user-directed Android UI task for ERA.")
            appendLine("USER GOAL: $cleanGoal")
            appendLine("RULES:")
            appendLine("1. Use resourceId, label, type and state as semantic selectors.")
            appendLine("2. Never guess or use pixel X/Y coordinates.")
            appendLine("3. ref is snapshot-local and is not a durable selector; request a fresh snapshot after navigation or UI changes.")
            appendLine("4. If a selector matches multiple elements, ask the user to disambiguate.")
            appendLine("5. Editable field values are intentionally omitted. Do not infer their contents.")
            appendLine("6. Do not target password/secure fields or infer hidden content.")
            appendLine("7. Return one proposed action at a time with a selector, action, rationale and expected result.")
            appendLine("8. Do not send, publish, purchase, delete, submit or place calls; stop for explicit user review.")
            appendLine("9. A proposal is not authorization. ERA requires a separate user confirmation.")
            appendLine("10. Prefer this structured UI data. Do not request a screenshot unless visual understanding is genuinely required; ask the user before visual capture.")
            appendLine("COMPACT STRUCTURED UI SNAPSHOT (JSON):")
            append(compactSnapshot)
        }
    }
}
