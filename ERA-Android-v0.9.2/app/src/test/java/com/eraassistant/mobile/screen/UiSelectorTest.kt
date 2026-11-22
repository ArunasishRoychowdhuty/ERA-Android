package com.eraassistant.mobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSelectorTest {
    private fun fixture(enabled: Boolean = true, duplicate: Boolean = false): UiElement {
        val first = UiElement(
            nodeKey = "0.0", parentKey = "0", resourceId = "com.example:id/send",
            role = "button", text = "Send", contentDescription = "Send message",
            hintText = null, className = "android.widget.Button", packageName = "com.example",
            clickable = true, longClickable = false, editable = false, focusable = true,
            focused = false, enabled = enabled, selected = false, checkable = false,
            checked = false, scrollable = false, password = false, visible = true, link = false,
            actions = listOf("click", "focus"), bounds = ScreenBounds(0, 0, 20, 20)
        )
        val second = if (duplicate) first.copy(nodeKey = "0.1", resourceId = "com.example:id/send2") else null
        return UiElement(
            nodeKey = "0", parentKey = null, resourceId = null, role = "container", text = null,
            contentDescription = null, hintText = null, className = "android.view.ViewGroup",
            packageName = "com.example", clickable = false, longClickable = false, editable = false,
            focusable = false, focused = false, enabled = true, selected = false, checkable = false,
            checked = false, scrollable = false, password = false, visible = true, link = false,
            actions = emptyList(), bounds = ScreenBounds(0, 0, 100, 100),
            children = listOfNotNull(first, second)
        )
    }

    @Test fun findsElementBySemanticProperties() {
        val result = SemanticUiSelectorEngine.resolve(
            fixture(), SemanticUiSelector(text = "Send", role = "button", clickable = true)
        )
        assertTrue(result is SelectorResolution.Unique)
        assertEquals("0.0", (result as SelectorResolution.Unique).element.nodeKey)
    }

    @Test fun rejectsAmbiguousMatch() {
        val result = SemanticUiSelectorEngine.resolve(
            fixture(duplicate = true), SemanticUiSelector(role = "button", clickable = true)
        )
        assertTrue(result is SelectorResolution.Ambiguous)
    }

    @Test fun actionValidatorRejectsDisabledControl() {
        val root = fixture(enabled = false)
        val result = UiActionProposalValidator.validate(
            root,
            UiActionProposal(SemanticUiSelector(resourceId = "com.example:id/send"), SemanticUiAction.CLICK, rationale = "User asked")
        )
        assertTrue(result is UiActionValidation.Rejected)
    }

    @Test fun actionValidatorOnlyProducesReviewableProposal() {
        val result = UiActionProposalValidator.validate(
            fixture(),
            UiActionProposal(SemanticUiSelector(text = "Send"), SemanticUiAction.CLICK, rationale = "User asked")
        )
        assertTrue(result is UiActionValidation.ReadyForUserReview)
    }

    @Test fun textEntryRejectsPasswordFields() {
        val secure = fixture().children.first().copy(password = true, editable = true, actions = listOf("set_text", "focus"))
        val root = fixture().copy(children = listOf(secure))
        val result = UiActionProposalValidator.validate(
            root,
            UiActionProposal(SemanticUiSelector(resourceId = "com.example:id/send"), SemanticUiAction.TYPE_TEXT, text = "sample", rationale = "Fill field")
        )
        assertTrue(result is UiActionValidation.Rejected)
    }

    @Test fun diffDetectsEnabledStateChange() {
        val before = fixture(enabled = true)
        val after = fixture(enabled = false)
        val diff = UiSnapshotDiffEngine.diff(before, after)
        assertEquals(1, diff.changed.size)
        assertFalse(diff.isEmpty)
    }

    @Test fun diffDetectsTextChangeAtSameNodePath() {
        val before = fixture()
        val changedButton = before.children.first().copy(text = "Continue")
        val after = before.copy(children = listOf(changedButton))
        val diff = UiSnapshotDiffEngine.diff(before, after)
        assertEquals(1, diff.changed.size)
        assertEquals("Send", diff.changed.single().before?.text)
        assertEquals("Continue", diff.changed.single().after?.text)
    }
    @Test fun staleReferenceResolvesUsingCurrentSemanticProperties() {
        val oldElement = fixture().children.first()
        val oldSnapshot = UiScreenSnapshot(1000L, "com.example", null, fixture(), 2, false)
        val reference = StaleUiElementGuard.from(oldSnapshot, oldElement)
        val currentTree = fixture().copy(children = listOf(oldElement.copy(nodeKey = "0.3")))
        val currentSnapshot = UiScreenSnapshot(2000L, "com.example", null, currentTree, 2, false)
        val result = StaleUiElementGuard.resolve(reference, currentSnapshot, nowEpochMs = 2500L)
        assertTrue(result is FreshElementResolution.Fresh)
        assertEquals("0.3", (result as FreshElementResolution.Fresh).element.nodeKey)
    }

    @Test fun staleReferenceRejectsWrongPackageAndOldTimestamp() {
        val oldSnapshot = UiScreenSnapshot(1000L, "com.example", null, fixture(), 2, false)
        val reference = StaleUiElementGuard.from(oldSnapshot, fixture().children.first())
        val wrongPackage = UiScreenSnapshot(2000L, "another.app", null, fixture(), 2, false)
        assertTrue(StaleUiElementGuard.resolve(reference, wrongPackage, nowEpochMs = 2500L) is FreshElementResolution.WrongPackage)
        assertTrue(StaleUiElementGuard.resolve(reference, oldSnapshot, nowEpochMs = 100_000L) is FreshElementResolution.Expired)
    }

}
