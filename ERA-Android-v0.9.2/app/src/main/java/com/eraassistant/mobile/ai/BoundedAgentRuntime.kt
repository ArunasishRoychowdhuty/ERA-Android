package com.eraassistant.mobile.ai

import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Platform-neutral observe/decide/authorize/execute/verify runtime.
 * Android, browser and model integrations are injected behind interfaces.
 * Screen/page content is untrusted input and never expands the action allow-list.
 */
enum class AgentActionKind {
    OPEN_APP,
    OPEN_SETTINGS,
    OPEN_URL,
    SCROLL,
    FOCUS_ELEMENT,
    CLICK_ELEMENT,
    TYPE_TEXT,
    SET_TORCH,
    ADJUST_VOLUME,
    SEND_MESSAGE,
    PUBLISH_CONTENT,
    SUBMIT_FORM,
    PURCHASE,
    MAKE_PAYMENT,
    DELETE_DATA,
    SHARE_DATA,
    PLACE_CALL,
    GRANT_PERMISSION,
    AUTHENTICATE,
    INSTALL_SOFTWARE,
    UNKNOWN
}

enum class AgentTextOrigin { USER_PROVIDED, USER_CONFIRMED, MODEL_GENERATED }
enum class AgentVolumeStream { MEDIA, RINGTONE, NOTIFICATION, ALARM }
enum class AgentScrollDirection { FORWARD, BACKWARD }

enum class AgentActionDisposition { AUTO_ALLOWED, REQUIRES_APPROVAL, MANUAL_ONLY, BLOCKED }

enum class AgentExecutionFailureKind {
    INVALID_REQUEST, UNSUPPORTED_CAPABILITY, IDEMPOTENCY_CONFLICT, DUPLICATE_DISPATCH,
    RECOVERY_REQUIRED, ADAPTER_EXCEPTION, JOURNAL_FAILURE, ADAPTER_REJECTED,
    POLICY_BLOCKED, MANUAL_ACTION_REQUIRED, APPROVAL_REQUIRED, STALE_OBSERVATION
}

data class AgentTarget(
    val packageName: String,
    val label: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val role: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
    val focusable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true
)

data class AgentObservation(
    /** Stable for an unchanged UI state; must change whenever the actionable UI changes. */
    val id: String,
    val packageName: String?,
    val capturedAtEpochMs: Long,
    val summary: String = ""
)

data class AgentActionProposal(
    val kind: AgentActionKind,
    val basedOnObservationId: String,
    val target: AgentTarget? = null,
    val text: String? = null,
    val textOrigin: AgentTextOrigin? = null,
    val url: String? = null,
    val packageName: String? = null,
    val torchEnabled: Boolean? = null,
    val volumeStream: AgentVolumeStream? = null,
    val volumePercent: Int? = null,
    val scrollDirection: AgentScrollDirection? = null,
    val rationale: String
)

data class AgentActionPolicyDecision(
    val disposition: AgentActionDisposition,
    val reason: String
)

/**
 * Deterministic policy. The model's self-reported risk is intentionally ignored.
 * Consequential actions are handed back to the user and are never dispatched by ERA.
 */
object AgentActionPolicy {
    private val manualOnlyKinds = setOf(
        AgentActionKind.SEND_MESSAGE,
        AgentActionKind.PUBLISH_CONTENT,
        AgentActionKind.SUBMIT_FORM,
        AgentActionKind.PURCHASE,
        AgentActionKind.MAKE_PAYMENT,
        AgentActionKind.DELETE_DATA,
        AgentActionKind.SHARE_DATA,
        AgentActionKind.PLACE_CALL,
        AgentActionKind.GRANT_PERMISSION,
        AgentActionKind.AUTHENTICATE,
        AgentActionKind.INSTALL_SOFTWARE
    )

    private val highImpactLabelPatterns = listOf(
        Regex("\\b(send|publish|post|submit|pay|payment|purchase|buy|checkout|delete|remove|share|call|transfer|confirm|allow|grant|install)\\b", RegexOption.IGNORE_CASE),
        Regex("(পাঠান|পাঠাও|পাঠিয়ে|প্রকাশ|পোস্ট|জমা|দাখিল|পরিশোধ|পেমেন্ট|ক্রয়|কেনা|কিনুন|মুছুন|মুছে ফেল|ডিলিট|শেয়ার|কল|অনুমতি|অনুমোদন|নিশ্চিত|ইনস্টল|অর্ডার|স্থানান্তর)")
    )

