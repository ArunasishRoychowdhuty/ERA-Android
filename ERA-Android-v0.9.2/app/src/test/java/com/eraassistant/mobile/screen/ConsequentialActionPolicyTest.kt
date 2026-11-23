package com.eraassistant.mobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsequentialActionPolicyTest {
    private fun element(
        key: String,
        text: String? = null,
        clickable: Boolean = false,
        children: List<UiElement> = emptyList()
    ) = UiElement(
        nodeKey = key,
        parentKey = null,
        resourceId = null,
        role = if (clickable) "button" else "text",
        text = text,
        contentDescription = null,
        hintText = null,
        className = if (clickable) "android.widget.Button" else "android.widget.TextView",
        packageName = "com.example.wallet",
        clickable = clickable,
        longClickable = false,
        editable = false,
        focusable = clickable,
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
        bounds = ScreenBounds(0, 0, 100, 50),
        children = children
    )

    @Test
    fun recognizesConsequentialActionsButNotOrdinaryButtons() {
        assertEquals(ConsequentialActionKind.PAYMENT, ConsequentialActionPolicy.classify(element("1", "Pay now", true)))
        assertEquals(ConsequentialActionKind.SUBMIT, ConsequentialActionPolicy.classify(element("2", "Submit form", true)))
        assertEquals(ConsequentialActionKind.DELETE, ConsequentialActionPolicy.classify(element("3", "Delete", true)))
        assertEquals(ConsequentialActionKind.PUBLISH, ConsequentialActionPolicy.classify(element("4", "Publish", true)))
        assertNull(ConsequentialActionPolicy.classify(element("5", "Next", true)))
        assertNull(ConsequentialActionPolicy.classify(element("6", "Pay now", false)))
    }

    @Test
    fun paymentSummaryRequiresAVisibleCurrencyAmount() {
        val payButton = element("button", "Pay now", true)
        val rootWithoutAmount = element("root", children = listOf(payButton))
        val missingAmount = UiScreenSnapshot(10L, "com.example.wallet", null, rootWithoutAmount, 2, false)
        assertNull(ConsequentialActionPolicy.buildSummary(ConsequentialActionKind.PAYMENT, missingAmount, payButton))
    }

    @Test
    fun paymentSummaryContainsVisibleAmountAndTargetButNotEditableValues() {
        val payButton = element("button", "Pay now", true)
        val amount = element("amount", "Total ₹1,249.00")
        val root = element("root", children = listOf(amount, payButton))
        val snapshot = UiScreenSnapshot(10L, "com.example.wallet", null, root, 3, false)
        val summary = ConsequentialActionPolicy.buildSummary(ConsequentialActionKind.PAYMENT, snapshot, payButton)
        assertTrue(summary!!.contains("₹1,249.00"))
        assertTrue(summary.contains("Pay now"))
        assertTrue(summary.contains("wallet"))
    }

    @Test
    fun paymentSummaryNeverReadsEditableFieldValues() {
        val payButton = element("button", "Pay now", true)
        val editableAmount = element("input", "₹7,777.00").copy(editable = true)
        val root = element("root", children = listOf(editableAmount, payButton))
        val snapshot = UiScreenSnapshot(10L, "com.example.wallet", null, root, 3, false)
        assertNull(ConsequentialActionPolicy.buildSummary(ConsequentialActionKind.PAYMENT, snapshot, payButton))
    }
}
