package com.eraassistant.mobile.domain

/** High-level domain ownership used before selecting a planner or capability. */
enum class AgentDomain {
    ANDROID_OS, PERSONAL_PROFILE, HOME_MANAGEMENT, COMMUNICATION,
    PRODUCTIVITY, FILES, WEB, INFORMATION, UNKNOWN
}

enum class GoalRisk { LOW, MEDIUM, HIGH, CRITICAL }

data class DomainIntentProfile(
    val domains: Set<AgentDomain>,
    val confidence: Double,
    val risk: GoalRisk,
    val constraints: Set<String>,
    val targets: Set<String>,
    val requiresPersonalContext: Boolean,
    val requiresExplicitApproval: Boolean,
    val unresolvedInputs: List<String>
) {
    init {
        require(confidence in 0.0..1.0)
        require(domains.isNotEmpty())
    }
}

/** Deterministic routing hints. This is not a general semantic parser. */
object DomainIntentClassifier {
    private val personalTerms = listOf("my profile", "personal details", "about me", "my preferences", "আমার প্রোফাইল", "পার্সোনাল ডিটেইল", "ব্যক্তিগত তথ্য", "আমার পছন্দ")
    private val homeTerms = listOf("home management", "house management", "household", "home routine", "home task", "বাসা", "বাড়ি", "বাড়ি", "ঘরের কাজ", "বাসার ম্যানেজমেন্ট", "বাড়ির ম্যানেজমেন্ট", "বাজার", "মুদি", "ঘর পরিষ্কার", "বিলের রিমাইন্ডার")
    private val osTerms = listOf("android", "phone", "mobile", "settings", "app", "device control", "ফোন", "মোবাইল", "অ্যান্ড্রয়েড", "অ্যান্ড্রয়েড", "সেটিংস", "অ্যাপ")
    private val communicationTerms = listOf("message", "whatsapp", "email", "gmail", "call", "sms", "মেসেজ", "হোয়াটসঅ্যাপ", "কল", "ইমেইল")
    private val fileTerms = listOf("file", "folder", "document", "storage", "ফাইল", "ফোল্ডার", "ডকুমেন্ট")
    private val webTerms = listOf("website", "browser", "web page", "internet", "ওয়েবসাইট", "ব্রাউজার")
    private val productivityTerms = listOf("schedule", "calendar", "reminder", "task", "routine", "রুটিন", "সময়সূচি", "রিমাইন্ডার")
    private val highRiskTerms = listOf("send", "publish", "post", "submit", "delete", "remove", "pay", "payment", "purchase", "buy", "transfer", "place order", "install", "পাঠাও", "পাঠিয়ে দাও", "প্রকাশ কর", "পোস্ট কর", "জমা দাও", "মুছে ফেল", "পেমেন্ট", "টাকা পাঠাও", "কিনে দাও", "অর্ডার কর", "ইনস্টল")
    private val criticalRiskTerms = listOf("transfer money", "bank transfer", "pay bill", "payment", "purchase", "buy", "টাকা ট্রান্সফার", "ব্যাংক", "পেমেন্ট", "বিল পরিশোধ")
    private val prohibitionTerms = listOf("don't send", "do not send", "never send", "don't publish", "do not publish", "don't delete", "do not delete", "শুধু ড্রাফট", "পাঠাবে না", "পাঠিও না", "প্রকাশ করবে না", "ডিলিট করবে না", "মুছবে না", "শুধু বুঝিয়ে দাও")

