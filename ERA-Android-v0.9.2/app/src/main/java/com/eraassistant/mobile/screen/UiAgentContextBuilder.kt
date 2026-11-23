package com.eraassistant.mobile.screen

import org.json.JSONObject

/** Builds a compact, manual-review prompt envelope. No network request is made here. */
object UiAgentContextBuilder {
    fun build(
        userGoal: String,
        snapshotJson: String,
        explicitVisualRequest: Boolean = false
    ): String {
        val cleanGoal = userGoal.trim().take(2_000)
        val compactSnapshot = UiTreeJson.compactFromEncoded(
            snapshotJson = snapshotJson,
            maxElements = 120,
            maxLabelChars = 100
        )
        val compactObject = JSONObject(compactSnapshot)
        val elements = compactObject.optJSONArray("elements")
        val elementCount = elements?.length() ?: 0
        val semanticAvailable = elementCount > 0
        // A sparse or truncated tree is not treated as a sufficient visual substitute.
        val semanticSufficient = elementCount >= MIN_SUFFICIENT_ELEMENTS &&
            !compactObject.optBoolean("truncated", false)
        val observationDecision = UiVisualFallbackPolicy.decide(
            UiUnderstandingInput(
                explicitVisualRequest = explicitVisualRequest,
                semanticSnapshotAvailable = semanticAvailable,
                semanticSnapshotSufficient = semanticSufficient
            )
        )

        return buildString {
            appendLine("You are planning a user-directed Android UI task for ERA.")
            appendLine("USER GOAL: $cleanGoal")
            appendLine("OBSERVATION DECISION: $observationDecision")
            when (observationDecision) {
                UiUnderstandingDecision.USE_SEMANTIC_UI ->
                    appendLine("Use the semantic UI structure first; do not request a screenshot.")
                UiUnderstandingDecision.ASK_BEFORE_VISUAL_FALLBACK ->
                    appendLine("Semantic UI is missing, sparse or truncated. If visual understanding is necessary, ask the user before visual capture. The user must explicitly long-press the ERA overlay to capture one frame.")
                UiUnderstandingDecision.VISUAL_REQUESTED_BY_USER ->
                    appendLine("The user explicitly requested visual understanding. One explicit in-memory visual capture is permitted; do not capture again automatically.")
            }
            appendLine("RULES:")
            appendLine("1. Use resourceId, label, type and state as semantic selectors.")
            appendLine("2. Never invent or freehand pixel X/Y coordinates. For an explicitly captured screenshot, a vision model may propose a normalized bounding region (0..1) tied to the exact frame ID, source package, timestamp, display size and rotation; a local resolver must validate all of these before any target can be used.")
            appendLine("3. ref is snapshot-local and is not a durable selector; request a fresh snapshot after navigation or UI changes.")
            appendLine("4. If a selector matches multiple elements, ask the user to disambiguate.")
            appendLine("5. Editable field values are intentionally omitted. Do not infer their contents.")
            appendLine("6. Do not target password/secure fields or infer hidden content.")
            appendLine("7. Return one proposed action at a time with a selector, action, rationale and expected result.")
            appendLine("8. Do not send, publish, purchase, delete, submit or place calls; stop for explicit user review.")
            appendLine("9. A proposal is not authorization. ERA requires a separate user confirmation.")
            appendLine("10. Prefer structured UI data. Use visual capture only when genuinely necessary and only after the user explicitly requests it.")
            appendLine("11. Visual frames remain in process memory only; never write them to disk or upload them from this builder.")
            appendLine("12. A visual target is only a proposal. Reject stale frames, app/package mismatches, rotation or display-size changes, ambiguous regions and confidence below 0.90.")
            appendLine("13. Match a vision proposal against the Accessibility tree first. Use semantic ACTION_CLICK when an equivalent accessible node exists; use a visual gesture only as a separately implemented and explicitly approved fallback.")
            appendLine("14. Re-capture and independently verify the resulting screen after any visual action. Never repeat a gesture just because its result is uncertain.")
            appendLine("COMPACT STRUCTURED UI SNAPSHOT (JSON):")
            append(compactSnapshot)
        }
    }

    private const val MIN_SUFFICIENT_ELEMENTS = 2
}
