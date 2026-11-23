package com.eraassistant.mobile.planner

/** Capabilities that can be discovered locally without a cloud AI provider. */
enum class TaskCapability {
    APP_LAUNCH, DEVICE_CONTROL, DIALER_OPEN, MESSAGE_DRAFT,
    SCREEN_OBSERVATION, CONTACT_PICKER, STRUCTURED_UI_ACTION,
    TEXT_ENTRY, MEDIA_PICKER, SHARE_DRAFT, USER_CONFIRMATION,
    WEB_NAVIGATION, FILE_STORAGE
}

enum class TaskRisk { LOW, MEDIUM, HIGH, CRITICAL }

data class TaskStep(
    val id: Int,
    val title: String,
    val description: String,
    val dependsOn: List<Int> = emptyList(),
    val requiredCapabilities: Set<TaskCapability> = emptySet(),
    val acceptanceCriteria: List<String> = emptyList(),
    val retryLimit: Int = 1,
    val risk: TaskRisk = TaskRisk.LOW,
    val reversible: Boolean = true,
    val requiresUserApproval: Boolean = false
)

data class TaskPlan(
    val goal: String,
    val steps: List<TaskStep>,
    val note: String,
    val acceptanceCriteria: List<String> = emptyList(),
    val overallRisk: TaskRisk = TaskRisk.LOW,
    val estimatedActions: Int = steps.size,
    val planVersion: String = "era.plan.v2"
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val ids = steps.map { it.id }.toSet()
        if (goal.isBlank()) errors += "Plan goal must not be blank."
        if (goal.length > 2_000) errors += "Plan goal exceeds the 2000 character limit."
        if (planVersion.isBlank()) errors += "Plan version must not be blank."
        if (steps.isEmpty()) errors += "Plan must contain at least one step."
        if (ids.size != steps.size) errors += "Step IDs must be unique."
        steps.forEach { step ->
            if (step.id <= 0) errors += "Step IDs must be positive."
            if (step.title.isBlank()) errors += "Step ${step.id} title must not be blank."
            if (step.description.isBlank()) errors += "Step ${step.id} description must not be blank."
            if (step.dependsOn.distinct().size != step.dependsOn.size) errors += "Step ${step.id} has duplicate dependencies."
            if (step.dependsOn.any { it !in ids }) errors += "Step ${step.id} depends on an unknown step."
            if (step.dependsOn.contains(step.id)) errors += "Step ${step.id} cannot depend on itself."
            if (step.retryLimit !in 0..3) errors += "Step ${step.id} retryLimit must be between 0 and 3."
        }
        if (hasCycle()) errors += "Plan dependency graph contains a cycle."
        return errors
    }

    private fun hasCycle(): Boolean {
        val byId = steps.associateBy { it.id }
        val visiting = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()
        fun visit(id: Int): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            byId[id]?.dependsOn.orEmpty().forEach { dep -> if (visit(dep)) return true }
            visiting -= id
            visited += id
            return false
        }
        return steps.any { visit(it.id) }
    }
}

/**
 * Offline planning contract. This is deliberately not connected to Gemini/OpenAI or
 * any provider; the provider/security workstream was explicitly excluded.
 */
interface AIPlanner {
    suspend fun createPlan(userRequest: String): TaskPlan
}

