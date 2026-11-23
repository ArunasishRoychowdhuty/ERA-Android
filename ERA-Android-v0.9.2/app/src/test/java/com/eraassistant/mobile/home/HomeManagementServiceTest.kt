package com.eraassistant.mobile.home

import com.eraassistant.mobile.memory.DomainMemoryService
import com.eraassistant.mobile.memory.PersistentAgentMemory
import org.junit.Assert.*
import org.junit.Test

class HomeManagementServiceTest {
    private fun createService(clock: () -> Long): HomeManagementService {
        val memory = PersistentAgentMemory(context = null)
        val domainMemory = DomainMemoryService(memory, clock)
        return HomeManagementService(domainMemory, clock)
    }

    @Test fun householdTaskCrudWorksWithRealEpochMillisClock() {
        var now = 1_700_000_000_000L
        val service = createService { now }

        val created = service.createTask(
            title = "Water the plants",
            dueAtEpochMs = now + 3_600_000L,
            explicitUserConsent = true
        )
        assertEquals(HomeManagementDecision.SAVED, created.decision)
        val task = created.value!!
        assertEquals("Water the plants", task.title)
        assertEquals(now + 3_600_000L, task.dueAtEpochMs)
        assertEquals(1, service.listTasks().size)

        now += 5_000L
        val updated = service.updateTaskStatus(task.id, HouseholdTaskStatus.COMPLETED, true)
        assertEquals(HomeManagementDecision.SAVED, updated.decision)
        assertEquals(HouseholdTaskStatus.COMPLETED, service.listTasks().single().status)

        val deleted = service.deleteTask(task.id, true)
        assertEquals(HomeManagementDecision.SAVED, deleted.decision)
        assertTrue(service.listTasks().isEmpty())
    }

    @Test fun taskMutationRequiresExplicitConsent() {
        var now = 1_700_000_000_000L
        val service = createService { now }
        val created = service.createTask("Clean the kitchen", explicitUserConsent = false)
        assertEquals(HomeManagementDecision.CONSENT_REQUIRED, created.decision)
        assertTrue(service.listTasks().isEmpty())
    }

    @Test fun inventoryUpsertUpdatesExistingItemInsteadOfDuplicatingIt() {
        var now = 1_700_000_000_000L
        val service = createService { now }
        val first = service.upsertInventoryItem("Rice", 2, "kg", explicitUserConsent = true)
        assertEquals(HomeManagementDecision.SAVED, first.decision)
        now += 1000L
        val second = service.upsertInventoryItem("rice", 5, "kg", explicitUserConsent = true)
        assertEquals(HomeManagementDecision.SAVED, second.decision)
        assertEquals(1, service.listInventory().size)
        assertEquals(5, service.listInventory().single().quantity)
        assertEquals(first.value?.id, second.value?.id)
    }

    @Test fun routineSlotsPersistIndependently() {
        val service = createService { 1_700_000_000_000L }
        assertEquals(
            HomeManagementDecision.SAVED,
            service.saveRoutine(HouseholdRoutineSlot.MORNING, "Review today's household tasks", true).decision
        )
        assertEquals(
            HomeManagementDecision.SAVED,
            service.saveRoutine(HouseholdRoutineSlot.CLEANING, "Sweep the living room", true).decision
        )
        assertEquals("Review today's household tasks", service.getRoutine(HouseholdRoutineSlot.MORNING))
        assertEquals("Sweep the living room", service.getRoutine(HouseholdRoutineSlot.CLEANING))
        assertNull(service.getRoutine(HouseholdRoutineSlot.EVENING))
    }

    @Test fun invalidAndOutOfRangeDueTimesAreRejected() {
        val now = 1_700_000_000_000L
        val service = createService { now }
        assertEquals(
            HomeManagementDecision.INVALID_INPUT,
            service.createTask("Past due", dueAtEpochMs = now - 1L, explicitUserConsent = true).decision
        )
        assertEquals(
            HomeManagementDecision.INVALID_INPUT,
            service.createTask("Too far", dueAtEpochMs = now + 400L * 86_400_000L, explicitUserConsent = true).decision
        )
        assertEquals(
            HomeManagementDecision.INVALID_INPUT,
            service.upsertInventoryItem("Sugar", -1, "kg", explicitUserConsent = true).decision
        )
    }

    @Test fun taskListHasABoundedCapacity() {
        val service = createService { 1_700_000_000_000L }
        repeat(HomeManagementService.MAX_TASKS) { index ->
            assertEquals(
                HomeManagementDecision.SAVED,
                service.createTask("Household task ${index + 1}", explicitUserConsent = true).decision
            )
        }
        assertEquals(
            HomeManagementDecision.LIMIT_REACHED,
            service.createTask("One more", explicitUserConsent = true).decision
        )
        assertEquals(HomeManagementService.MAX_TASKS, service.listTasks().size)
    }
}
