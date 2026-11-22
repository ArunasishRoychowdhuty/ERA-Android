package com.eraassistant.mobile.ai

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed capability-routing boundary for platform adapters.
 * Adapters own explicitly declared action kinds and validate the live target at dispatch.
 */
data class AgentDispatchRequest(
    val actionId: String,
    val idempotencyKey: String,
    val observation: AgentObservation,
    val action: AgentActionProposal,
    val approvalReceipt: AgentApprovalReceipt? = null
) {
    fun validate(): String? {
        if (actionId.isBlank() || actionId.length > 128) return "Action ID is missing or too long."
        if (idempotencyKey.isBlank() || idempotencyKey.length > 160) return "Idempotency key is missing or too long."
        if (observation.id.isBlank() || observation.id.length > 160) return "Observation ID is missing or too long."
        if (observation.id != action.basedOnObservationId) return "Dispatch request is not bound to the proposal observation."
        return null
    }
}

enum class AgentDispatchRecordState { IN_PROGRESS, COMPLETED, REJECTED, FAILED }

sealed interface AgentDispatchClaim {
    data object Acquired : AgentDispatchClaim
    data class Existing(val state: AgentDispatchRecordState) : AgentDispatchClaim
    data object KeyConflict : AgentDispatchClaim
    data object StorageFailure : AgentDispatchClaim
}

/**
 * Claim must be durably stored before adapter execution. An IN_PROGRESS record after
 * process death is uncertain and must never be replayed automatically.
 */
interface AgentDispatchLedger {
    fun claim(idempotencyKey: String, actionFingerprint: String): AgentDispatchClaim
    fun finish(
        idempotencyKey: String,
        actionFingerprint: String,
        state: AgentDispatchRecordState
    ): Boolean
}

class InMemoryAgentDispatchLedger : AgentDispatchLedger {
    private data class Entry(val fingerprint: String, val state: AgentDispatchRecordState)
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    override fun claim(idempotencyKey: String, actionFingerprint: String): AgentDispatchClaim {
        val existing = entries[idempotencyKey] ?: run {
            entries[idempotencyKey] = Entry(actionFingerprint, AgentDispatchRecordState.IN_PROGRESS)
            return AgentDispatchClaim.Acquired
        }
        if (existing.fingerprint != actionFingerprint) return AgentDispatchClaim.KeyConflict
        return AgentDispatchClaim.Existing(existing.state)
    }

    @Synchronized
    override fun finish(
        idempotencyKey: String,
        actionFingerprint: String,
        state: AgentDispatchRecordState
    ): Boolean {
        if (state == AgentDispatchRecordState.IN_PROGRESS) return false
        val existing = entries[idempotencyKey] ?: return false
        if (existing.fingerprint != actionFingerprint || existing.state != AgentDispatchRecordState.IN_PROGRESS) return false
        entries[idempotencyKey] = existing.copy(state = state)
        return true
    }
}

interface AgentCapabilityAdapter {
    val capabilityId: String
    val supportedActions: Set<AgentActionKind>
    suspend fun execute(request: AgentDispatchRequest): AgentExecutionReceipt
}

/**
 * Re-checks the deterministic action policy at the final routing boundary, then claims
 * idempotency before invoking exactly one declared capability adapter.
 */
