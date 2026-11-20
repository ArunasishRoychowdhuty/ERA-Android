package com.eraassistant.mobile.ai

/** Provider contract. API-key handling intentionally remains outside this v0.5 scope. */
data class PlannerRequest(val goal: String, val capabilities: List<String>, val uiContext: String? = null)
data class PlannerStep(val id: String, val description: String, val tool: String, val dependsOn: List<String> = emptyList(), val risk: Risk = Risk.LOW)
enum class Risk { LOW, REVIEW, CONSEQUENTIAL }
data class PlannerResponse(val steps: List<PlannerStep>, val successCriteria: List<String>)

interface AIPlannerProvider { suspend fun plan(request: PlannerRequest): PlannerResponse }

/** Gemini REST integration is intentionally a contract-only stub until key handling is separately designed. */
class GeminiPlannerStub : AIPlannerProvider {
    override suspend fun plan(request: PlannerRequest): PlannerResponse =
        throw NotImplementedError("Gemini provider transport not wired in v0.5 scaffold; API-key security scope excluded by user.")
}
