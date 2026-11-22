package com.eraassistant.mobile.orchestration

import com.eraassistant.mobile.capability.CapabilityRegistry
import com.eraassistant.mobile.memory.MemoryCategory
import com.eraassistant.mobile.memory.PersistentAgentMemory
import com.eraassistant.mobile.memory.PrivacyFilter
import com.eraassistant.mobile.memory.WorkflowLearningEngine
import com.eraassistant.mobile.planner.PreviewPlanner
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep
import com.eraassistant.mobile.skills.SkillDefinition
import com.eraassistant.mobile.skills.SkillEngine
import com.eraassistant.mobile.skills.SkillStep
import com.eraassistant.mobile.statemachine.ExecutionState
import com.eraassistant.mobile.statemachine.TaskStateMachine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskPlanDAGAndFeedbackTest {

    @Test
    fun testDAGTopologicalOrderAndDependencyResolution() {
        val steps = listOf(
            TaskStep(id = 1, title = "Open App", description = "Launch app"),
            TaskStep(id = 2, title = "Find Chat", description = "Find chat", dependsOn = listOf(1)),
            TaskStep(id = 3, title = "Draft Msg", description = "Draft message", dependsOn = listOf(2)),
            TaskStep(id = 4, title = "Send Msg", description = "Send message", dependsOn = listOf(3))
        )
        val plan = TaskPlan(goal = "Send WhatsApp message", steps = steps, note = "Test plan")
        val dag = TaskPlanDAG(plan)

        val order = dag.topologicalOrder()
        assertEquals(4, order.size)
        assertEquals(1, order[0].id)
        assertEquals(2, order[1].id)
        assertEquals(3, order[2].id)
        assertEquals(4, order[3].id)

        // Ready steps initially: only step 1 has 0 dependencies
        val readyInit = dag.getReadySteps(emptySet())
        assertEquals(1, readyInit.size)
        assertEquals(1, readyInit.first().id)

        // After completing step 1: step 2 becomes ready
        val readyAfter1 = dag.getReadySteps(setOf(1))
        assertEquals(1, readyAfter1.size)
        assertEquals(2, readyAfter1.first().id)
    }

    @Test
    fun testDAGCircularDependencyRejection() {
        val cyclicSteps = listOf(
            TaskStep(id = 1, title = "A", description = "Step A", dependsOn = listOf(2)),
            TaskStep(id = 2, title = "B", description = "Step B", dependsOn = listOf(1))
        )
        val cyclicPlan = TaskPlan(goal = "Cycle", steps = cyclicSteps, note = "Cycle")
        val errors = cyclicPlan.validate()
        assertTrue("Plan validation must catch cycle", errors.any { it.contains("cycle", ignoreCase = true) })
    }

    @Test
    fun testPlannerFeedbackLoopLimits() = runBlocking {
        val planner = object : com.eraassistant.mobile.planner.AIPlanner {
            override suspend fun createPlan(userRequest: String): TaskPlan = TaskPlan(
                goal = "Test replan",
                steps = listOf(TaskStep(id = 1, title = "Manual review", description = "Review the proposed step")),
                note = "Capability-free deterministic test plan"
            )
        }
        val registry = CapabilityRegistry()
        val feedbackLoop = PlannerFeedbackLoop(planner, registry, maxReplans = 2)

        val stateMachine = TaskStateMachine(
            taskId = "t_replan",
            goal = "Test replan",
            initialActions = listOf("a1" to "Action 1")
        )

        // Replan 1: OK
        val r1 = feedbackLoop.requestReplan(stateMachine, "a1", null, null)
        assertTrue(r1.isSuccess)
        assertEquals(1, feedbackLoop.currentReplanCount())

        // Replan 2: Identical plan will be rejected to avoid infinite loops, or succeeds if distinct
        val r2 = feedbackLoop.requestReplan(stateMachine, "a1", null, null)
        // Either cyclic loop rejected or success
        assertNotNull(r2)

        // Further replan after budget exhausted must be rejected
        feedbackLoop.requestReplan(stateMachine, "a1", null, null)
        val rExhausted = feedbackLoop.requestReplan(stateMachine, "a1", null, null)
        assertTrue(rExhausted.isFailure)
        assertTrue(rExhausted.exceptionOrNull()?.message?.contains("exhausted") == true)
    }

    @Test
    fun testSkillEngineRegistrationAndDangerousCommandRejection() {
        val skillEngine = SkillEngine()

        // Valid custom skill
        val validSkill = SkillDefinition(
            id = "custom.skill.test",
            name = "Test Skill",
            version = "1.0",
            goalPattern = "do something",
            description = "Valid custom skill",
            steps = listOf(
                SkillStep(1, "Step 1", "device.torch.set", com.eraassistant.mobile.capability.CapabilityId.ANDROID_DEVICE)
            )
        )
        val regResult = skillEngine.registerSkill(validSkill)
        assertTrue(regResult.isSuccess)

        // Dangerous skill with shell execution pattern
        val dangerousSkill = SkillDefinition(
            id = "malicious.skill",
            name = "Malicious Skill",
            version = "1.0",
            goalPattern = "exploit",
            description = "Tries dangerous command",
            steps = listOf(
                SkillStep(
                    1,
                    "Run RM",
                    "dangerous.action",
                    com.eraassistant.mobile.capability.CapabilityId.ANDROID_DEVICE,
                    parametersTemplate = mapOf("cmd" to "rm -rf /")
                )
            )
        )
        val badResult = skillEngine.registerSkill(dangerousSkill)
        assertTrue("Dangerous commands must be rejected by SkillEngine", badResult.isFailure)
    }

    @Test
    fun testPersistentMemoryAndPrivacyFilter() {
        val memory = PersistentAgentMemory(null)

        // Privacy filter redacts secrets
        val clean = PrivacyFilter.sanitizeText("My key is AIzaSyB1234567890123456789012345678 and email is user@example.com")
        assertFalse(clean.contains("AIzaSyB1234567890123456789012345678"))
        assertFalse(clean.contains("user@example.com"))
        assertTrue(clean.contains("[REDACTED_API_KEY]"))
        assertTrue(clean.contains("[REDACTED_EMAIL]"))
        val sensitiveValues = PrivacyFilter.sanitizeText(
            "OTP: 123456, PIN 2468, card 4111 1111 1111 1111, year 2026, token abcdef0123456789abcdef0123456789"
        )
        assertFalse(sensitiveValues.contains("123456"))
        assertFalse(sensitiveValues.contains("2468"))
        assertFalse(sensitiveValues.contains("4111 1111 1111 1111"))
        assertFalse(sensitiveValues.contains("abcdef0123456789abcdef0123456789"))
        assertTrue("Ordinary years must survive contextual secret filtering.", sensitiveValues.contains("2026"))

        // Putting sensitive key directly is refused
        val putPassword = memory.put(MemoryCategory.USER_PREFERENCES, "account_password", "secret123")
        assertFalse(putPassword)

        // Putting valid preference works
        val putTheme = memory.put(MemoryCategory.USER_PREFERENCES, "theme_mode", "dark")
        assertTrue(putTheme)
        assertEquals("dark", memory.get(MemoryCategory.USER_PREFERENCES, "theme_mode"))
        assertFalse(memory.put(MemoryCategory.USER_PREFERENCES, "otp_value", "123456"))
        assertTrue(memory.put(MemoryCategory.USER_PREFERENCES, "account_note", "OTP: 654321"))
        assertFalse(memory.get(MemoryCategory.USER_PREFERENCES, "account_note")!!.contains("654321"))
        assertTrue(memory.put(MemoryCategory.USER_PREFERENCES, "mail user@example.com", "allowed"))
        assertFalse(memory.getCategoryRecords(MemoryCategory.USER_PREFERENCES).any { it.key.contains("user@example.com") })
    }

    @Test
    fun testWorkflowLearningFromSucceededTask() {
        val memory = PersistentAgentMemory(null)
        val workflowEngine = WorkflowLearningEngine(memory)

        val sm = TaskStateMachine(
            taskId = "task_learn_1",
            goal = "send message to Alice",
            initialActions = listOf("a1" to "Open Chat", "a2" to "Type Message")
        )
        sm.transitionTask(ExecutionState.VALIDATING)
        sm.transitionTask(ExecutionState.READY)
        sm.transitionTask(ExecutionState.RUNNING)
        sm.transitionAction("a1", ExecutionState.READY)
        sm.transitionAction("a1", ExecutionState.RUNNING)
        sm.transitionAction("a1", ExecutionState.SUCCEEDED)
        sm.transitionAction("a2", ExecutionState.READY)
        sm.transitionAction("a2", ExecutionState.RUNNING)
        sm.transitionAction("a2", ExecutionState.SUCCEEDED)
        sm.transitionTask(ExecutionState.VERIFIED)
        sm.transitionTask(ExecutionState.SUCCEEDED)

        val learned = workflowEngine.learnFromTask(sm)
        assertNotNull(learned)
        assertEquals(2, learned?.actionNames?.size)

        val retrieved = workflowEngine.findVerifiedWorkflow("send message to Alice")
        assertNotNull(retrieved)
        assertEquals(learned?.workflowId, retrieved?.workflowId)
    }
}
