import com.eraassistant.mobile.core.*
import com.eraassistant.mobile.planner.*
import com.eraassistant.mobile.tasks.*
import com.eraassistant.mobile.screen.*
import kotlin.coroutines.*

fun <T> runSuspend(block: suspend () -> T): T {
    var value: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { value = result }
    })
    return value!!.getOrThrow()
}

fun main() {
    val router = CommandRouter()
    check(router.route("টর্চ অন কর") == AssistantCommand.SetTorch(true))
    check(router.route("টর্চ বন্ধ কর") == AssistantCommand.SetTorch(false))
    val sequence = router.route("টর্চ অন করে YouTube খোলো") as AssistantCommand.RunSequence
    check(sequence.commands == listOf(AssistantCommand.SetTorch(true), AssistantCommand.LaunchApp(KnownApp.YOUTUBE)))
    check(router.route("volume up then open youtube") is AssistantCommand.RunSequence)
    val mixedSequence = router.route("torch on then volume up and open youtube") as AssistantCommand.RunSequence
    check(mixedSequence.commands.size == 3)
    check(router.route("search for cats and dogs") == AssistantCommand.OpenWebSearch("cats and dogs"))
    check(router.route("মাকে কল কর") == AssistantCommand.PickContactForDial)
    check(router.route("call mom") == AssistantCommand.PickContactForDial)
    check(router.route("কল কর ৯৮৭৬৫৪৩২১০") == AssistantCommand.DialNumber("9876543210"))
    check(router.route("রিংটোন ভলিউম বাড়াও") == AssistantCommand.AdjustVolume(VolumeStream.RINGTONE, VolumeDirection.UP))
    check(router.route("অ্যালার্ম ভলিউম ৩০%") == AssistantCommand.SetVolumePercent(VolumeStream.ALARM, 30))
    check(router.route("notification volume down") == AssistantCommand.AdjustVolume(VolumeStream.NOTIFICATION, VolumeDirection.DOWN))
    check(router.route("WhatsApp-এ Hi লিখে দাও") == AssistantCommand.ShareTextToWhatsApp("Hi"))
    check(router.route("WhatsApp খুলে সাহেবকে Hi লিখে দাও") is AssistantCommand.UnsupportedComplexTask)
    check(router.route("Instagram খুলে একটা পোস্ট কর") is AssistantCommand.UnsupportedComplexTask)
    check(router.route("ERA stop") == AssistantCommand.CancelCurrentTask)

    val whatsapp = runSuspend { PreviewPlanner().createPlan("WhatsApp খুলে সাহেবকে Hi লিখে দাও") }
    check(whatsapp.validate().isEmpty())
    check(whatsapp.steps.size == 6)
    check(whatsapp.steps.last().requiresUserApproval)
    check(whatsapp.steps.last().dependsOn == listOf(5))
    val coordinator = TaskExecutionCoordinator.from(whatsapp)
    check(coordinator.readySteps().map { it.step.id } == listOf("1"))
    check(coordinator.start("2") is TransitionResult.Rejected)
    coordinator.start("1")
    coordinator.readyForReview("1", "WhatsApp opened")
    coordinator.approve("1")
    coordinator.reportUserPerformed("1")
    check(coordinator.confirmUserOutcome("1", "I checked WhatsApp is visible.") is TransitionResult.Updated)
    check(coordinator.ledger.get("1")?.state == TaskStepState.USER_CONFIRMED)
    check(coordinator.readySteps().single().step.id == "2")

    val ledger = TaskExecutionLedger(listOf(AgentTaskStep("step", "Observe", "Visible", retryLimit = 1)))
    ledger.transition("step", TaskStepState.OBSERVING)
    check((ledger.recordFailure("step", "first") as TransitionResult.Updated).record.state == TaskStepState.NEEDS_REPLAN)
    ledger.transition("step", TaskStepState.OBSERVING, "Replanned")
    check((ledger.recordFailure("step", "second") as TransitionResult.Updated).record.state == TaskStepState.FAILED)

    val persistence = InMemoryTaskLedgerPersistence()
    val persistedSteps = listOf(AgentTaskStep("persist", "Inspect", "Current page"))
    val firstRun = TaskExecutionLedger(persistedSteps, persistence, "smoke-resume")
    firstRun.transition("persist", TaskStepState.OBSERVING)
    firstRun.transition("persist", TaskStepState.READY_FOR_REVIEW, "Ready for approval")
    val restoredRun = TaskExecutionLedger(persistedSteps, persistence, "smoke-resume")
    check(restoredRun.get("persist")?.state == TaskStepState.RECOVERY_REQUIRED)
    check(restoredRun.readySteps().isEmpty())
    check(restoredRun.confirmRecoveryInspection("persist", "Target inspected after restart.") is TransitionResult.Updated)
    check(restoredRun.readySteps().single().step.id == "persist")
    val inFlightStore = InMemoryTaskLedgerPersistence()
    val inFlight = TaskExecutionLedger(persistedSteps, inFlightStore, "in-flight")
    inFlight.transition("persist", TaskStepState.OBSERVING)
    val afterRestart = TaskExecutionLedger(persistedSteps, inFlightStore, "in-flight")
    check(afterRestart.get("persist")?.state == TaskStepState.RECOVERY_REQUIRED)
    check(afterRestart.readySteps().isEmpty())
    check(afterRestart.confirmRecoveryInspection("persist", "Target inspected after restart.") is TransitionResult.Updated)
    check(afterRestart.readySteps().single().step.id == "persist")

    val root = UiElement(
        nodeKey = "0", parentKey = null, resourceId = null, role = "container", text = null,
        contentDescription = null, hintText = null, className = "android.view.ViewGroup", packageName = "pkg",
        clickable = false, longClickable = false, editable = false, focusable = false, focused = false,
        enabled = true, selected = false, checkable = false, checked = false, scrollable = false,
        password = false, visible = true, link = false, actions = emptyList(), bounds = ScreenBounds(0,0,1,1),
        children = listOf(UiElement(
            nodeKey = "0.0", parentKey = "0", resourceId = "pkg:id/go", role = "button", text = "Go",
            contentDescription = null, hintText = null, className = "android.widget.Button", packageName = "pkg",
            clickable = true, longClickable = false, editable = false, focusable = true, focused = false,
            enabled = true, selected = false, checkable = false, checked = false, scrollable = false,
            password = false, visible = true, link = false, actions = listOf("click", "focus"), bounds = ScreenBounds(0,0,1,1)
        ))
    )
    check(UiActionProposalValidator.validate(root, UiActionProposal(SemanticUiSelector(text="Go", clickable=true), SemanticUiAction.CLICK, rationale="user")) is UiActionValidation.ReadyForUserReview)
    val unchangedSnapshotA = UiSnapshotFingerprint.of("pkg", "{\"root\":\"same\"}", 3, false)
    val unchangedSnapshotB = UiSnapshotFingerprint.of("pkg", "{\"root\":\"same\"}", 3, false)
    val changedSnapshot = UiSnapshotFingerprint.of("pkg", "{\"root\":\"changed\"}", 3, false)
    val otherPackageSnapshot = UiSnapshotFingerprint.of("other.pkg", "{\"root\":\"same\"}", 3, false)
    val changedNodeCount = UiSnapshotFingerprint.of("pkg", "{\"root\":\"same\"}", 4, false)
    val truncatedSnapshot = UiSnapshotFingerprint.of("pkg", "{\"root\":\"same\"}", 3, true)
    check(unchangedSnapshotA == unchangedSnapshotB)
    check(unchangedSnapshotA != changedSnapshot)
    check(unchangedSnapshotA != otherPackageSnapshot)
    check(unchangedSnapshotA.matches(Regex("[0-9a-f]{64}")))
    check(unchangedSnapshotA != changedNodeCount)
    check(unchangedSnapshotA != truncatedSnapshot)

    println("PASS: 36 smoke assertions covering routing, compound actions, contacts, volume, planning DAG, retries, semantic UI validation and snapshot-bound approval identity")
}
