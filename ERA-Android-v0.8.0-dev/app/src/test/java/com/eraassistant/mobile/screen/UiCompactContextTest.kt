package com.eraassistant.mobile.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCompactContextTest {
    private fun baseElement(
        nodeKey: String,
        role: String,
        text: String? = null,
        hint: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        password: Boolean = false,
        children: List<UiElement> = emptyList()
    ) = UiElement(
        nodeKey = nodeKey,
        parentKey = null,
        resourceId = null,
        role = role,
        text = text,
        contentDescription = null,
        hintText = hint,
        className = "android.view.View",
        packageName = "com.example",
        clickable = clickable,
        longClickable = false,
        editable = editable,
        focusable = editable,
        focused = false,
        enabled = true,
        selected = false,
        checkable = false,
        checked = false,
        scrollable = false,
        password = password,
        visible = true,
        link = false,
        actions = if (editable) listOf("focus", "set_text") else if (clickable) listOf("click") else emptyList(),
        bounds = ScreenBounds(1, 2, 3, 4),
        children = children
    )

    @Test fun compactJsonOmitsEditableValuesAndPixelBounds() {
        val input = baseElement(
            "0",
            "container",
            children = listOf(
                baseElement("0.0", "input", text = "private@example.com", hint = "Email address", editable = true),
                baseElement("0.1", "button", text = "Continue", clickable = true)
            )
        )
        val compact = UiTreeJson.encodeCompact(UiScreenSnapshot(123L, "com.example", "Checkout", input, 3, false))

        assertTrue(compact.contains("Email address"))
        assertTrue(compact.contains("Continue"))
        assertTrue(compact.contains("valueOmitted"))
        assertFalse(compact.contains("private@example.com"))
        assertFalse(compact.contains("bounds"))
        assertFalse(compact.contains("\"nodeKey\""))
    }

    @Test fun passwordNodesAreExcludedFromCompactJson() {
        val input = baseElement(
            "0",
            "container",
            children = listOf(baseElement("0.0", "input", hint = "Password", editable = true, password = true))
        )
        val compact = UiTreeJson.encodeCompact(UiScreenSnapshot(123L, "com.example", null, input, 2, false))
        assertFalse(compact.contains("Password"))
        assertFalse(compact.contains("\"type\":\"input\""))
    }

    @Test fun contextBuilderUsesCompactJsonAndRequestsApprovalForVisualFallback() {
        val root = baseElement(
            "0",
            "container",
            children = listOf(baseElement("0.0", "button", text = "Continue", clickable = true))
        )
        val full = UiTreeJson.encode(UiScreenSnapshot(123L, "com.example", null, root, 2, false))
        val context = UiAgentContextBuilder.build("Continue", full)
        assertTrue(context.contains("era.ui-compact.v1"))
        assertTrue(context.contains("ask the user before visual capture"))
        assertFalse(context.contains("\"bounds\""))
    }
}