/** Two-pass local planner: intent/constraints first, then capability-aware DAG construction. */
class PreviewPlanner(
    private val availableCapabilities: Set<TaskCapability> = TaskCapability.entries.toSet()
) : AIPlanner {
    override suspend fun createPlan(userRequest: String): TaskPlan {
        val request = userRequest.trim()
        val lower = request.lowercase()
        val requestsExplanationOnly = containsAny(
            lower, "do not launch", "don't launch", "do not open", "don't open",
            "only explain", "explain how", "শুধু বুঝিয়ে", "খুলবে না", "চালু করবে না"
        )
        val whatsappRequested = containsAny(lower, "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ") &&
            !isNegatedTarget(lower, "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ")
        val instagramRequested = containsAny(lower, "instagram", "ইনস্টাগ্রাম") &&
            !isNegatedTarget(lower, "instagram", "ইনস্টাগ্রাম")
        val requestsDraftOnly = containsAny(
            lower, "message", "মেসেজ", "বার্তা", "draft"
        ) && isNegatedAction(lower, "send", "sending", "পাঠাও", "পাঠানো")
        val steps = when {
            requestsExplanationOnly -> explanationPlan()
            requestsDraftOnly -> draftOnlyPlan()
            whatsappRequested -> whatsappPlan(includeSend = !isNegatedAction(lower, "send", "sending", "পাঠাও", "পাঠানো"))
            instagramRequested -> instagramPlan(includePublish = !isNegatedAction(lower, "publish", "share", "post", "প্রকাশ", "পোস্ট"))
            containsAny(lower, "facebook", "ফেসবুক", "post", "পোস্ট", "upload", "আপলোড") -> socialPostPlan()
            containsAny(lower, "gmail", "ইমেইল", "email", "mail") &&
                !isNegatedTarget(lower, "gmail", "email", "mail") -> emailPlan()
            containsAny(lower, "form", "ফর্ম", "application", "আবেদন") -> formPlan()
            containsAny(lower, "call", "কল", "phone", "ফোন") -> callPlan()
            containsAny(lower, "search", "খোঁজ", "সার্চ", "browser", "ওয়েবসাইট", "product", "পণ্য") -> browserPlan()
            else -> genericPlan()
        }.map { step ->
            val missing = step.requiredCapabilities - availableCapabilities
            if (missing.isEmpty()) step else step.copy(
                description = step.description + " Missing local capabilities: ${missing.joinToString()}.",
                retryLimit = 0
            )
        }
        val overallRisk = steps.maxByOrNull { it.risk.ordinal }?.risk ?: TaskRisk.LOW
        val plan = TaskPlan(
            goal = request,
            steps = steps,
            note = "Offline capability-aware plan. No external AI provider is connected. Each UI step requires a fresh screen observation; consequential actions remain user-confirmed.",
            acceptanceCriteria = listOf("Every step reaches its stated acceptance criteria.", "The final app/account/recipient matches the user's request.", "No consequential action runs without fresh user approval."),
            overallRisk = overallRisk,
            estimatedActions = steps.size
        )
        val errors = plan.validate()
        return if (errors.isEmpty()) plan else plan.copy(note = "Plan validation failed: ${errors.joinToString()}")
    }

    private fun whatsappPlan(includeSend: Boolean = true) = buildList {
        add(step(1, "Open WhatsApp", "Launch the installed WhatsApp package.", caps = setOf(TaskCapability.APP_LAUNCH), criteria = listOf("WhatsApp is foreground")))
        add(step(2, "Observe current UI", "Capture a fresh structured UI tree and identify the current page.", depends = listOf(1), caps = setOf(TaskCapability.SCREEN_OBSERVATION), criteria = listOf("Current WhatsApp page is identified")))
        add(step(3, "Resolve recipient", "Find the exact requested contact. Pause for user selection if multiple matches exist.", depends = listOf(2), caps = setOf(TaskCapability.STRUCTURED_UI_ACTION), criteria = listOf("Exactly one intended chat is open")))
        add(step(4, "Prepare message draft", "Enter only the exact user-provided message in the composer.", depends = listOf(3), caps = setOf(TaskCapability.TEXT_ENTRY), criteria = listOf("Draft text matches the request")))
        add(step(5, "Review recipient and draft", "Show recipient and message for user verification.", depends = listOf(4), caps = setOf(TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION), criteria = listOf("Recipient and draft are verified")))
        if (includeSend) {
            add(step(6, "Send message", "Sending is consequential and must be performed manually after explicit review.", depends = listOf(5), caps = setOf(TaskCapability.USER_CONFIRMATION), criteria = listOf("User manually confirms Send"), risk = TaskRisk.HIGH, reversible = false, approval = true))
        }
    }

    private fun instagramPlan(includePublish: Boolean = true) = buildList {
        add(step(1, "Open Instagram", "Launch Instagram and verify the intended account is active.", caps = setOf(TaskCapability.APP_LAUNCH), criteria = listOf("Instagram is foreground and the user confirms the account")))
        add(step(2, "Choose post media", "Ask the user to select the exact photo or video from Android's media picker.", depends = listOf(1), caps = setOf(TaskCapability.MEDIA_PICKER), criteria = listOf("The intended media preview is visible")))
        add(step(3, "Prepare caption and hashtags", "Prepare caption text and optional hashtags only from user-provided or user-approved content; keep it as a draft.", depends = listOf(2), caps = setOf(TaskCapability.TEXT_ENTRY), criteria = listOf("Caption and hashtags are editable and not published")))
        add(step(4, "Review Instagram post", "Verify account, selected media, caption, tagged people, audience and visible settings.", depends = listOf(3), caps = setOf(TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION), criteria = listOf("User has reviewed the complete post preview")))
        if (includePublish) {
            add(step(5, "Share post", "Publishing is consequential. Stop and let the user manually tap Share after the final review.", depends = listOf(4), caps = setOf(TaskCapability.USER_CONFIRMATION), criteria = listOf("User manually confirms Share"), risk = TaskRisk.HIGH, reversible = false, approval = true))
        }
    }

    private fun explanationPlan() = listOf(
        step(1, "Explain without launching", "Provide instructions only. Do not open or launch another application.")
    )

    private fun draftOnlyPlan() = listOf(
        step(
            1,
            "Prepare message draft",
            "Prepare the message as a draft only. Do not send, share or submit it.",
            caps = setOf(TaskCapability.MESSAGE_DRAFT),
            criteria = listOf("Draft is prepared and remains unsent")
        )
    )

    private fun socialPostPlan() = listOf(
        step(1, "Open social app", "Launch the requested social application.", caps = setOf(TaskCapability.APP_LAUNCH)),
        step(2, "Choose media", "Ask the user to select the photo or video; never guess media.", depends = listOf(1), caps = setOf(TaskCapability.MEDIA_PICKER), criteria = listOf("User selected the correct media")),
        step(3, "Prepare caption", "Create or insert the caption as a draft.", depends = listOf(2), caps = setOf(TaskCapability.TEXT_ENTRY)),
        step(4, "Review post", "Verify account, media, caption, audience and visibility.", depends = listOf(3), caps = setOf(TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION)),
        step(5, "Publish", "Publishing is consequential; user must tap Publish after review.", depends = listOf(4), caps = setOf(TaskCapability.USER_CONFIRMATION), criteria = listOf("User manually confirms Publish"), risk = TaskRisk.HIGH, reversible = false, approval = true)
    )

    private fun emailPlan() = listOf(
        step(1, "Open email app", "Open Gmail or the configured mail application.", caps = setOf(TaskCapability.APP_LAUNCH)),
        step(2, "Resolve recipient", "Find recipient; ask if the result is ambiguous.", depends = listOf(1), caps = setOf(TaskCapability.STRUCTURED_UI_ACTION)),
        step(3, "Prepare email draft", "Prepare subject and body without sending.", depends = listOf(2), caps = setOf(TaskCapability.TEXT_ENTRY)),
        step(4, "Review email", "Verify To, Cc, subject and body.", depends = listOf(3), caps = setOf(TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION)),
        step(5, "Send email", "User manually confirms the final Send action.", depends = listOf(4), caps = setOf(TaskCapability.USER_CONFIRMATION), risk = TaskRisk.HIGH, reversible = false, approval = true)
    )

    private fun formPlan() = listOf(
        step(1, "Open official form", "Identify and open the requested app or official website.", caps = setOf(TaskCapability.APP_LAUNCH, TaskCapability.WEB_NAVIGATION)),
        step(2, "Inspect form fields", "Read field labels and mark required fields.", depends = listOf(1), caps = setOf(TaskCapability.SCREEN_OBSERVATION)),
        step(3, "Prepare field values", "Use only values provided or confirmed by the user.", depends = listOf(2), caps = setOf(TaskCapability.TEXT_ENTRY)),
        step(4, "Review completed form", "Show all fields before submission.", depends = listOf(3), caps = setOf(TaskCapability.SCREEN_OBSERVATION, TaskCapability.USER_CONFIRMATION)),
        step(5, "Submit form", "Submission requires explicit user approval.", depends = listOf(4), caps = setOf(TaskCapability.USER_CONFIRMATION), risk = TaskRisk.HIGH, reversible = false, approval = true)
    )

    private fun callPlan() = listOf(
        step(1, "Open contact picker", "Use Android's system contact picker; the user selects the contact.", caps = setOf(TaskCapability.CONTACT_PICKER)),
        step(2, "Review selected number", "Open the dialer with the selected number; do not place the call automatically.", depends = listOf(1), criteria = listOf("Correct number is visible in dialer"), risk = TaskRisk.MEDIUM),
        step(3, "Place call", "User manually taps the call button.", depends = listOf(2), risk = TaskRisk.HIGH, reversible = false, approval = true)
    )

    private fun browserPlan() = listOf(
        step(1, "Open browser", "Launch a browser or use an Android web intent.", caps = setOf(TaskCapability.APP_LAUNCH, TaskCapability.WEB_NAVIGATION)),
        step(2, "Observe page", "Inspect the current accessible UI tree.", depends = listOf(1), caps = setOf(TaskCapability.SCREEN_OBSERVATION)),
        step(3, "Complete requested navigation", "Use semantic controls and verify the resulting page.", depends = listOf(2), caps = setOf(TaskCapability.STRUCTURED_UI_ACTION), criteria = listOf("Expected page or result is visible"))
    )

    private fun genericPlan() = listOf(
        step(1, "Extract goal and constraints", "Identify target app, requested data, constraints and desired outcome."),
        step(2, "Discover local capabilities", "Check supported Android intents, available apps and permitted UI actions."),
        step(3, "Build dependency graph", "Break the request into small ordered steps with acceptance criteria."),
        step(4, "Observe and execute reversible steps", "Use a fresh UI snapshot; only user-approved semantic actions can execute.", depends = listOf(3), caps = setOf(TaskCapability.SCREEN_OBSERVATION, TaskCapability.STRUCTURED_UI_ACTION)),
        step(5, "Verify and replan", "Compare actual UI state with acceptance criteria; retry within bounded limits."),
        step(6, "Confirm consequential action", "Sending, publishing, purchasing, deleting or submitting always requires user approval.", depends = listOf(5), caps = setOf(TaskCapability.USER_CONFIRMATION), risk = TaskRisk.HIGH, reversible = false, approval = true)
    )

    private fun step(
        id: Int, title: String, description: String,
        depends: List<Int> = emptyList(),
        caps: Set<TaskCapability> = emptySet(),
        criteria: List<String> = emptyList(),
        risk: TaskRisk = TaskRisk.LOW,
        reversible: Boolean = true,
        approval: Boolean = false
    ) = TaskStep(
        id = id,
        title = title,
        description = description,
        dependsOn = depends,
        requiredCapabilities = caps,
        acceptanceCriteria = criteria,
        retryLimit = if (risk.ordinal >= TaskRisk.HIGH.ordinal) 0 else 2,
        risk = risk,
        reversible = reversible,
        requiresUserApproval = approval
    )

    private fun containsAny(text: String, vararg phrases: String): Boolean = phrases.any(text::contains)

    private fun isNegatedTarget(text: String, vararg targets: String): Boolean {
        val negativeEnglish = listOf("don't use", "do not use", "never use", "don't open", "do not open", "without")
        if (targets.any { target -> negativeEnglish.any { prefix -> "$prefix $target" in text } }) return true
        val bengaliNegative = listOf("ব্যবহার করো না", "ব্যবহার করব না", "খুলবে না", "চালাবে না")
        return targets.any { target -> bengaliNegative.any { suffix -> "$target $suffix" in text } }
    }

    private fun isNegatedAction(text: String, vararg actions: String): Boolean {
        val prefixes = listOf("don't", "do not", "never", "without")
        if (actions.any { action -> prefixes.any { prefix -> "$prefix $action" in text } }) return true
        val bengaliNegative = listOf("না পাঠিয়ে", "পাঠিও না", "প্রকাশ করবে না", "পোস্ট করবে না", "শেয়ার করবে না")
        return bengaliNegative.any(text::contains)
    }
}