    fun evaluate(action: AgentActionProposal, observation: AgentObservation): AgentActionPolicyDecision {
        if (action.basedOnObservationId != observation.id) {
            return blocked("The proposal refers to a stale screen observation.")
        }
        if (action.rationale.isBlank() || action.rationale.length > 500) {
            return blocked("A short, non-empty action rationale is required.")
        }
        if (action.kind in manualOnlyKinds) {
            return manual("This action has an external or consequential effect and must be completed manually by the user.")
        }
        val target = action.target
        val visibleLabel = listOf(target?.label, target?.hintText, target?.resourceId, target?.role)
            .filterNotNull().joinToString(" ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("[_-]+"), " ")
        if (highImpactLabelPatterns.any { it.containsMatchIn(visibleLabel) }) {
            return manual("The visible control appears to perform a consequential action.")
        }

        when (action.kind) {
            AgentActionKind.OPEN_APP -> {
                if (action.packageName.isNullOrBlank() || !PACKAGE_NAME.matches(action.packageName)) {
                    return blocked("A valid Android package name is required.")
                }
                return auto("Launching an explicitly named app is reversible.")
            }
            AgentActionKind.OPEN_SETTINGS -> return auto("Opening a settings screen does not change a setting by itself.")
            AgentActionKind.SET_TORCH -> {
                if (action.torchEnabled == null) return blocked("Torch action requires an explicit on/off value.")
                return auto("Torch control is a reversible local device action.")
            }
            AgentActionKind.ADJUST_VOLUME -> {
                if (action.volumeStream == null || action.volumePercent !in 0..100) {
                    return blocked("Volume action requires an allowed stream and a percentage from 0 to 100.")
                }
                return auto("Volume control is bounded and reversible.")
            }
            AgentActionKind.SCROLL -> {
                val actionTarget = action.target ?: return blocked("A semantic scroll target is required.")
                if (!actionTarget.enabled || !actionTarget.visible || !actionTarget.scrollable) return blocked("The target is not a visible, enabled scroll container.")
                if (action.scrollDirection == null) return blocked("Scroll direction must be explicit.")
                if (!samePackage(actionTarget, observation)) return blocked("The scroll target belongs to a different app.")
                return auto("Scrolling is limited to the currently observed container.")
            }
            AgentActionKind.FOCUS_ELEMENT -> {
                val actionTarget = action.target ?: return blocked("A semantic focus target is required.")
                if (!actionTarget.enabled || !actionTarget.visible || !actionTarget.focusable) return blocked("The target is not a visible, enabled focus target.")
                if (!samePackage(actionTarget, observation)) return blocked("The focus target belongs to a different app.")
                return auto("Focusing does not submit or transmit data.")
            }
            AgentActionKind.CLICK_ELEMENT -> {
                val actionTarget = action.target ?: return blocked("A semantic click target is required.")
                if (!actionTarget.enabled || !actionTarget.visible || !actionTarget.clickable) return blocked("The target is not a visible, enabled clickable element.")
                if (actionTarget.label.isNullOrBlank() && actionTarget.hintText.isNullOrBlank() && actionTarget.resourceId.isNullOrBlank()) {
                    return blocked("The click target has no reviewable semantic label or resource ID.")
                }
                if (!samePackage(actionTarget, observation)) return blocked("The click target belongs to a different app.")
                return approval("A generic UI click requires review of the exact target before dispatch.")
            }
            AgentActionKind.TYPE_TEXT -> {
                val actionTarget = action.target ?: return blocked("A semantic text-field target is required.")
                if (!actionTarget.enabled || !actionTarget.visible || !actionTarget.editable) return blocked("The target is not a visible, enabled editable field.")
                if (actionTarget.label.isNullOrBlank() && actionTarget.hintText.isNullOrBlank() && actionTarget.resourceId.isNullOrBlank()) {
                    return blocked("The text field has no reviewable semantic label or resource ID.")
                }
                val passwordField = actionTarget.password || actionTarget.role.orEmpty().contains("password", ignoreCase = true) ||
                    actionTarget.resourceId.orEmpty().contains("password", ignoreCase = true)
                if (passwordField) return blocked("Password fields are never filled by the agent.")
                if (!samePackage(actionTarget, observation)) return blocked("The text field belongs to a different app.")
                if (action.text.isNullOrBlank() || action.text.length > MAX_TEXT_LENGTH) {
                    return blocked("Text is empty or exceeds the safe input limit.")
                }
                if (action.textOrigin == null || action.textOrigin == AgentTextOrigin.MODEL_GENERATED) {
                    return blocked("Only text supplied or explicitly confirmed by the user may be entered.")
                }
                return approval("Text entry requires the user to review the exact text and target field.")
            }
            AgentActionKind.OPEN_URL -> {
                val uri = runCatching { URI(action.url ?: "") }.getOrNull()
                if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                    return blocked("Only a well-formed HTTPS URL without embedded credentials is allowed.")
                }
                return approval("Opening an external website requires user review.")
            }
            AgentActionKind.SEND_MESSAGE,
            AgentActionKind.PUBLISH_CONTENT,
            AgentActionKind.SUBMIT_FORM,
            AgentActionKind.PURCHASE,
            AgentActionKind.MAKE_PAYMENT,
            AgentActionKind.DELETE_DATA,
            AgentActionKind.SHARE_DATA,
            AgentActionKind.PLACE_CALL,
            AgentActionKind.GRANT_PERMISSION,
            AgentActionKind.AUTHENTICATE,
            AgentActionKind.INSTALL_SOFTWARE -> return manual("This action must remain a user-performed step.")
            AgentActionKind.UNKNOWN -> return blocked("Unknown actions are not in the execution allow-list.")
        }
    }

