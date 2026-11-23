package com.eraassistant.mobile.workflows

import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep

data class PersonalWorkflowDefinition(val id: String, val title: String, val intentPhrases: Set<String>, val steps: List<TaskStep>, val acceptanceCriteria: List<String>)

object PersonalWorkflowCatalog {
    private val workflows = listOf(
        workflow("account-onboarding", "Account onboarding", setOf("create account", "open account", "sign up", "register account", "account registration", "নতুন অ্যাকাউন্ট"),
            listOf(
                stage("Identify official website", "Verify the official service domain before opening it.", TaskCapability.WEB_NAVIGATION),
                stage("Inspect registration fields", "Read visible labels and required fields; never inspect credential values.", TaskCapability.SCREEN_OBSERVATION),
                stage("Prepare approved profile data", "Map only user-approved non-secret profile values to ordinary fields.", TaskCapability.TEXT_ENTRY),
                stage("Authenticate with the service", "The user completes password, passkey, OTP and recovery steps using the service UI or Android Autofill.", TaskCapability.USER_CONFIRMATION, risk = TaskRisk.HIGH, approval = true),
                stage("Review account details", "Show the destination, public profile values and terms for review.", TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION),
                stage("Create account", "Final registration remains a manual user action after review.", TaskCapability.USER_CONFIRMATION, risk = TaskRisk.CRITICAL, approval = true)
            )),
        workflow("website-form", "Website form completion", setOf("fill form", "complete form", "submit form", "application form", "ফর্ম পূরণ", "ফর্ম সাবমিট"),
            listOf(
                stage("Open official form", "Open the user-specified official site and verify the domain.", TaskCapability.WEB_NAVIGATION),
                stage("Inspect form schema", "Collect visible labels, types and required-field indicators.", TaskCapability.SCREEN_OBSERVATION),
                stage("Map approved values", "Use only user-provided non-secret values; leave secure fields to the user.", TaskCapability.TEXT_ENTRY),
                stage("Review full form", "Present the completed fields, missing fields and destination before submission.", TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION),
                stage("Submit form", "Submission remains a manual user action after fresh review.", TaskCapability.USER_CONFIRMATION, risk = TaskRisk.CRITICAL, approval = true)
            )),
        workflow("professional-profile", "Professional profile optimization", setOf("improve profile", "update profile", "optimize profile", "professional profile", "প্রোফাইল আপডেট"),
            listOf(
                stage("Inspect current profile", "Review visible headline, summary, skills and portfolio sections.", TaskCapability.SCREEN_OBSERVATION),
                stage("Draft improvements", "Prepare evidence-based suggestions without inventing experience.", TaskCapability.TEXT_ENTRY),
                stage("Compare before and after", "Show exact proposed changes and identify unsupported claims.", TaskCapability.USER_CONFIRMATION),
                stage("Apply approved changes", "User reviews and manually applies final profile changes.", TaskCapability.USER_CONFIRMATION, risk = TaskRisk.HIGH, approval = true)
            )),
        workflow("lead-discovery", "Lead and opportunity discovery", setOf("find clients", "find leads", "find projects", "client discovery", "prospect search", "ক্লায়েন্ট খোঁজ"),
            listOf(
                stage("Search approved sources", "Search user-selected public marketplaces or professional directories.", TaskCapability.WEB_NAVIGATION),
                stage("Qualify opportunities", "Score fit using published requirements, skills and project evidence.", TaskCapability.SCREEN_OBSERVATION),
                stage("Prepare shortlist", "Show source URL, match rationale and uncertainty for each result.", TaskCapability.USER_CONFIRMATION),
                stage("Save selected opportunity", "Save only user-selected, non-secret business context in encrypted CRM.", TaskCapability.FILE_STORAGE)
            )),
        workflow("client-reply", "Client communication preparation", setOf("reply to client", "respond to client", "client message", "reply client", "ক্লায়েন্টকে রিপ্লাই"),
            listOf(
                stage("Inspect approved conversation", "Use only the conversation the user selected and is authorized to access.", TaskCapability.SCREEN_OBSERVATION),
                stage("Draft client response", "Prepare an accurate response grounded in the selected conversation and verified portfolio evidence.", TaskCapability.TEXT_ENTRY),
                stage("Review response", "Show the recipient context, tone and full draft for approval.", TaskCapability.USER_CONFIRMATION),
                stage("Send response", "Sending remains a manual final user action.", TaskCapability.USER_CONFIRMATION, risk = TaskRisk.CRITICAL, approval = true)
            ))
    )

    fun all(): List<PersonalWorkflowDefinition> = workflows
    fun match(goal: String): PersonalWorkflowDefinition? {
        val normalized = normalize(goal)
        val ranked = workflows.mapNotNull { definition ->
            val score = definition.intentPhrases.maxOfOrNull { phrase ->
                val p = normalize(phrase)
                when {
                    normalized == p -> 100
                    normalized.contains(p) -> 80 + p.length.coerceAtMost(20)
                    p.split(' ').filter { it.length > 2 }.count(normalized::contains) >= 2 -> 35
                    else -> 0
                }
            } ?: 0
            if (score > 0) definition to score else null
        }.sortedWith(compareByDescending<Pair<PersonalWorkflowDefinition, Int>> { it.second }.thenBy { it.first.id })
        if (ranked.isEmpty() || ranked.size > 1 && ranked[0].second - ranked[1].second <= 5) return null
        return ranked.first().first
    }

    fun createPlan(goal: String): TaskPlan? {
        val definition = match(goal) ?: return null
        return TaskPlan(
            goal = goal.trim().take(2_000),
            steps = definition.steps,
            note = definition.title + " workflow: staged plan only. Secure authentication and consequential final actions remain user-controlled.",
            acceptanceCriteria = definition.acceptanceCriteria,
            overallRisk = definition.steps.maxByOrNull { it.risk.ordinal }?.risk ?: TaskRisk.LOW,
            estimatedActions = definition.steps.size,
            planVersion = "era.personal-workflow.v1"
        ).takeIf { it.validate().isEmpty() }
    }

    private fun workflow(id: String, title: String, phrases: Set<String>, stages: List<TaskStep>) =
        PersonalWorkflowDefinition(id, title, phrases, stages.mapIndexed { index, step ->
            step.copy(id = index + 1, dependsOn = if (index == 0) emptyList() else listOf(index))
        }, listOf(
            "Every stage is reviewed and reaches its acceptance criteria.",
            "No credential or secret is entered or persisted by ERA.",
            "Consequential actions remain manual and user-approved."
        ))

    private fun stage(title: String, description: String, vararg capabilities: TaskCapability,
                      risk: TaskRisk = TaskRisk.LOW, approval: Boolean = false) = TaskStep(
        id = 1, title = title, description = description, requiredCapabilities = capabilities.toSet(),
        acceptanceCriteria = listOf("User can inspect the result of this stage."),
        retryLimit = 0, risk = risk, reversible = !approval, requiresUserApproval = approval
    )
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
