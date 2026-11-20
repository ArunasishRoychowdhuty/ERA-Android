package com.eraassistant.mobile.screen

data class UiElementChange(
    val identity: String,
    val before: UiElement?,
    val after: UiElement?
)

data class UiSnapshotDiff(
    val added: List<UiElementChange>,
    val removed: List<UiElementChange>,
    val changed: List<UiElementChange>
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

object UiSnapshotDiffEngine {
    fun diff(before: UiElement, after: UiElement): UiSnapshotDiff {
        val old = flatten(before).associateBy { it.semanticIdentity() }
        val new = flatten(after).associateBy { it.semanticIdentity() }
        val added = new.keys.minus(old.keys).map { UiElementChange(it, null, new[it]) }
        val removed = old.keys.minus(new.keys).map { UiElementChange(it, old[it], null) }
        val changed = old.keys.intersect(new.keys).mapNotNull { key ->
            val left = old.getValue(key)
            val right = new.getValue(key)
            if (left.stateSignature() == right.stateSignature()) null
            else UiElementChange(key, left, right)
        }
        return UiSnapshotDiff(added, removed, changed)
    }

    private fun flatten(root: UiElement): List<UiElement> = buildList {
        fun visit(node: UiElement) {
            add(node)
            node.children.forEach(::visit)
        }
        visit(root)
    }

    private fun UiElement.stateSignature(): List<Any?> = listOf(
        text, contentDescription, hintText, clickable, longClickable, editable,
        focusable, focused, enabled, selected, checkable, checked, scrollable,
        password, visible, link, actions
    )
}
