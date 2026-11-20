package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.PreviewPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskExecutionMonitorTest {
    @Test fun planWithConsequentialStepWaitsForUserApproval() = runBlocking {
        val plan = PreviewPlanner().createPlan("Instagram-এ পোস্ট কর")
        val coordinator = TaskExecutionCoordinator.from(plan)
        val snapshot = TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all(), 123L)
        assertEquals(TaskMonitorPhase.PLANNING, snapshot.phase)
        assertEquals(plan.steps.size, snapshot.totalSteps)
        assertEquals(0, snapshot.verifiedSteps)
        assertEquals(0, snapshot.waitingApprovalSteps)
        assertTrue(snapshot.toDisplayText().contains("does not dispatch"))
    }

    @Test fun monitorShowsVerifiedProgress() = runBlocking {
        val plan = PreviewPlanner().createPlan("WhatsApp-এ message draft তৈরি কর")
        val coordinator = TaskExecutionCoordinator.from(plan)
        coordinator.start("1")
        coordinator.readyForReview("1", "WhatsApp opened")
        coordinator.approve("1")
        coordinator.reportDispatched("1")
        coordinator.verify("1", "WhatsApp foreground")
        val snapshot = TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all())
        assertEquals(1, snapshot.verifiedSteps)
        assertTrue(snapshot.totalSteps > snapshot.verifiedSteps)
    }
}
