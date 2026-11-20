package com.eraassistant.mobile.screen

/** Screen-space metadata is descriptive only; it is never used as an action selector. */
data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class UiElement(
    val nodeKey: String,
    val parentKey: String?,
    val resourceId: String?,
    val role: String,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val className: String?,
    val packageName: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val visible: Boolean,
    val link: Boolean,
    val actions: List<String>,
    val bounds: ScreenBounds,
    val children: List<UiElement> = emptyList()
) {
    val disabled: Boolean get() = !enabled
}

data class UiScreenSnapshot(
    val capturedAtEpochMs: Long,
    val packageName: String?,
    val windowTitle: String?,
    val root: UiElement,
    val nodeCount: Int,
    val truncated: Boolean
)

/** All supplied selector terms are ANDed. Bounds and nodeKey are intentionally not selectors. */
data class SemanticUiSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val packageName: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val enabled: Boolean? = null,
    val exactText: Boolean = true
) {
    fun matches(element: UiElement): Boolean {
        if (!resourceId.isNullOrBlank() && !element.resourceId.equals(resourceId, ignoreCase = true)) return false
        if (!text.isNullOrBlank()) {
            val actual = element.text.orEmpty()
            val match = if (exactText) actual.equals(text, ignoreCase = true) else actual.contains(text, ignoreCase = true)
            if (!match) return false
        }
        if (!contentDescription.isNullOrBlank() && !element.contentDescription.equals(contentDescription, ignoreCase = true)) return false
        if (!role.isNullOrBlank() && !element.role.equals(role, ignoreCase = true)) return false
        if (!packageName.isNullOrBlank() && !element.packageName.equals(packageName, ignoreCase = true)) return false
        if (clickable != null && element.clickable != clickable) return false
        if (editable != null && element.editable != editable) return false
        if (enabled != null && element.enabled != enabled) return false
        return true
    }
}

sealed interface SelectorResolution {
    data class Unique(val element: UiElement) : SelectorResolution
    data object NotFound : SelectorResolution
    data class Ambiguous(val matches: List<UiElement>) : SelectorResolution
}

object SemanticUiSelectorEngine {
    fun findAll(root: UiElement, selector: SemanticUiSelector): List<UiElement> = buildList {
        fun visit(element: UiElement) {
            if (selector.matches(element)) add(element)
            element.children.forEach(::visit)
        }
        visit(root)
    }

    fun resolve(root: UiElement, selector: SemanticUiSelector): SelectorResolution {
        val matches = findAll(root, selector)
        return when (matches.size) {
            0 -> SelectorResolution.NotFound
            1 -> SelectorResolution.Unique(matches.single())
            else -> SelectorResolution.Ambiguous(matches)
        }
    }
}

/** Stable-ish semantic identity; unlike nodeKey it survives many sibling insertions. */
internal fun UiElement.semanticIdentity(): String = listOf(
    packageName.orEmpty(),
    resourceId.orEmpty(),
    className.orEmpty(),
    role,
    nodeKey
).joinToString("|")