@Deprecated(
    message = "Legacy AgentActionKind registry retained for the portable runtime. " +
        "New production adapters use capability.CapabilityRegistry."
)
class AgentCapabilityRegistry(
    private val dispatchLedger: AgentDispatchLedger,
    private val clock: AgentClock = AgentClock { System.currentTimeMillis() },
    private val maxObservationAgeMs: Long = 10_000L,
    private val maxApprovalAgeMs: Long = 60_000L
) {
    private val adaptersByAction = linkedMapOf<AgentActionKind, AgentCapabilityAdapter>()
    private val adaptersById = linkedMapOf<String, AgentCapabilityAdapter>()
    private val consumedApprovalIds = ConcurrentHashMap<String, Long>()

    init {
        require(maxObservationAgeMs in 1_000L..30_000L)
        require(maxApprovalAgeMs in 5_000L..120_000L)
    }

    @Synchronized
    fun register(adapter: AgentCapabilityAdapter) {
        require(CAPABILITY_ID.matches(adapter.capabilityId)) { "Invalid capability ID." }
        require(adapter.supportedActions.isNotEmpty()) { "A capability must declare at least one supported action." }
        require(adapter.capabilityId !in adaptersById) { "Capability ID is already registered." }
        adapter.supportedActions.forEach { action ->
            require(action != AgentActionKind.UNKNOWN) { "UNKNOWN cannot be registered as an executable capability." }
            require(action !in adaptersByAction) { "Action $action is already owned by another capability." }
        }
        adaptersById[adapter.capabilityId] = adapter
        adapter.supportedActions.forEach { adaptersByAction[it] = adapter }
    }

    @Synchronized
    fun capabilityIds(): Set<String> = adaptersById.keys.toSet()

    @Synchronized
    fun supports(action: AgentActionKind): Boolean = action in adaptersByAction

    suspend fun dispatch(request: AgentDispatchRequest): AgentExecutionReceipt {
        val invalid = request.validate()
        if (invalid != null) {
            return AgentExecutionReceipt(false, invalid, AgentExecutionFailureKind.INVALID_REQUEST)
        }
        if (!isFresh(request.observation)) {
            return AgentExecutionReceipt(
                false,
                "The proposal observation is stale; capture the screen again.",
                AgentExecutionFailureKind.STALE_OBSERVATION
            )
        }

        when (val policy = AgentActionPolicy.evaluate(request.action, request.observation).disposition) {
            AgentActionDisposition.BLOCKED -> return AgentExecutionReceipt(
                false,
                "The action was rejected by the final capability policy.",
                AgentExecutionFailureKind.POLICY_BLOCKED
            )
            AgentActionDisposition.MANUAL_ONLY -> return AgentExecutionReceipt(
                false,
                "This action must remain user-performed and cannot be sent to an adapter.",
                AgentExecutionFailureKind.MANUAL_ACTION_REQUIRED
            )
            AgentActionDisposition.REQUIRES_APPROVAL -> {
                val approval = request.approvalReceipt
                if (approval == null || !isValidApproval(approval, request.action, request.observation) ||
                    !consumeApprovalId(approval.approvalId)) {
                    return AgentExecutionReceipt(
                        false,
                        "A fresh, exact-action, one-time approval is required at the capability boundary.",
                        AgentExecutionFailureKind.APPROVAL_REQUIRED
                    )
                }
            }
            AgentActionDisposition.AUTO_ALLOWED -> Unit
        }

        val adapter = synchronized(this) { adaptersByAction[request.action.kind] }
            ?: return AgentExecutionReceipt(
                false,
                "No registered capability supports ${request.action.kind}.",
                AgentExecutionFailureKind.UNSUPPORTED_CAPABILITY
            )

        val fingerprint = AgentActionFingerprint.of(request.action)
        val claim = runCatching {
            dispatchLedger.claim(request.idempotencyKey, fingerprint)
        }.getOrElse {
            return AgentExecutionReceipt(
                false,
                "Dispatch journal could not claim this action; no adapter was called.",
                AgentExecutionFailureKind.JOURNAL_FAILURE
            )
        }

        when (claim) {
            AgentDispatchClaim.Acquired -> Unit
            AgentDispatchClaim.KeyConflict -> return AgentExecutionReceipt(
                false,
                "Idempotency key was previously bound to a different action.",
                AgentExecutionFailureKind.IDEMPOTENCY_CONFLICT
            )
            AgentDispatchClaim.StorageFailure -> return AgentExecutionReceipt(
                false,
                "Dispatch journal could not persist the claim; no adapter was called.",
                AgentExecutionFailureKind.JOURNAL_FAILURE
            )
            is AgentDispatchClaim.Existing -> {
                val failure = if (claim.state == AgentDispatchRecordState.IN_PROGRESS) {
                    AgentExecutionFailureKind.RECOVERY_REQUIRED
                } else AgentExecutionFailureKind.DUPLICATE_DISPATCH
                return AgentExecutionReceipt(
                    false,
                    if (claim.state == AgentDispatchRecordState.IN_PROGRESS) {
                        "A previous dispatch may have started before interruption. Inspect the target app; automatic replay is blocked."
                    } else {
                        "This idempotency key has already been used. A new reviewed attempt requires a new key."
                    },
                    failure,
                    retryable = false
                )
            }
        }

        val receipt = try {
            adapter.execute(request)
        } catch (cancelled: CancellationException) {
            // Leave the durable claim IN_PROGRESS: the adapter may have produced a
            // partial external effect before cancellation. Recovery must inspect, not replay.
            throw cancelled
        } catch (error: Throwable) {
            AgentExecutionReceipt(
                false,
                "Capability adapter failed: ${(error.message ?: error::class.simpleName ?: "unknown").take(160)}",
                AgentExecutionFailureKind.ADAPTER_EXCEPTION,
                retryable = false
            )
        }

        val finalState = when {
            receipt.accepted -> AgentDispatchRecordState.COMPLETED
            receipt.failure == AgentExecutionFailureKind.ADAPTER_EXCEPTION -> AgentDispatchRecordState.FAILED
            else -> AgentDispatchRecordState.REJECTED
        }
        val recorded = runCatching {
            dispatchLedger.finish(request.idempotencyKey, fingerprint, finalState)
        }.getOrDefault(false)

        return if (recorded) receipt else receipt.copy(
            detail = (receipt.detail + " Dispatch journal could not persist the final state; outcome requires manual inspection.").take(500),
            failure = AgentExecutionFailureKind.JOURNAL_FAILURE,
            retryable = false
        )
    }

    private fun isFresh(observation: AgentObservation): Boolean {
        val age = clock.nowEpochMs() - observation.capturedAtEpochMs
        return observation.id.isNotBlank() && age in 0..maxObservationAgeMs
    }

    private fun isValidApproval(
        receipt: AgentApprovalReceipt,
        action: AgentActionProposal,
        observation: AgentObservation
    ): Boolean {
        if (receipt.approvalId.isBlank() || receipt.approvalId.length > 128) return false
        if (receipt.observationId != observation.id) return false
        if (receipt.actionFingerprint != AgentActionFingerprint.of(action)) return false
        return (clock.nowEpochMs() - receipt.approvedAtEpochMs) in 0..maxApprovalAgeMs
    }

    private fun consumeApprovalId(approvalId: String): Boolean {
        val now = clock.nowEpochMs()
        consumedApprovalIds.entries.removeIf { now - it.value > maxApprovalAgeMs }
        return consumedApprovalIds.putIfAbsent(approvalId, now) == null
    }

    companion object {
        private val CAPABILITY_ID = Regex("[a-z][a-z0-9._-]{1,63}")
    }
}

