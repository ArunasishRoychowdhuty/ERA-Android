package com.eraassistant.mobile.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceExecutionPolicyTest {
    private fun outcome(index: Int, disposition: SequenceStepDisposition) =
        SequenceStepOutcome(index, 2, "Step $index", disposition, "result")

    @Test fun torchThenAppContinuesWhenBothAreVerified() {
        val progress = SequenceProgress(listOf(
            outcome(1, SequenceStepDisposition.MACHINE_VERIFIED),
            outcome(2, SequenceStepDisposition.MACHINE_VERIFIED)
        ))
        assertFalse(progress.shouldStop)
        assertEqualsText("Sequence completed: 2 of 2 steps.", progress.summary)
    }

    @Test fun appLaunchVerificationUnavailableDoesNotStopIndependentVolumeStep() {
        val progress = SequenceProgress(listOf(
            outcome(1, SequenceStepDisposition.EXECUTED_UNVERIFIED),
            outcome(2, SequenceStepDisposition.MACHINE_VERIFIED)
        ))
        assertFalse(progress.shouldStop)
        assertTrue(progress.hasUnverifiedSteps)
        assertTrue(progress.summary.contains("needs user verification"))
    }

    @Test fun actualFirstActionFailureStopsSequence() {
        val progress = SequenceProgress(listOf(outcome(1, SequenceStepDisposition.FAILED)))
        assertTrue(progress.shouldStop)
        assertTrue(progress.summary.contains("partially completed"))
    }

    @Test fun noDispatchAndRecoveryRequiredStopSequence() {
        assertTrue(SequenceProgress(listOf(outcome(1, SequenceStepDisposition.NOT_DISPATCHED))).shouldStop)
        assertTrue(SequenceProgress(listOf(outcome(1, SequenceStepDisposition.RECOVERY_REQUIRED))).shouldStop)
    }

    @Test fun cancellationStopsAfterCompletedPrefix() {
        val progress = SequenceProgress(
            listOf(outcome(1, SequenceStepDisposition.MACHINE_VERIFIED)),
            cancelled = true
        )
        assertTrue(progress.shouldStop)
        assertTrue(progress.summary.contains("cancelled"))
    }

    private fun assertEqualsText(expected: String, actual: String) = org.junit.Assert.assertEquals(expected, actual)
}
