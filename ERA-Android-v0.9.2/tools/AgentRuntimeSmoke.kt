import com.eraassistant.mobile.ai.*
import kotlin.coroutines.*

private fun <T> runSuspend(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { completed = result }
    })
    return completed!!.getOrThrow()
}

private class SequenceObserver(private val observations: MutableList<AgentObservation>) : AgentObservationProvider {
    override suspend fun capture(): AgentObservation = observations.removeAt(0)
}
private class SequencePlanner(private val decisions: MutableList<AgentDecision>) : AgentDecisionProvider {
    override suspend fun decide(input: AgentPlanningInput): AgentDecision = decisions.removeAt(0)
}
private class RecordingExecutor(private val accepted: Boolean = true) : AgentActionExecutor {
    var calls = 0
    override suspend fun execute(action: AgentActionProposal): AgentExecutionReceipt {
        calls += 1
        return AgentExecutionReceipt(accepted, if (accepted) "dispatched" else "adapter rejected")
    }
}
private class FixedApproval(private val approved: Boolean, private val approvedAt: Long = 100_100L) : AgentApprovalGate {
    var calls = 0
    override suspend fun requestApproval(action: AgentActionProposal, observation: AgentObservation): AgentApprovalReceipt? {
        calls += 1
        return if (approved) AgentApprovalReceipt.forExactAction(action, observation, approvedAt, "approval-$calls") else null
    }
}
private class CustomReceiptApproval(
    private val create: (AgentActionProposal, AgentObservation) -> AgentApprovalReceipt?
) : AgentApprovalGate {
    override suspend fun requestApproval(action: AgentActionProposal, observation: AgentObservation) = create(action, observation)
}
private class FixedVerifier(private val verified: Boolean = true) : AgentOutcomeVerifier {
    override suspend fun verify(expectedOutcome: String, before: AgentObservation, after: AgentObservation, receipt: AgentExecutionReceipt) =
        AgentVerification(verified, if (verified) "post-state observed" else "expected state not visible")
}
private fun obs(id: String, time: Long = 100_000L, pkg: String = "com.example") = AgentObservation(id, pkg, time, "test screen")
private fun action(kind: AgentActionKind, id: String, target: AgentTarget? = null, text: String? = null, origin: AgentTextOrigin? = null, pkg: String? = null, url: String? = null, torch: Boolean? = null, stream: AgentVolumeStream? = null, percent: Int? = null, scrollDirection: AgentScrollDirection? = null) =
    AgentActionProposal(kind, id, target, text, origin, url = url, packageName = pkg, torchEnabled = torch, volumeStream = stream, volumePercent = percent, scrollDirection = scrollDirection, rationale = "test action")

