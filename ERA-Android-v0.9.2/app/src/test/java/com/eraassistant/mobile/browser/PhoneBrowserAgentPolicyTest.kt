package com.eraassistant.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBrowserAgentPolicyTest {
    private fun node(
        name: String = "Search",
        tag: String = "button",
        role: String = "button",
        clickable: Boolean = false,
        editable: Boolean = false,
        sensitive: Boolean = false,
        visible: Boolean = true,
        disabled: Boolean = false,
        consequential: Boolean = false,
        readOnly: Boolean = false,
        focusable: Boolean = false,
        checked: Boolean? = null,
        customDropdown: Boolean = false,
        attributes: Map<String, String> = emptyMap()
    ) = PhoneBrowserNode(
        ref = "@s1:e1",
        tag = tag,
        role = role,
        name = name,
        clickable = clickable,
        editable = editable,
        sensitive = sensitive,
        visible = visible,
        disabled = disabled,
        consequential = consequential,
        readOnly = readOnly,
        focusable = focusable,
        checked = checked,
        customDropdown = customDropdown,
        attributes = attributes
    )

    @Test
    fun hiddenOrDisabledNodesCannotBeActedOn() {
        assertFalse(PhoneBrowserAgentPolicy.canRead(node(visible = false)))
        assertFalse(PhoneBrowserAgentPolicy.canClick(node(clickable = true, visible = false)))
        assertFalse(PhoneBrowserAgentPolicy.canClick(node(clickable = true, disabled = true)))
        assertFalse(PhoneBrowserAgentPolicy.canFill(node(editable = true, disabled = true)))
    }

    @Test
    fun ordinaryVisibleControlsCanBeClickedWithoutExtraConfirmation() {
        val target = node(clickable = true)
        assertTrue(PhoneBrowserAgentPolicy.canClick(target))
        assertFalse(PhoneBrowserAgentPolicy.requiresConfirmation(target, "click"))
    }

    @Test
    fun consequentialActionsRequireOneTimeConfirmation() {
        val send = node(name = "Send message", clickable = true)
        val submit = node(name = "Continue", clickable = true, consequential = true)
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(send, "click"))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(submit, "click"))
    }

    @Test
    fun sensitiveFieldsAreHardBlockedEvenWhenUserConfirmationExists() {
        val password = node(
            name = "Password",
            tag = "input",
            role = "textbox",
            editable = true,
            sensitive = true,
            attributes = mapOf("type" to "password")
        )
        assertFalse(PhoneBrowserAgentPolicy.canFill(password))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(password, "read"))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(password, "fill"))
        assertTrue(PhoneBrowserAgentPolicy.textEntryRejection(password, "not-a-secret").orEmpty().contains("blocked"))
    }

    @Test
    fun normalEditableFieldDoesNotRequireConfirmationForFillButReadIsPrivate() {
        val search = node(name = "Search", tag = "input", role = "searchbox", editable = true)
        assertTrue(PhoneBrowserAgentPolicy.canFill(search))
        assertFalse(PhoneBrowserAgentPolicy.requiresConfirmation(search, "fill"))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(search, "read"))
    }

    @Test
    fun submitTypeIsConsequentialEvenWithoutLabel() {
        val submit = node(
            name = "Continue",
            clickable = true,
            attributes = mapOf("type" to "submit")
        )
        assertTrue(PhoneBrowserAgentPolicy.isConsequential(submit))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(submit, "click"))
    }

    @Test
    fun readOnlyFieldsCannotBeFilled() {
        val field = node(name = "Account", tag = "input", role = "textbox", editable = true, readOnly = true)
        assertFalse(PhoneBrowserAgentPolicy.canFill(field))
    }

    @Test
    fun checkboxRadioAndSwitchExposeStateActions() {
        val checkbox = node(name = "Accept terms", tag = "input", role = "checkbox", checked = false,
            attributes = mapOf("type" to "checkbox"))
        val radio = node(name = "Option A", tag = "input", role = "radio", checked = true,
            attributes = mapOf("type" to "radio"))
        val switch = node(name = "Notifications", tag = "button", role = "switch", checked = false)
        assertTrue(PhoneBrowserAgentPolicy.canCheck(checkbox))
        assertTrue(PhoneBrowserAgentPolicy.canCheck(radio))
        assertTrue(PhoneBrowserAgentPolicy.canCheck(switch))
        assertFalse(PhoneBrowserAgentPolicy.canCheck(node(name = "Submit")))
    }

    @Test
    fun selectionSupportedForNativeAndCustomDropdowns() {
        val native = node(name = "Country", tag = "select", role = "combobox")
        val custom = node(name = "Country", role = "combobox", clickable = true, customDropdown = true)
        assertTrue(PhoneBrowserAgentPolicy.canSelect(native))
        assertTrue(PhoneBrowserAgentPolicy.canSelect(custom))
        assertFalse(PhoneBrowserAgentPolicy.canSelect(node(name = "Search", role = "searchbox")))
    }

    @Test
    fun consequentialDropdownSelectionRequiresConfirmation() {
        val dropdown = node(name = "Confirm payment", role = "combobox", customDropdown = true, consequential = true)
        assertTrue(PhoneBrowserAgentPolicy.canSelect(dropdown))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(dropdown, "select"))
    }

    @Test
    fun keyboardActionsRequireExplicitOneTimeConfirmation() {
        val textbox = node(name = "Search", tag = "input", role = "searchbox", editable = true, focusable = true)
        assertTrue(PhoneBrowserAgentPolicy.canPress(textbox))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(textbox, "press"))
    }

    @Test
    fun sensitiveAutocompleteFieldsAreRecognized() {
        val otp = node(name = "Code", tag = "input", role = "textbox", editable = true,
            attributes = mapOf("autocomplete" to "one-time-code"))
        assertTrue(PhoneBrowserAgentPolicy.isSensitive(otp))
        assertFalse(PhoneBrowserAgentPolicy.canFill(otp))
        assertTrue(PhoneBrowserAgentPolicy.requiresConfirmation(otp, "fill"))
    }

    @Test
    fun credentialLikePayloadIsBlockedEvenForAnOrdinarySearchField() {
        val search = node(name = "Search", tag = "input", role = "searchbox", editable = true)
        assertTrue(PhoneBrowserAgentPolicy.canFill(search))
        assertTrue(
            PhoneBrowserAgentPolicy.textEntryRejection(search, "Bearer abcdefghijklmnopqrstuvwxyz")
                .orEmpty().contains("credential")
        )
        assertTrue(
            PhoneBrowserAgentPolicy.textEntryRejection(search, "password=my-secret-value") != null
        )
        assertTrue(PhoneBrowserAgentPolicy.textEntryRejection(search, "find badminton shops") == null)
    }

    @Test
    fun shippingAddressIsNotMisclassifiedAsAPinField() {
        val shipping = node(name = "Shipping address", tag = "input", role = "textbox", editable = true)
        assertFalse(PhoneBrowserAgentPolicy.isSensitive(shipping))
        assertTrue(PhoneBrowserAgentPolicy.canFill(shipping))
    }
}
