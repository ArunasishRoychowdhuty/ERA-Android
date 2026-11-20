package com.eraassistant.mobile.screen

/** Builds a manual-review prompt envelope. No network request is made here. */
object UiAgentContextBuilder {
    fun build(userGoal: String, snapshotJson: String): String {
        val cleanGoal = userGoal.trim().take(2_000)
        val safeSnapshot = snapshotJson.take(500_000)
        return buildString {
            appendLine("You are planning a user-directed Android UI task for ERA.")
            appendLine("USER GOAL: $cleanGoal")
            appendLine("RULES:")
            appendLine("1. Use resourceId, text, contentDescription, role and state as semantic selectors.")
            appendLine("2. Never guess or use pixel X/Y coordinates. Bounds are diagnostic only.")
            appendLine("3. nodeKey is valid only for this captured snapshot; request a fresh capture after navigation or UI changes.")
            appendLine("4. If a selector matches multiple elements, ask the user to disambiguate.")
            appendLine("5. Do not target password/secure fields or infer hidden content.")
            appendLine("6. Return one proposed action at a time with a selector, action, rationale and expected result.")
            appendLine("7. Do not send, publish, purchase, delete, submit or place calls; stop for explicit user review.")
            appendLine("8. The proposal is not authorization. ERA requires a separate user confirmation.")
            appendLine("STRUCTURED UI SNAPSHOT (JSON):")
            append(safeSnapshot)
        }
    }
}
