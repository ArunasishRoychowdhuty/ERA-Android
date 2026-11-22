package com.eraassistant.mobile.recovery

import com.eraassistant.mobile.capability.ExecutionError
import com.eraassistant.mobile.capability.ExecutionErrorCategory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExecutionJournalTest {

    private lateinit var persistence: InMemoryExecutionJournalPersistence
    private lateinit var journal: ExecutionJournal

    @Before
    fun setup() {
        persistence = InMemoryExecutionJournalPersistence()
        journal = ExecutionJournal(persistence)
    }

    @Test
    fun testIdempotencyAndClaimAcquisition() {
        val claim1 = journal.claim(
            taskId = "t1",
            actionId = "act_1",
            idempotencyKey = "key_101",
            actionFingerprint = "fp_abc123",
            actionName = "device.torch.set"
        )
        assertTrue(claim1 is JournalClaimResult.Acquired)

        val entry = journal.findByActionId("act_1")
        assertNotNull(entry)
        assertEquals(JournalEntryState.IN_PROGRESS, entry?.state)

        journal.markCompleted(entry!!.journalId)
        val updated = journal.findByActionId("act_1")
        assertEquals(JournalEntryState.COMPLETED, updated?.state)

        // Attempt duplicate action with same key and fingerprint
        val claim2 = journal.claim(
            taskId = "t1",
            actionId = "act_2",
            idempotencyKey = "key_101",
            actionFingerprint = "fp_abc123",
            actionName = "device.torch.set"
        )
        assertTrue("Duplicate action must be recognized as existing", claim2 is JournalClaimResult.Existing)
    }

    @Test
    fun testIdempotencyConflictOnFingerprintMismatch() {
        journal.claim(
            taskId = "t2",
            actionId = "act_orig",
            idempotencyKey = "key_shared",
            actionFingerprint = "fp_original",
            actionName = "device.volume.adjust"
        )

        // Reusing the same idempotency key for a different action must conflict
        val conflictClaim = journal.claim(
            taskId = "t2",
            actionId = "act_diff",
            idempotencyKey = "key_shared",
            actionFingerprint = "fp_different",
            actionName = "browser.webview.click"
        )
        assertTrue(conflictClaim is JournalClaimResult.Conflict)
    }

    @Test
    fun testCrashRecoveryReconcilesInProgressEntries() {
        journal.claim(
            taskId = "t3",
            actionId = "act_crash",
            idempotencyKey = "key_crash",
            actionFingerprint = "fp_crash",
            actionName = "device.app.launch",
            isConsequential = true
        )

        // Simulate restart
        val recovered = journal.reconcileAfterRestart()
        assertEquals(1, recovered.size)
        assertEquals(JournalEntryState.INTERRUPTED_RECOVERY_REQUIRED, recovered.first().state)

        // Claiming the same key now must return RecoveryRequired and refuse automatic execution
        val retryClaim = journal.claim(
            taskId = "t3",
            actionId = "act_crash_retry",
            idempotencyKey = "key_crash",
            actionFingerprint = "fp_crash",
            actionName = "device.app.launch"
        )
        assertTrue(retryClaim is JournalClaimResult.RecoveryRequired)
    }

    @Test
    fun testRetryPolicyAndBackoff() {
        val policy = RetryPolicy(maxRetries = 3, initialBackoffMs = 200L, backoffMultiplier = 2.0)
        assertEquals(0L, policy.computeBackoff(0))
        assertEquals(200L, policy.computeBackoff(1))
        assertEquals(400L, policy.computeBackoff(2))
        assertEquals(800L, policy.computeBackoff(3))

        val retryableError = ExecutionError(ExecutionErrorCategory.TIMEOUT, "Timeout", retryable = true)
        val nonRetryableError = ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Bad param", retryable = false)

        assertTrue(policy.isRetryable(retryableError, 1))
        assertTrue(policy.isRetryable(retryableError, 2))
        assertFalse(policy.isRetryable(retryableError, 3)) // Max retries reached
        assertFalse(policy.isRetryable(nonRetryableError, 1))
    }

    @Test
    fun testCheckpointDataPersistence() {
        journal.claim(
            taskId = "t4",
            actionId = "act_ckpt",
            idempotencyKey = "key_ckpt",
            actionFingerprint = "fp_ckpt",
            actionName = "browser.webview.navigate"
        )
        val entry = journal.findByActionId("act_ckpt")!!
        assertTrue(journal.recordCheckpoint(entry.journalId, "checkpoint: loaded 50% DOM"))

        val updated = journal.findByActionId("act_ckpt")
        assertEquals("checkpoint: loaded 50% DOM", updated?.checkpointData)
    }

    @Test
    fun failedIdempotencyKeyCannotBeReused() {
        assertTrue(journal.claim("t5", "a5", "failed-key", "fp-5", "device.app.launch") is JournalClaimResult.Acquired)
        val entry = journal.findByActionId("a5")!!
        assertTrue(journal.markFailed(entry.journalId, "adapter timed out"))
        val retry = journal.claim("t5", "a6", "failed-key", "fp-5", "device.app.launch")
        assertTrue(retry is JournalClaimResult.RecoveryRequired)
    }

    @Test
    fun persistenceFailurePreventsActionClaim() {
        val failingPersistence = object : ExecutionJournalPersistence {
            override fun loadAll(): List<JournalEntry> = emptyList()
            override fun save(entry: JournalEntry): Boolean = false
            override fun remove(journalId: String) = Unit
            override fun clear() = Unit
        }
        val failClosedJournal = ExecutionJournal(failingPersistence)
        val claim = failClosedJournal.claim("t6", "a6", "never-durable", "fp-6", "device.app.launch")
        assertTrue(claim is JournalClaimResult.PersistenceFailure)
        assertTrue(failClosedJournal.allEntries().isEmpty())
        assertFalse(failClosedJournal.isAvailable())
    }

    @Test
    fun corruptJournalLoadDisablesClaims() {
        val corruptPersistence = object : ExecutionJournalPersistence {
            override fun loadAll(): List<JournalEntry> = error("corrupt")
            override fun save(entry: JournalEntry): Boolean = true
            override fun remove(journalId: String) = Unit
            override fun clear() = Unit
        }
        val failClosedJournal = ExecutionJournal(corruptPersistence)
        val claim = failClosedJournal.claim("t7", "a7", "corrupt-store-key", "fp-7", "device.app.launch")
        assertTrue(claim is JournalClaimResult.PersistenceFailure)
    }

    @Test
    fun waitingUserActionIsReconciledAfterRestart() {
        journal.claim("t8", "a8", "waiting-key", "fp-8", "browser.chrome.click")
        val entry = journal.findByActionId("a8")!!
        assertTrue(journal.markWaitingForUser(entry.journalId, "Waiting for overlay tap"))
        val recovered = journal.reconcileAfterRestart()
        assertEquals(1, recovered.size)
        assertEquals(JournalEntryState.INTERRUPTED_RECOVERY_REQUIRED, recovered.single().state)
    }
}