    private fun samePackage(target: AgentTarget, observation: AgentObservation): Boolean =
        target.packageName.isNotBlank() && observation.packageName != null &&
            target.packageName.equals(observation.packageName, ignoreCase = true)

    private fun auto(reason: String) = AgentActionPolicyDecision(AgentActionDisposition.AUTO_ALLOWED, reason)
    private fun approval(reason: String) = AgentActionPolicyDecision(AgentActionDisposition.REQUIRES_APPROVAL, reason)
    private fun manual(reason: String) = AgentActionPolicyDecision(AgentActionDisposition.MANUAL_ONLY, reason)
    private fun blocked(reason: String) = AgentActionPolicyDecision(AgentActionDisposition.BLOCKED, reason)

    const val MAX_TEXT_LENGTH = 1000
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}

data class AgentPlanningInput(
    val goal: String,
    val observation: AgentObservation,
    val history: List<AgentHistoryEvent>
)

sealed interface AgentDecision {
    data class ProposeAction(val action: AgentActionProposal, val expectedOutcome: String) : AgentDecision
    data class Replan(val reason: String) : AgentDecision
    data class AskUser(val question: String) : AgentDecision
    data class Complete(val summary: String, val basedOnObservationId: String) : AgentDecision
}

interface AgentDecisionProvider {
    suspend fun decide(input: AgentPlanningInput): AgentDecision
}

interface AgentObservationProvider {
    suspend fun capture(): AgentObservation
}

interface AgentActionExecutor {
    /** Compatibility entry point. Production adapters should override the context-aware method. */
    suspend fun execute(action: AgentActionProposal): AgentExecutionReceipt

    /**
     * Carries the exact observation and approval that authorized this proposal.
     * The default keeps legacy test adapters source-compatible; production routing should
     * re-check policy and freshness at this boundary as well as in the runtime.
     */
    suspend fun execute(
        action: AgentActionProposal,
        observation: AgentObservation,
        approval: AgentApprovalReceipt?
    ): AgentExecutionReceipt = execute(action)
}

/** A one-time acknowledgement bound to one exact proposal and one observed UI state. */
data class AgentApprovalReceipt(
    val approvalId: String,
    val actionFingerprint: String,
    val observationId: String,
    val approvedAtEpochMs: Long
) {
    companion object {
        /** Create only in response to a fresh, explicit user approval event. */
        fun forExactAction(
            action: AgentActionProposal,
            observation: AgentObservation,
            approvedAtEpochMs: Long,
            approvalId: String
        ): AgentApprovalReceipt = AgentApprovalReceipt(
            approvalId = approvalId,
            actionFingerprint = AgentActionFingerprint.of(action),
            observationId = observation.id,
            approvedAtEpochMs = approvedAtEpochMs
        )
    }
}

