package com.eraassistant.mobile.orchestration

import com.eraassistant.mobile.capability.CapabilityAvailability
import com.eraassistant.mobile.capability.CapabilityId
import com.eraassistant.mobile.capability.CapabilityRegistry
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskStep

/**
 * Exact execution mapping between abstract planner capabilities and concrete actions.
 * A task step may be dispatched only when its declared capability contract permits
 * the exact adapter ID and action name supplied by the trusted local request builder.
 */
object TaskCapabilityActionPolicy {
    private val actionBindings: Map<TaskCapability, Set<Pair<CapabilityId, String>>> = mapOf(
        TaskCapability.APP_LAUNCH to setOf(CapabilityId.ANDROID_DEVICE to "device.app.launch"),
        TaskCapability.DEVICE_CONTROL to setOf(
            CapabilityId.ANDROID_DEVICE to "device.torch.set",
            CapabilityId.ANDROID_DEVICE to "device.volume.adjust",
            CapabilityId.ANDROID_DEVICE to "device.volume.set_percent",
            CapabilityId.ANDROID_DEVICE to "device.settings.open",
            CapabilityId.ANDROID_DEVICE to "device.camera.open"
        ),
        TaskCapability.DIALER_OPEN to setOf(CapabilityId.ANDROID_DEVICE to "device.dialer.open"),
        TaskCapability.MESSAGE_DRAFT to setOf(
            CapabilityId.ANDROID_DEVICE to "device.sms.create_draft",
            CapabilityId.ANDROID_DEVICE to "device.whatsapp.share_draft"
        ),
        TaskCapability.SCREEN_OBSERVATION to setOf(
            CapabilityId.ACCESSIBILITY_UI to "accessibility.screen.capture",
            CapabilityId.EXTERNAL_CHROME to "browser.chrome.inspect_visible_tree",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.snapshot",
            CapabilityId.VISUAL_INSPECTION to "screen.visual.inspect_frame"
        ),
        TaskCapability.STRUCTURED_UI_ACTION to setOf(
            CapabilityId.ACCESSIBILITY_UI to "accessibility.ui.approve_click",
            CapabilityId.ACCESSIBILITY_UI to "accessibility.ui.approve_type",
            CapabilityId.ACCESSIBILITY_UI to "accessibility.ui.approve_scroll",
            CapabilityId.EXTERNAL_CHROME to "browser.chrome.approve_click",
            CapabilityId.EXTERNAL_CHROME to "browser.chrome.approve_type",
            CapabilityId.EXTERNAL_CHROME to "browser.chrome.approve_scroll",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.click",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.fill",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.type",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.press_key",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.check",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.uncheck",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.select_option",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.scroll_into_view"
        ),
        TaskCapability.TEXT_ENTRY to setOf(
            CapabilityId.ACCESSIBILITY_UI to "accessibility.ui.approve_type",
            CapabilityId.EXTERNAL_CHROME to "browser.chrome.approve_type",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.fill",
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.type"
        ),
        TaskCapability.SHARE_DRAFT to setOf(
            CapabilityId.ANDROID_DEVICE to "device.sms.create_draft",
            CapabilityId.ANDROID_DEVICE to "device.whatsapp.share_draft"
        ),
        TaskCapability.WEB_NAVIGATION to setOf(
            CapabilityId.NATIVE_WEBVIEW to "browser.webview.navigate",
            CapabilityId.ANDROID_DEVICE to "device.app.launch",
            CapabilityId.ANDROID_DEVICE to "device.web.search",
            CapabilityId.ANDROID_DEVICE to "device.maps.search",
            CapabilityId.ANDROID_DEVICE to "device.website.open"
        ),
        TaskCapability.FILE_STORAGE to setOf(
            CapabilityId.FILE_STORAGE to "storage.tree.list",
            CapabilityId.FILE_STORAGE to "storage.document.read"
        )
    )

    fun allows(step: TaskStep, capabilityId: CapabilityId, actionName: String): Boolean {
        if (step.requiredCapabilities.isEmpty()) return true
        return step.requiredCapabilities.any { requirement ->
            (capabilityId to actionName) in actionBindings[requirement].orEmpty()
        }
    }

    fun mappedActions(requirement: TaskCapability): Set<Pair<CapabilityId, String>> =
        actionBindings[requirement].orEmpty()
}

/**
 * Validated Directed Acyclic Graph (DAG) for multi-step task execution.
 * Required planner capabilities are matched to exact registered capability IDs,
 * never fuzzy-matched against display names or descriptions.
 */
class TaskPlanDAG(val plan: TaskPlan) {
    private val stepsById = plan.steps.associateBy { it.id }
    private val inDegree = mutableMapOf<Int, Int>()
    private val adjacencyList = mutableMapOf<Int, MutableList<Int>>()

    init {
        val validationErrors = plan.validate()
        require(validationErrors.isEmpty()) {
            "Invalid TaskPlan in DAG: ${validationErrors.joinToString("; ")}"
        }
        plan.steps.forEach { step ->
            inDegree[step.id] = step.dependsOn.size
            step.dependsOn.forEach { parentId ->
                adjacencyList.getOrPut(parentId) { mutableListOf() }.add(step.id)
            }
        }
    }