/** Adapter that lets the bounded runtime use the typed capability registry and exact authorization context. */
class RegistryBackedAgentActionExecutor(
    private val registry: AgentCapabilityRegistry,
    private val requestFactory: (AgentActionProposal, AgentObservation, AgentApprovalReceipt?) -> AgentDispatchRequest
) : AgentActionExecutor {
    override suspend fun execute(action: AgentActionProposal): AgentExecutionReceipt =
        AgentExecutionReceipt(false, "Capability dispatch requires an observation and authorization context.", AgentExecutionFailureKind.INVALID_REQUEST)

    override suspend fun execute(
        action: AgentActionProposal,
        observation: AgentObservation,
        approval: AgentApprovalReceipt?
    ): AgentExecutionReceipt = registry.dispatch(requestFactory(action, observation, approval))
}

/**
 * Deterministic IDs for one run. Reuse runId only when deliberately recovering that run;
 * a repeated sequence then collides with the journal and is held for manual inspection.
 */
class SequentialAgentDispatchRequestFactory(runId: String) {
    private val safeRunId = runId.trim().also {
        require(it.isNotBlank() && it.length <= 80) { "runId must contain 1 to 80 characters." }
    }
    private val sequence = AtomicLong(0L)

    fun create(
        action: AgentActionProposal,
        observation: AgentObservation,
        approval: AgentApprovalReceipt? = null
    ): AgentDispatchRequest {
        val index = sequence.incrementAndGet()
        val actionId = "$safeRunId:$index"
        val keySource = "$safeRunId|$index"
        val key = MessageDigest.getInstance("SHA-256")
            .digest(keySource.toByteArray(Charsets.UTF_8))
            .take(20)
            .joinToString("") { byte -> "%02x".format(byte) }
        return AgentDispatchRequest(actionId, key, observation, action, approval)
    }
}
