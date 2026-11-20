package com.eraassistant.mobile.ai

/** Compatibility limits retained for the earlier task coordinator. */
object AgentLoopPolicy {
    private val consequential = listOf(
        "send", "publish", "post", "pay", "payment", "purchase", "buy", "checkout", "delete", "remove",
        "share", "transfer", "call", "submit", "confirm", "allow", "grant", "install",
        "পাঠান", "পাঠিয়ে", "প্রকাশ", "পোস্ট", "জমা", "দাখিল", "পরিশোধ", "পেমেন্ট", "কিনুন",
        "মুছুন", "ডিলিট", "শেয়ার", "কল", "অনুমতি", "নিশ্চিত", "ইনস্টল", "অর্ডার", "স্থানান্তর"
    )

    /** Legacy text helper only. The execution runtime must use [AgentActionPolicy]. */
    @Deprecated("Use typed AgentActionPolicy decisions in BoundedAgentRuntime.")
    fun requiresConfirmation(action: String): Boolean = consequential.any { token -> action.contains(token, ignoreCase = true) }

    const val MAX_STEPS_PER_RUN = 20
    const val MAX_REPLANS = 3
    const val MAX_ACTIONS_WITHOUT_OBSERVATION = 1
}