    fun topologicalOrder(): List<TaskStep> {
        val workingInDegree = inDegree.toMutableMap()
        val queue = ArrayDeque<Int>()
        workingInDegree.filter { it.value == 0 }.keys.sorted().forEach(queue::add)
        val order = mutableListOf<Int>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            order += current
            adjacencyList[current].orEmpty().sorted().forEach { childId ->
                val remaining = (workingInDegree[childId] ?: 0) - 1
                workingInDegree[childId] = remaining
                if (remaining == 0) queue.add(childId)
            }
        }

        if (order.size != plan.steps.size) {
            val remaining = workingInDegree.filterValues { it > 0 }.keys
            throw IllegalStateException("TaskPlan DAG contains a circular dependency involving step IDs: $remaining")
        }
        return order.map { stepsById.getValue(it) }
    }

    fun getReadySteps(completedStepIds: Set<Int>): List<TaskStep> =
        plan.steps.filter { step ->
            step.id !in completedStepIds && step.dependsOn.all { it in completedStepIds }
        }

    /** Validates every required planner capability against exact registry identities. */
    fun validateCapabilitySupport(registry: CapabilityRegistry): List<String> {
        val descriptors = registry.allDescriptors().associateBy { it.id }
        val errors = mutableListOf<String>()
        plan.steps.forEach { step ->
            step.requiredCapabilities.forEach { requirement ->
                if (requirement in MANUAL_ONLY_CAPABILITIES) return@forEach
                val bindings = TaskCapabilityActionPolicy.mappedActions(requirement)
                val registeredAction = bindings.any { (id, action) ->
                    descriptors[id]?.supportedActions?.contains(action) == true
                }
                if (bindings.isEmpty()) {
                    errors += "Step ${step.id} ('${step.title}') has no execution adapter mapping for ${requirement.name}."
                } else if (!registeredAction) {
                    errors += "Step ${step.id} ('${step.title}') requires ${requirement.name}; none of its exact adapter/action bindings are registered."
                }
            }
        }
        return errors
    }

    /** Performs live availability checks for each required capability, not just registration checks. */
    suspend fun validateCapabilityAvailability(registry: CapabilityRegistry): List<String> {
        val supportErrors = validateCapabilitySupport(registry).toMutableList()
        if (supportErrors.isNotEmpty()) return supportErrors
        val available = registry.discoverAvailableCapabilities()
        plan.steps.forEach { step ->
            step.requiredCapabilities.forEach { requirement ->
                if (requirement in MANUAL_ONLY_CAPABILITIES) return@forEach
                val ids = acceptableCapabilityIds(requirement)
                val candidates = available.filterKeys { it.id in ids }
                if (candidates.none { it.value.isAvailable }) {
                    val reasons = candidates.values.mapNotNull(CapabilityAvailability::unavailableReason).distinct()
                    supportErrors += "Step ${step.id} ('${step.title}') requires ${requirement.name}, but its registered adapter(s) are unavailable: ${reasons.joinToString().ifBlank { "no available adapter" }}."
                }
            }
        }
        return supportErrors
    }

    fun step(id: Int): TaskStep? = stepsById[id]
    fun totalSteps(): Int = plan.steps.size

    private companion object {
        val MANUAL_ONLY_CAPABILITIES = setOf(
            TaskCapability.CONTACT_PICKER,
            TaskCapability.MEDIA_PICKER,
            TaskCapability.USER_CONFIRMATION
        )
    }

    private fun acceptableCapabilityIds(requirement: TaskCapability): Set<CapabilityId> = when (requirement) {
        TaskCapability.APP_LAUNCH,
        TaskCapability.DEVICE_CONTROL,
        TaskCapability.DIALER_OPEN,
        TaskCapability.MESSAGE_DRAFT -> setOf(CapabilityId.ANDROID_DEVICE)
        TaskCapability.SCREEN_OBSERVATION -> setOf(
            CapabilityId.ACCESSIBILITY_UI,
            CapabilityId.EXTERNAL_CHROME,
            CapabilityId.NATIVE_WEBVIEW,
            CapabilityId.VISUAL_INSPECTION
        )
        TaskCapability.CONTACT_PICKER -> emptySet() // Contact picker remains a dedicated, user-driven workflow.
        TaskCapability.STRUCTURED_UI_ACTION -> setOf(
            CapabilityId.ACCESSIBILITY_UI,
            CapabilityId.EXTERNAL_CHROME,
            CapabilityId.NATIVE_WEBVIEW
        )
        TaskCapability.TEXT_ENTRY -> setOf(
            CapabilityId.ACCESSIBILITY_UI,
            CapabilityId.EXTERNAL_CHROME,
            CapabilityId.NATIVE_WEBVIEW
        )
        TaskCapability.MEDIA_PICKER -> emptySet() // Android Photo Picker is a user-driven system flow, not a capability adapter.
        TaskCapability.SHARE_DRAFT -> setOf(CapabilityId.ANDROID_DEVICE)
        TaskCapability.USER_CONFIRMATION -> emptySet() // Explicit manual step; no adapter is required or allowed.
        TaskCapability.WEB_NAVIGATION -> setOf(CapabilityId.NATIVE_WEBVIEW, CapabilityId.ANDROID_DEVICE)
        TaskCapability.FILE_STORAGE -> setOf(CapabilityId.FILE_STORAGE)
    }
}
