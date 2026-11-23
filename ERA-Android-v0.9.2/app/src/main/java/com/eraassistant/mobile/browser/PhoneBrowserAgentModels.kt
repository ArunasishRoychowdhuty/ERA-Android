package com.eraassistant.mobile.browser

import com.eraassistant.mobile.screen.SensitiveInputPolicy

data class PhoneBrowserSession(
    val tab: Int,
    val targetId: String,
    val url: String,
    val title: String,
    val frameCount: Int,
    val active: Boolean = false,
    val loading: Boolean = false
)

data class PhoneBrowserNode(
    val ref: String,
    val tag: String,
    val role: String,
    val name: String,
    val clickable: Boolean,
    val editable: Boolean,
    val sensitive: Boolean,
    val visible: Boolean,
    val disabled: Boolean,
    val consequential: Boolean = false,
    val value: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val locator: List<String> = emptyList(),
    val frameUrl: String = "",
    val children: List<PhoneBrowserNode> = emptyList(),
    val parentRef: String = "",
    val depth: Int = 0,
    val description: String = "",
    val focusable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val expanded: Boolean? = null,
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val invalid: Boolean = false,
    val focused: Boolean = false,
    val level: Int? = null,
    val customDropdown: Boolean = false
)

data class PhoneBrowserSnapshot(
    val targetId: String,
    val url: String,
    val title: String,
    val nodeCount: Int,
    val complete: Boolean,
    val truncated: Boolean,
    val nodes: List<PhoneBrowserNode>,
    val accessibilitySummary: String,
    val includesPrivateDetails: Boolean = false,
    val snapshotId: String = "",
    val frameCount: Int = 1
)

data class PhoneBrowserAgentUiState(
    val addressDraft: String = "",
    val status: String = "ERA in-app browser ready.",
    val busy: Boolean = false,
    val sessions: List<PhoneBrowserSession> = emptyList(),
    val selectedTargetId: String = "era-webview-1",
    val snapshot: PhoneBrowserSnapshot? = null,
    val selectedRef: String = "",
    val fillText: String = "",
    val keyName: String = "ENTER",
    val actionStatus: String = ""
)

object PhoneBrowserAgentPolicy {
    private val sensitive = Regex(
        "(?i)(password|passcode|one.?time(?:.?password|.?code)|\\botp\\b|verification.?code|security.?code|api.?key|access.?token|secret|\\bpin\\b|\\bcvv\\b|\\bcvc\\b|card.?number|credential)"
    )
    private val consequential = Regex(
        "(?i)(send|publish|submit|delete|remove|purchase|checkout|place order|buy now|pay|payment|transfer|withdraw|sign in|log in|apply|confirm|authorize|subscribe|unsubscribe|cancel|পাঠাও|পাঠান|প্রকাশ|জমা|মুছ|পেমেন্ট|কেনা|অর্ডার)"
    )
    private val checkableRoles = setOf("checkbox", "radio", "switch")
    private val selectionRoles = setOf("combobox", "listbox")

    fun isSensitive(node: PhoneBrowserNode): Boolean = node.sensitive ||
        sensitive.containsMatchIn(node.name) ||
        sensitive.containsMatchIn(node.tag) ||
        sensitive.containsMatchIn(node.attributes["type"].orEmpty()) ||
        sensitive.containsMatchIn(node.attributes["autocomplete"].orEmpty()) ||
        SensitiveInputPolicy.isSensitiveField(
            label = node.name,
            resourceId = node.attributes["id"],
            hint = node.attributes["placeholder"],
            role = listOf(
                node.role, node.tag, node.attributes["type"].orEmpty(),
                node.attributes["autocomplete"].orEmpty()
            ).joinToString(" ")
        )

    /**
     * Last-mile rejection shared with Android Accessibility text entry.
     * A confirmation can never override this credential/sensitive-data block.
     */
    fun textEntryRejection(node: PhoneBrowserNode, payload: String): String? {
        if (isSensitive(node)) {
            return "Sensitive credential, verification or payment fields are blocked. Use the site's own sign-in or Android Autofill."
        }
        return SensitiveInputPolicy.rejectionReason(
            label = node.name,
            resourceId = node.attributes["id"],
            hint = node.attributes["placeholder"],
            role = listOf(node.role, node.tag, node.attributes["type"].orEmpty()).joinToString(" "),
            payload = payload
        )
    }

    fun isConsequential(node: PhoneBrowserNode): Boolean = node.consequential ||
        consequential.containsMatchIn(node.name) ||
        node.attributes["type"].equals("submit", ignoreCase = true)

    fun isCheckable(node: PhoneBrowserNode): Boolean =
        node.role.lowercase() in checkableRoles ||
            node.tag.equals("input", ignoreCase = true) &&
            node.attributes["type"].orEmpty().lowercase() in setOf("checkbox", "radio")

    fun canRead(node: PhoneBrowserNode): Boolean = node.visible

    fun canClick(node: PhoneBrowserNode): Boolean =
        node.visible && node.clickable && !node.disabled

    fun canFill(node: PhoneBrowserNode): Boolean =
        node.visible && node.editable && !node.disabled && !node.readOnly && !isSensitive(node)

    fun canPress(node: PhoneBrowserNode): Boolean =
        node.visible && !node.disabled && (node.focusable || node.clickable || node.editable)

    fun canCheck(node: PhoneBrowserNode): Boolean =
        node.visible && !node.disabled && !node.readOnly && isCheckable(node)

    fun canSelect(node: PhoneBrowserNode): Boolean =
        node.visible && !node.disabled && !node.readOnly &&
            (node.tag.equals("select", ignoreCase = true) ||
                node.customDropdown || node.role.lowercase() in selectionRoles)

    fun requiresConfirmation(node: PhoneBrowserNode, action: String): Boolean = when (action.lowercase()) {
        "click", "check", "uncheck", "select" -> isConsequential(node)
        "fill", "type" -> isSensitive(node)
        "read" -> isSensitive(node) || node.editable
        "press" -> true
        else -> false
    }
}