    fun classify(input: String): DomainIntentProfile {
        val text = input.trim().lowercase()
        val domains = linkedSetOf<AgentDomain>()
        fun hasAny(terms: List<String>) = terms.any(text::contains)
        if (hasAny(personalTerms)) domains += AgentDomain.PERSONAL_PROFILE
        if (hasAny(homeTerms)) domains += AgentDomain.HOME_MANAGEMENT
        if (hasAny(osTerms)) domains += AgentDomain.ANDROID_OS
        if (hasAny(communicationTerms)) domains += AgentDomain.COMMUNICATION
        if (hasAny(fileTerms)) domains += AgentDomain.FILES
        if (hasAny(webTerms)) domains += AgentDomain.WEB
        if (hasAny(productivityTerms)) domains += AgentDomain.PRODUCTIVITY
        if (domains.isEmpty()) domains += AgentDomain.UNKNOWN
        if (domains.any { it == AgentDomain.PERSONAL_PROFILE || it == AgentDomain.HOME_MANAGEMENT }) domains += AgentDomain.PRODUCTIVITY

        val constraints = linkedSetOf<String>()
        if (hasAny(prohibitionTerms)) constraints += "RESPECT_EXPLICIT_PROHIBITIONS"
        if (listOf("only explain", "শুধু বুঝিয়ে", "শুধু বুঝিয়ে").any(text::contains)) constraints += "EXPLANATION_ONLY"
        if (listOf("draft only", "শুধু ড্রাফট", "না পাঠিয়ে").any(text::contains)) constraints += "DRAFT_ONLY"
        if (listOf("don't send", "do not send", "never send", "পাঠাবে না", "পাঠিও না", "পাঠিয়ো না").any(text::contains)) constraints += "NO_SEND"
        if (listOf("don't publish", "do not publish", "never publish", "প্রকাশ করবে না", "পোস্ট করবে না").any(text::contains)) constraints += "NO_PUBLISH"
        if (listOf("don't delete", "do not delete", "মুছবে না", "ডিলিট করবে না").any(text::contains)) constraints += "NO_DELETE"
        if (listOf("don't use", "do not use", "ব্যবহার করো না").any(text::contains)) constraints += "NEGATIVE_TOOL_PREFERENCE"
        if (domains.contains(AgentDomain.PERSONAL_PROFILE)) constraints += "PERSONAL_MEMORY_REQUIRES_CONSENT"
        if (domains.contains(AgentDomain.HOME_MANAGEMENT)) constraints += "HOUSEHOLD_DATA_REQUIRES_SCOPE"

        val risk = when {
            hasAny(criticalRiskTerms) -> GoalRisk.CRITICAL
            hasAny(highRiskTerms) -> GoalRisk.HIGH
            domains.any { it == AgentDomain.PERSONAL_PROFILE || it == AgentDomain.HOME_MANAGEMENT } -> GoalRisk.MEDIUM
            else -> GoalRisk.LOW
        }
        val targets = buildSet {
            listOf("whatsapp", "instagram", "gmail", "chrome", "camera", "settings").forEach { if (text.contains(it)) add(it) }
        }
        val personal = domains.any { it == AgentDomain.PERSONAL_PROFILE || it == AgentDomain.HOME_MANAGEMENT }
        val unresolved = buildList {
            if (domains.contains(AgentDomain.HOME_MANAGEMENT) &&
                hasAny(listOf("manage", "ম্যানেজ", "সব কাজ")) &&
                !hasAny(listOf("reminder", "schedule", "task", "রিমাইন্ডার", "রুটিন", "কাজ"))) {
                add("Choose the home workflow: tasks, reminders, inventory, or bills.")
            }
            if (domains.contains(AgentDomain.PERSONAL_PROFILE) &&
                !hasAny(listOf("save", "remember", "store", "মনে রাখ", "সেভ"))) {
                add("Personal details are not persisted unless the user explicitly chooses what ERA may remember.")
            }
        }
        val confidence = when {
            domains == setOf(AgentDomain.UNKNOWN) -> 0.35
            domains.size > 3 -> 0.62
            personal -> 0.88
            else -> 0.82
        }
        return DomainIntentProfile(
            domains = domains, confidence = confidence, risk = risk,
            constraints = constraints, targets = targets,
            requiresPersonalContext = personal,
            requiresExplicitApproval = risk >= GoalRisk.HIGH,
            unresolvedInputs = unresolved
        )
    }
}