fun main() {
    var assertions = 0
    fun checkIt(value: Boolean, message: String) { check(value) { message }; assertions++ }
    val clock = AgentClock { 100_100L }

    // Typed capability routing claims a durable idempotency key before adapter execution.
    run {
        val ledger = InMemoryAgentDispatchLedger()
        val registry = AgentCapabilityRegistry(ledger, clock = clock)
        var calls = 0
        registry.register(object : AgentCapabilityAdapter {
            override val capabilityId = "android.device"
            override val supportedActions = setOf(AgentActionKind.SET_TORCH)
            override suspend fun execute(request: AgentDispatchRequest): AgentExecutionReceipt {
                calls += 1
                return AgentExecutionReceipt(true, "torch adapter accepted")
            }
        })
        checkIt(registry.supports(AgentActionKind.SET_TORCH), "registered action must be discoverable")
        checkIt(!registry.supports(AgentActionKind.OPEN_APP), "unregistered action must not be discoverable")
        val factory = SequentialAgentDispatchRequestFactory("run-registry-test")
        val observation = obs("s1")
        val request = factory.create(action(AgentActionKind.SET_TORCH, "s1", torch = true), observation)
        val first = runSuspend { registry.dispatch(request) }
        checkIt(first.accepted && first.failure == null && calls == 1, "first registered dispatch must reach its adapter")
        val duplicate = runSuspend { registry.dispatch(request) }
        checkIt(!duplicate.accepted && duplicate.failure == AgentExecutionFailureKind.DUPLICATE_DISPATCH && calls == 1,
            "completed idempotency key must block replay")
        val changedRequest = request.copy(action = request.action.copy(torchEnabled = false))
        val conflict = runSuspend { registry.dispatch(changedRequest) }
        checkIt(conflict.failure == AgentExecutionFailureKind.IDEMPOTENCY_CONFLICT && calls == 1,
            "same key bound to different action must be rejected")
        val stale = runSuspend { registry.dispatch(request.copy(observation = obs("another-screen"))) }
        checkIt(stale.failure == AgentExecutionFailureKind.INVALID_REQUEST && calls == 1,
            "dispatch must be bound to the proposal observation")
    }

    // An interrupted IN_PROGRESS dispatch is held for explicit recovery, not auto-replayed.
    run {
        val ledger = InMemoryAgentDispatchLedger()
        val registry = AgentCapabilityRegistry(ledger, clock = clock)
        var calls = 0
        registry.register(object : AgentCapabilityAdapter {
            override val capabilityId = "android.device"
            override val supportedActions = setOf(AgentActionKind.SET_TORCH)
            override suspend fun execute(request: AgentDispatchRequest): AgentExecutionReceipt {
                calls += 1
                return AgentExecutionReceipt(true, "should not execute")
            }
        })
        val observation = obs("s1")
        val proposal = action(AgentActionKind.SET_TORCH, "s1", torch = true)
        val key = "interrupted-key"
        checkIt(ledger.claim(key, AgentActionFingerprint.of(proposal)) == AgentDispatchClaim.Acquired,
            "in-progress test claim should be recorded")
        val held = runSuspend {
            registry.dispatch(AgentDispatchRequest("interrupted:1", key, observation, proposal))
        }
        checkIt(!held.accepted && held.failure == AgentExecutionFailureKind.RECOVERY_REQUIRED && calls == 0,
            "interrupted dispatch must require recovery and never auto-replay")
    }

    // Registry enforces manual-only effects and one-time approval independently of the runtime.
    run {
        val ledger = InMemoryAgentDispatchLedger()
        val registry = AgentCapabilityRegistry(ledger, clock = clock)
        var calls = 0
        registry.register(object : AgentCapabilityAdapter {
            override val capabilityId = "android.ui"
            override val supportedActions = setOf(AgentActionKind.CLICK_ELEMENT, AgentActionKind.SEND_MESSAGE)
            override suspend fun execute(request: AgentDispatchRequest): AgentExecutionReceipt {
                calls += 1
                return AgentExecutionReceipt(true, "UI adapter accepted")
            }
        })
        val observation = obs("approval-screen")
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val proposal = action(AgentActionKind.CLICK_ELEMENT, observation.id, target)
        val factory = SequentialAgentDispatchRequestFactory("approval-registry")
        val noApproval = runSuspend { registry.dispatch(factory.create(proposal, observation)) }
        checkIt(noApproval.failure == AgentExecutionFailureKind.APPROVAL_REQUIRED && calls == 0,
            "registry must reject an approval-requiring action without an approval receipt")

        val approval = AgentApprovalReceipt.forExactAction(proposal, observation, 100_100L, "single-use-approval")
        val authorized = runSuspend { registry.dispatch(factory.create(proposal, observation, approval)) }
        checkIt(authorized.accepted && calls == 1, "fresh exact-action approval must authorize a registered generic click")
        val approvalReplay = runSuspend { registry.dispatch(factory.create(proposal, observation, approval)) }
        checkIt(approvalReplay.failure == AgentExecutionFailureKind.APPROVAL_REQUIRED && calls == 1,
            "the same approval ID must not authorize a second dispatch")

        val manualProposal = action(AgentActionKind.SEND_MESSAGE, observation.id)
        val manual = runSuspend { registry.dispatch(factory.create(manualProposal, observation)) }
        checkIt(manual.failure == AgentExecutionFailureKind.MANUAL_ACTION_REQUIRED && calls == 1,
            "manual-only message sending must not reach any registered adapter")
    }

    // A reversible local action executes only after a fresh observation and is then verified.
    run {
        val executor = RecordingExecutor()
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(
                AgentDecision.ProposeAction(action(AgentActionKind.SET_TORCH, "s1", torch = true), "torch is enabled"),
                AgentDecision.Complete("done", "s2")
            )),
            SequenceObserver(mutableListOf(obs("s1"), obs("s1"), obs("s2"), obs("s2"))),
            executor, FixedApproval(false), FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Turn torch on") }
        checkIt(result.status == AgentRunStatus.COMPLETED && result.actionsDispatched == 1, "local action should complete")
        checkIt(executor.calls == 1, "local action should dispatch exactly once")
    }

    // Generic click requires approval; denied approvals never reach the executor.
    run {
        val executor = RecordingExecutor()
        val approval = FixedApproval(false)
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.ProposeAction(action(AgentActionKind.CLICK_ELEMENT, "s1", target), "details page opens"))),
            SequenceObserver(mutableListOf(obs("s1"))), executor, approval, FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Open details") }
        checkIt(result.status == AgentRunStatus.PAUSED_FOR_USER && executor.calls == 0 && approval.calls == 1, "click must be approved")
    }

    // A semantically consequential action is manual-only even if an approval adapter would say yes.
    run {
        val executor = RecordingExecutor()
        val approval = FixedApproval(true)
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.ProposeAction(action(AgentActionKind.PUBLISH_CONTENT, "s1"), "publish post"))),
            SequenceObserver(mutableListOf(obs("s1"))), executor, approval, FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Publish a post") }
        checkIt(result.status == AgentRunStatus.MANUAL_ACTION_REQUIRED && executor.calls == 0 && approval.calls == 0, "publish must remain manual")
    }

    // The screen is re-captured after approval; a changed UI invalidates the proposal.
    run {
        val executor = RecordingExecutor()
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.ProposeAction(action(AgentActionKind.CLICK_ELEMENT, "s1", target), "details opens"), AgentDecision.Complete("stopped safely", "s2"))),
            SequenceObserver(mutableListOf(obs("s1"), obs("s2"), obs("s2"))), executor, FixedApproval(true), FixedVerifier(),
            config = AgentRuntimeConfig(maxReplans = 1), clock = clock
        )
        val result = runSuspend { runtime.run("Open details") }
        checkIt(result.status == AgentRunStatus.COMPLETED && executor.calls == 0, "stale approval must not dispatch")
    }

    // Password and model-generated text entry are blocked.
    run {
        val password = AgentTarget("com.example", label = "Password", editable = true, password = true)
        val policy = AgentActionPolicy.evaluate(action(AgentActionKind.TYPE_TEXT, "s1", password, "abc", AgentTextOrigin.USER_PROVIDED), obs("s1"))
        checkIt(policy.disposition == AgentActionDisposition.BLOCKED, "password entry must be blocked")
        val field = AgentTarget("com.example", label = "Comment", editable = true)
        val modelText = AgentActionPolicy.evaluate(action(AgentActionKind.TYPE_TEXT, "s1", field, "generated text", AgentTextOrigin.MODEL_GENERATED), obs("s1"))
        checkIt(modelText.disposition == AgentActionDisposition.BLOCKED, "model-generated text must not be inserted")
    }

    // App launch can legitimately target a package different from the currently visible app.
    run {
        val launch = AgentActionPolicy.evaluate(action(AgentActionKind.OPEN_APP, "s1", pkg = "com.google.android.youtube"), obs("s1"))
        checkIt(launch.disposition == AgentActionDisposition.AUTO_ALLOWED, "launch target may differ from current package")
    }

    // Consequential button labels are manual-only, not merely review-required.
    run {
        val target = AgentTarget("com.example", label = "Send message", clickable = true)
        val policy = AgentActionPolicy.evaluate(action(AgentActionKind.CLICK_ELEMENT, "s1", target), obs("s1"))
        checkIt(policy.disposition == AgentActionDisposition.MANUAL_ONLY, "send control must be manual-only")
        val unlabeled = AgentTarget("com.example", clickable = true, role = "button")
        val noLabel = AgentActionPolicy.evaluate(action(AgentActionKind.CLICK_ELEMENT, "s1", unlabeled), obs("s1"))
        checkIt(noLabel.disposition == AgentActionDisposition.BLOCKED, "unlabeled click must be blocked")
        val idOnly = AgentTarget("com.example", resourceId = "com.example:id/btn_send_message", clickable = true)
        val byId = AgentActionPolicy.evaluate(action(AgentActionKind.CLICK_ELEMENT, "s1", idOnly), obs("s1"))
        checkIt(byId.disposition == AgentActionDisposition.MANUAL_ONLY, "send action must be caught from resource id too")
    }

    // Scroll actions must carry an explicit direction and target.
    run {
        val list = AgentTarget("com.example", label = "Results", scrollable = true)
        val missingDirection = AgentActionPolicy.evaluate(action(AgentActionKind.SCROLL, "s1", list), obs("s1"))
        checkIt(missingDirection.disposition == AgentActionDisposition.BLOCKED, "scroll without direction must be blocked")
        val directed = AgentActionPolicy.evaluate(action(AgentActionKind.SCROLL, "s1", list, scrollDirection = AgentScrollDirection.FORWARD), obs("s1"))
        checkIt(directed.disposition == AgentActionDisposition.AUTO_ALLOWED, "directed safe scroll may auto-run")
    }

    // User-origin text is never inserted without an approval checkpoint.
    run {
        val field = AgentTarget("com.example", label = "Comment", editable = true, role = "input")
        val policy = AgentActionPolicy.evaluate(action(AgentActionKind.TYPE_TEXT, "s1", field, "User supplied text", AgentTextOrigin.USER_PROVIDED), obs("s1"))
        checkIt(policy.disposition == AgentActionDisposition.REQUIRES_APPROVAL, "user text entry must require approval")
        val wrongPackage = AgentTarget("com.other", label = "Continue", clickable = true)
        val click = AgentActionPolicy.evaluate(action(AgentActionKind.CLICK_ELEMENT, "s1", wrongPackage), obs("s1"))
        checkIt(click.disposition == AgentActionDisposition.BLOCKED, "cross-app target must be blocked")
    }

    // Completion claims must point to the exact latest observation.
    run {
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.Complete("done", "old-snapshot"))),
            SequenceObserver(mutableListOf(obs("s1"))), RecordingExecutor(), FixedApproval(true), FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Complete safely") }
        checkIt(result.status == AgentRunStatus.FAILED, "stale completion claim must be rejected")
    }

    // Per-run action limit is a hard stop even when the planner could continue.
    run {
        val executor = RecordingExecutor()
        val localTorch = action(AgentActionKind.SET_TORCH, "s1", torch = true)
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.ProposeAction(localTorch, "torch on"))),
            SequenceObserver(mutableListOf(obs("s1"), obs("s1"), obs("s2"))), executor, FixedApproval(true), FixedVerifier(),
            config = AgentRuntimeConfig(maxActionsPerRun = 1), clock = clock
        )
        val result = runSuspend { runtime.run("Turn torch on") }
        checkIt(result.status == AgentRunStatus.LIMIT_REACHED && result.actionsDispatched == 1, "action cap must stop the run")
    }

    // Out-of-range volume values and malformed package names are blocked.
    run {
        val volume = AgentActionPolicy.evaluate(action(AgentActionKind.ADJUST_VOLUME, "s1", stream = AgentVolumeStream.MEDIA, percent = 120), obs("s1"))
        checkIt(volume.disposition == AgentActionDisposition.BLOCKED, "volume above 100 must be blocked")
        val app = AgentActionPolicy.evaluate(action(AgentActionKind.OPEN_APP, "s1", pkg = "com..bad"), obs("s1"))
        checkIt(app.disposition == AgentActionDisposition.BLOCKED, "malformed package name must be blocked")
    }

    // Only HTTPS URLs pass structural validation, and opening them still requires approval.
    run {
        val bad = AgentActionPolicy.evaluate(action(AgentActionKind.OPEN_URL, "s1", url = "javascript:alert(1)"), obs("s1"))
        checkIt(bad.disposition == AgentActionDisposition.BLOCKED, "non-HTTPS URL must be blocked")
        val good = AgentActionPolicy.evaluate(action(AgentActionKind.OPEN_URL, "s1", url = "https://example.com/path"), obs("s1"))
        checkIt(good.disposition == AgentActionDisposition.REQUIRES_APPROVAL, "HTTPS navigation must require review")
    }

    // Approval receipts are bound to the exact action and observation and expire quickly.
    run {
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val requested = action(AgentActionKind.CLICK_ELEMENT, "s1", target)
        val changed = requested.copy(rationale = "Open a different destination")
        val mismatched = AgentApprovalReceipt.forExactAction(changed, obs("s1"), 100_100L, "wrong-action")
        val executor = RecordingExecutor()
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.ProposeAction(requested, "details open"))),
            SequenceObserver(mutableListOf(obs("s1"))), executor,
            CustomReceiptApproval { _, _ -> mismatched }, FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Open details") }
        checkIt(result.status == AgentRunStatus.PAUSED_FOR_USER && executor.calls == 0, "approval for a different action must be rejected")
        checkIt(AgentActionFingerprint.of(requested) != AgentActionFingerprint.of(changed), "fingerprint must cover rationale and proposal details")
        checkIt(AgentActionFingerprint.of(requested).matches(Regex("[0-9a-f]{64}")), "action fingerprint must be a 256-bit lowercase hex digest")
    }

    run {
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val requested = action(AgentActionKind.CLICK_ELEMENT, "s1", target)
        val otherObservation = AgentApprovalReceipt.forExactAction(requested, obs("s2"), 100_100L, "wrong-screen")
        val executor = RecordingExecutor()
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(AgentDecision.ProposeAction(requested, "details open"))),
            SequenceObserver(mutableListOf(obs("s1"))), executor,
            CustomReceiptApproval { _, _ -> otherObservation }, FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Open details") }
        checkIt(result.status == AgentRunStatus.PAUSED_FOR_USER && executor.calls == 0, "approval bound to another observation must be rejected")
    }

    run {
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val requested = action(AgentActionKind.CLICK_ELEMENT, "s1", target)
        val expired = AgentApprovalReceipt.forExactAction(requested, obs("s1"), 20_000L, "expired")
        val future = AgentApprovalReceipt.forExactAction(requested, obs("s1"), 100_101L, "future")
        for ((label, approvalReceipt) in listOf("expired" to expired, "future" to future)) {
            val executor = RecordingExecutor()
            val runtime = BoundedAgentRuntime(
                SequencePlanner(mutableListOf(AgentDecision.ProposeAction(requested, "details open"))),
                SequenceObserver(mutableListOf(obs("s1"))), executor,
                CustomReceiptApproval { _, _ -> approvalReceipt }, FixedVerifier(), clock = clock
            )
            val result = runSuspend { runtime.run("Open details") }
            checkIt(result.status == AgentRunStatus.PAUSED_FOR_USER && executor.calls == 0, "$label approval receipt must be rejected")
        }
    }

    // A receipt ID cannot be replayed within a single run.
    run {
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val proposal = action(AgentActionKind.CLICK_ELEMENT, "s1", target)
        val replayed = AgentApprovalReceipt.forExactAction(proposal, obs("s1"), 100_100L, "same-id")
        val executor = RecordingExecutor()
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(
                AgentDecision.ProposeAction(proposal, "details open"),
                AgentDecision.ProposeAction(proposal, "details still open")
            )),
            SequenceObserver(mutableListOf(obs("s1"), obs("s1"), obs("s1"), obs("s1"), obs("s1"))),
            executor, CustomReceiptApproval { _, _ -> replayed }, FixedVerifier(), clock = clock
        )
        val result = runSuspend { runtime.run("Open details") }
        checkIt(result.status == AgentRunStatus.PAUSED_FOR_USER && executor.calls == 1, "replayed approval ID must not authorize a second action")
    }

    // The same receipt cannot be replayed in a later run on the same runtime instance.
    run {
        val target = AgentTarget("com.example", label = "Open details", clickable = true)
        val proposal = action(AgentActionKind.CLICK_ELEMENT, "s1", target)
        val executor = RecordingExecutor()
        val runtime = BoundedAgentRuntime(
            SequencePlanner(mutableListOf(
                AgentDecision.ProposeAction(proposal, "details open"),
                AgentDecision.ProposeAction(proposal, "details open")
            )),
            SequenceObserver(mutableListOf(obs("s1"), obs("s1"), obs("s1"), obs("s1"))),
            executor,
            CustomReceiptApproval { action, observation ->
                AgentApprovalReceipt.forExactAction(action, observation, 100_100L, "cross-run-replay")
            },
            FixedVerifier(), config = AgentRuntimeConfig(maxActionsPerRun = 1), clock = clock
        )
        val first = runSuspend { runtime.run("Open details") }
        val second = runSuspend { runtime.run("Open details") }
        checkIt(first.status == AgentRunStatus.LIMIT_REACHED && second.status == AgentRunStatus.PAUSED_FOR_USER && executor.calls == 1,
            "approval receipt must not replay across runs on the same runtime instance")
    }

    println("PASS: $assertions agent-runtime assertions (freshness, exact one-time approvals, manual-only effects, password-field rule, origin checks, URL policy)")
}
