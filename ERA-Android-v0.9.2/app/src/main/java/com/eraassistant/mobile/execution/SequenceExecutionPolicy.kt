package com.eraassistant.mobile.execution

enum class SequenceStepDisposition {
    MACHINE_VERIFIED,
    EXECUTED_UNVERIFIED,
    FAILED,
    NOT_DISPATCHED,
    RECOVERY_REQUIRED,
    CANCELLED
}

data class SequenceStepOutcome(
    val stepIndex: Int,
    val totalSteps: Int,
    val commandLabel: String,
    val disposition: SequenceStepDisposition,
    val message: String
) {
    init {
        require(stepIndex in 1..totalSteps)
        require(commandLabel.isNotBlank())
        require(message.length <= 2_000)
    }

    val mayContinue: Boolean
        get() = disposition == SequenceStepDisposition.MACHINE_VERIFIED ||
            disposition == SequenceStepDisposition.EXECUTED_UNVERIFIED
}

data class SequenceProgress(
    val outcomes: List<SequenceStepOutcome>,
    val cancelled: Boolean = false
) {
    val completedCount: Int get() = outcomes.count { it.mayContinue }
    val hasUnverifiedSteps: Boolean get() =
        outcomes.any { it.disposition == SequenceStepDisposition.EXECUTED_UNVERIFIED }
    val shouldStop: Boolean get() = cancelled || outcomes.lastOrNull()?.mayContinue == false
    val summary: String
        get() = when {
            cancelled -> "Sequence cancelled after $completedCount of ${outcomes.size} completed steps."
            shouldStop -> "Sequence partially completed: $completedCount of ${outcomes.size} steps completed; last result was ${outcomes.lastOrNull()?.disposition ?: "unknown"}."
            hasUnverifiedSteps -> "Sequence dispatched all steps, but at least one outcome still needs user verification."
            else -> "Sequence completed: $completedCount of ${outcomes.size} steps."
        }
}
