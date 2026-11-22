package com.eraassistant.mobile.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAgentContextObservationTest {
    private fun element(
        key: String,
        text: String,
        clickable: Boolean = true,
        children: List<UiElement> = emptyList()
    ) = UiElement(
        nodeKey = key,
        parentKey = null,
        resourceId = null,
        role = if (clickable) "button" else "text",
        text = text,
        contentDescription = null,
        hintText = null,
        className = "android.widget.Button",
        packageName = "com.example",
        clickable = clickable,
        longClickable = false,
        editable = false,
        focusable = false,
        focused = false,
        enabled = true,
        selected = false,
        checkable = false,
        checked = false,
        scrollable = false,
        password = false,
        visible = true,
        link = false,
        actions = if (clickable) listOf("click") else emptyList(),
        bounds = ScreenBounds(0, 0, 10, 10),
        children = children
    )

    @Test
    fun sufficientSemanticTreeIsSelectedWithoutVisualFallback() {
        val root = element(
            "0",
            "",
            clickable = false,
            children = listOf(
                element("0.0", "Search"),
                element("0.1", "Continue")
            )
        )
        val snapshot = UiTreeJson.encode(
            UiScreenSnapshot(1L, "com.example", "Home", root, 3, false)
        )

        val context = UiAgentContextBuilder.build("Tap Search", snapshot)

        assertTrue(context.contains("OBSERVATION DECISION: USE_SEMANTIC_UI"))
        assertTrue(context.contains("Use the semantic UI structure first"))
        assertFalse(context.contains("ask the user before visual capture"))
    }

    @Test
    fun sparseSemanticTreeRequiresUserVisualChoice() {
        val root = element("0", "", clickable = false, children = listOf(element("0.0", "Search")))
        val snapshot = UiTreeJson.encode(
            UiScreenSnapshot(1L, "com.example", "Home", root, 2, false)
        )

        val context = UiAgentContextBuilder.build("Find the image", snapshot)

        assertTrue(context.contains("OBSERVATION DECISION: ASK_BEFORE_VISUAL_FALLBACK"))
        assertTrue(context.contains("ask the user before visual capture"))
        assertTrue(context.contains("long-press the ERA overlay"))
    }

    @Test
    fun explicitVisualRequestIsMarkedAsUserRequested() {
        val root = element("0", "", clickable = false, children = listOf(element("0.0", "Search")))
        val snapshot = UiTreeJson.encode(
            UiScreenSnapshot(1L, "com.example", "Home", root, 2, false)
        )

        val context = UiAgentContextBuilder.build("Understand this picture", snapshot, explicitVisualRequest = true)

        assertTrue(context.contains("OBSERVATION DECISION: VISUAL_REQUESTED_BY_USER"))
        assertTrue(context.contains("The user explicitly requested visual understanding"))
    }
}