/** Stable digest over every field shown to or relied on by the approval UI. */
object AgentActionFingerprint {
    fun of(action: AgentActionProposal): String {
        val target = action.target
        val values = listOf(
            action.kind.name,
            action.basedOnObservationId,
            target?.packageName,
            target?.label,
            target?.hintText,
            target?.resourceId,
            target?.role,
            target?.clickable?.toString(),
            target?.editable?.toString(),
            target?.password?.toString(),
            target?.scrollable?.toString(),
            target?.focusable?.toString(),
            target?.enabled?.toString(),
            target?.visible?.toString(),
            action.text,
            action.textOrigin?.name,
            action.url,
            action.packageName,
            action.torchEnabled?.toString(),
            action.volumeStream?.name,
            action.volumePercent?.toString(),
            action.scrollDirection?.name,
            action.rationale
        )
        // Length-prefix nullable fields to prevent delimiter and null/empty collisions.
        val canonical = buildString {
            values.forEach { value ->
                if (value == null) append("-1:")
                else append(value.length).append(':').append(value)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

interface AgentApprovalGate {
    /** Return null unless a human explicitly approved this exact action and observation. */
    suspend fun requestApproval(action: AgentActionProposal, observation: AgentObservation): AgentApprovalReceipt?
}

interface AgentOutcomeVerifier {
    suspend fun verify(
        expectedOutcome: String,
        before: AgentObservation,
        after: AgentObservation,
        receipt: AgentExecutionReceipt
    ): AgentVerification
}

/** accepted means the adapter accepted the dispatch request; it does not mean the task outcome was verified. */
data class AgentExecutionReceipt(
    val accepted: Boolean,
    val detail: String,
    val failure: AgentExecutionFailureKind? = null,
    val retryable: Boolean = false
)
data class AgentVerification(val verified: Boolean, val evidence: String)

enum class AgentHistoryStatus { REPLANNED, BLOCKED, MANUAL_REQUIRED, APPROVAL_DENIED, EXECUTED_VERIFIED, EXECUTED_UNVERIFIED }

data class AgentHistoryEvent(
    val actionKind: AgentActionKind?,
    val status: AgentHistoryStatus,
    val detail: String,
    val observationId: String?
)

enum class AgentRunStatus { COMPLETED, PAUSED_FOR_USER, CANCELLED, FAILED, LIMIT_REACHED, MANUAL_ACTION_REQUIRED }

data class AgentRunResult(
    val status: AgentRunStatus,
    val summary: String,
    val actionsDispatched: Int,
    val replans: Int,
    val history: List<AgentHistoryEvent>
)

fun interface AgentCancellation { fun isCancelled(): Boolean }
fun interface AgentClock { fun nowEpochMs(): Long }

data class AgentRuntimeConfig(
    val maxActionsPerRun: Int = 20,
    val maxReplans: Int = 3,
    val maxObservationAgeMs: Long = 10_000L,
    val maxApprovalAgeMs: Long = 60_000L
) {
    init {
        require(maxActionsPerRun in 1..20) { "maxActionsPerRun must be between 1 and 20." }
        require(maxReplans in 0..3) { "maxReplans must be between 0 and 3." }
        require(maxObservationAgeMs in 1_000L..30_000L) { "maxObservationAgeMs must be between 1s and 30s." }
        require(maxApprovalAgeMs in 5_000L..120_000L) { "maxApprovalAgeMs must be between 5s and 120s." }
    }
}

/**
 * One action is dispatched per fresh observation. After each dispatch, a new observation
 * and independent verification are required before the planner can propose another action.
 */
@Deprecated(
    message = "Legacy portable runtime retained for compatibility and policy smoke tests. " +
        "Production task coordination uses capability.CapabilityRegistry and TaskExecutionCoordinator."
)
class BoundedAgentRuntime(
    private val planner: AgentDecisionProvider,
    private val observer: AgentObservationProvider,
    private val executor: AgentActionExecutor,
    private val approvalGate: AgentApprovalGate,
    private val verifier: AgentOutcomeVerifier,
    private val config: AgentRuntimeConfig = AgentRuntimeConfig(),
    private val cancellation: AgentCancellation = AgentCancellation { false },
    private val clock: AgentClock = AgentClock { System.currentTimeMillis() }
) {
    // Prevent a receipt from being replayed across multiple runs using this runtime instance.
    // A persistent approval-gate adapter must additionally retain consumed IDs across process death.
    private val consumedApprovalIds = ConcurrentHashMap<String, Long>()

    suspend fun run(goal: String): AgentRunResult {
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty()) return result(AgentRunStatus.FAILED, "Task goal is empty.", 0, 0, emptyList())
        if (cleanGoal.length > MAX_GOAL_LENGTH) return result(AgentRunStatus.FAILED, "Task goal exceeds the safe length limit.", 0, 0, emptyList())

        val history = mutableListOf<AgentHistoryEvent>()
        var dispatched = 0
        var replans = 0

        fun replan(reason: String, kind: AgentActionKind? = null, observationId: String? = null): AgentRunResult? {
            history += AgentHistoryEvent(kind, AgentHistoryStatus.REPLANNED, reason.take(300), observationId)
            if (replans >= config.maxReplans) {
                return result(AgentRunStatus.FAILED, "Replan limit reached: $reason", dispatched, replans, history)
            }
            replans += 1
            return null
        }

        while (dispatched < config.maxActionsPerRun) {
            if (cancellation.isCancelled()) {
                return result(AgentRunStatus.CANCELLED, "Cancelled by the user.", dispatched, replans, history)
            }

            val observation = runCatching { observer.capture() }.getOrElse { error ->
                return result(AgentRunStatus.FAILED, "Screen observation failed: ${safeMessage(error)}", dispatched, replans, history)
            }
            if (!isFresh(observation)) {
                val stop = replan("The observation is stale or has an invalid timestamp.", observationId = observation.id)
                if (stop != null) return stop
                continue
            }

            val decision = runCatching {
                planner.decide(AgentPlanningInput(cleanGoal, observation, history.toList()))
            }.getOrElse { error ->
                return result(AgentRunStatus.FAILED, "Planning failed: ${safeMessage(error)}", dispatched, replans, history)
            }

            when (decision) {
                is AgentDecision.Complete -> {
                    if (decision.basedOnObservationId != observation.id || decision.summary.isBlank()) {
                        return result(AgentRunStatus.FAILED, "Completion claim was not grounded in the current observation.", dispatched, replans, history)
                    }
                    return result(AgentRunStatus.COMPLETED, decision.summary.take(500), dispatched, replans, history)
                }
                is AgentDecision.AskUser -> return result(AgentRunStatus.PAUSED_FOR_USER, decision.question.take(500), dispatched, replans, history)
                is AgentDecision.Replan -> {
                    val stop = replan(decision.reason, observationId = observation.id)
                    if (stop != null) return stop
                }
                is AgentDecision.ProposeAction -> {
                    val action = decision.action
                    val policy = AgentActionPolicy.evaluate(action, observation)
                    var approvalReceipt: AgentApprovalReceipt? = null
                    when (policy.disposition) {
                        AgentActionDisposition.BLOCKED -> {
                            history += AgentHistoryEvent(action.kind, AgentHistoryStatus.BLOCKED, policy.reason, observation.id)
                            return result(AgentRunStatus.FAILED, policy.reason, dispatched, replans, history)
                        }
                        AgentActionDisposition.MANUAL_ONLY -> {
                            history += AgentHistoryEvent(action.kind, AgentHistoryStatus.MANUAL_REQUIRED, policy.reason, observation.id)
                            return result(AgentRunStatus.MANUAL_ACTION_REQUIRED, policy.reason, dispatched, replans, history)
                        }
                        AgentActionDisposition.REQUIRES_APPROVAL -> {
                            val receipt = runCatching { approvalGate.requestApproval(action, observation) }.getOrNull()
                            if (receipt == null || !isValidApproval(receipt, action, observation) || !consumeApprovalId(receipt.approvalId)) {
                                history += AgentHistoryEvent(action.kind, AgentHistoryStatus.APPROVAL_DENIED, "No fresh, exact-action, one-time approval receipt was provided.", observation.id)
                                return result(AgentRunStatus.PAUSED_FOR_USER, "Action was not approved with a valid, current approval.", dispatched, replans, history)
                            }
                            approvalReceipt = receipt
                        }
                        AgentActionDisposition.AUTO_ALLOWED -> Unit
                    }

                    // Re-capture after any approval UI and before dispatch. The snapshot identity
                    // must remain unchanged; otherwise the approval is stale and cannot be reused.
                    val preDispatch = runCatching { observer.capture() }.getOrElse { error ->
                        return result(AgentRunStatus.FAILED, "Pre-dispatch observation failed: ${safeMessage(error)}", dispatched, replans, history)
                    }
                    if (!isFresh(preDispatch) || preDispatch.id != observation.id || preDispatch.packageName != observation.packageName) {
                        val stop = replan("The screen changed after planning/approval; no action was dispatched.", action.kind, observation.id)
                        if (stop != null) return stop
                        continue
                    }
                    if (cancellation.isCancelled()) {
                        return result(AgentRunStatus.CANCELLED, "Cancelled before dispatch; no action was run.", dispatched, replans, history)
                    }

                    val receipt = runCatching { executor.execute(action, observation, approvalReceipt) }.getOrElse { error ->
                        return result(AgentRunStatus.FAILED, "Action adapter failed: ${safeMessage(error)}", dispatched, replans, history)
                    }
                    dispatched += 1
                    if (receipt.failure in setOf(
                            AgentExecutionFailureKind.JOURNAL_FAILURE,
                            AgentExecutionFailureKind.IDEMPOTENCY_CONFLICT,
                            AgentExecutionFailureKind.DUPLICATE_DISPATCH,
                            AgentExecutionFailureKind.RECOVERY_REQUIRED
                        )) {
                        history += AgentHistoryEvent(
                            action.kind,
                            AgentHistoryStatus.EXECUTED_UNVERIFIED,
                            receipt.detail.take(300),
                            observation.id
                        )
                        return result(AgentRunStatus.FAILED, "Dispatch is held for recovery review: ${receipt.detail.take(300)}", dispatched, replans, history)
                    }
                    if (!receipt.accepted && !receipt.retryable) {
                        history += AgentHistoryEvent(action.kind, AgentHistoryStatus.EXECUTED_UNVERIFIED, receipt.detail.take(300), observation.id)
                        return result(AgentRunStatus.FAILED, "Capability adapter rejected the dispatch: ${receipt.detail.take(300)}", dispatched, replans, history)
                    }
                    val after = runCatching { observer.capture() }.getOrElse { error ->
                        history += AgentHistoryEvent(action.kind, AgentHistoryStatus.EXECUTED_UNVERIFIED, "Post-action observation failed: ${safeMessage(error)}", observation.id)
                        return result(AgentRunStatus.FAILED, "Action was dispatched but its result could not be observed.", dispatched, replans, history)
                    }
                    val verification = if (!isFresh(after)) {
                        AgentVerification(false, "The post-action observation was stale.")
                    } else runCatching {
                        verifier.verify(decision.expectedOutcome, observation, after, receipt)
                    }.getOrElse { error -> AgentVerification(false, "Verification failed: ${safeMessage(error)}") }

                    if (receipt.accepted && verification.verified) {
                        history += AgentHistoryEvent(action.kind, AgentHistoryStatus.EXECUTED_VERIFIED, verification.evidence.take(300), after.id)
                    } else {
                        val reason = listOf(receipt.detail, verification.evidence).filter { it.isNotBlank() }.joinToString(" | ")
                        history += AgentHistoryEvent(action.kind, AgentHistoryStatus.EXECUTED_UNVERIFIED, reason.take(300), after.id)
                        val stop = replan("The action outcome was not verified.", action.kind, after.id)
                        if (stop != null) return stop
                    }
                }
            }
        }
        return result(AgentRunStatus.LIMIT_REACHED, "Action limit reached; the agent stopped without continuing in the background.", dispatched, replans, history)
    }

    private fun consumeApprovalId(approvalId: String): Boolean {
        val now = clock.nowEpochMs()
        consumedApprovalIds.entries.removeIf { now - it.value > config.maxApprovalAgeMs }
        return consumedApprovalIds.putIfAbsent(approvalId, now) == null
    }

    private fun isValidApproval(
        receipt: AgentApprovalReceipt,
        action: AgentActionProposal,
        observation: AgentObservation
    ): Boolean {
        if (receipt.approvalId.isBlank() || receipt.approvalId.length > 128) return false
        if (receipt.observationId != observation.id) return false
        if (receipt.actionFingerprint != AgentActionFingerprint.of(action)) return false
        val age = clock.nowEpochMs() - receipt.approvedAtEpochMs
        return age in 0..config.maxApprovalAgeMs
    }

    private fun isFresh(observation: AgentObservation): Boolean {
        if (observation.id.isBlank() || observation.id.length > 160) return false
        val age = clock.nowEpochMs() - observation.capturedAtEpochMs
        return age in 0..config.maxObservationAgeMs
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "Unknown error").take(180)

    private fun result(
        status: AgentRunStatus,
        summary: String,
        dispatched: Int,
        replans: Int,
        history: List<AgentHistoryEvent>
    ) = AgentRunResult(status, summary, dispatched, replans, history.toList())

    companion object { const val MAX_GOAL_LENGTH = 2_000 }
}
