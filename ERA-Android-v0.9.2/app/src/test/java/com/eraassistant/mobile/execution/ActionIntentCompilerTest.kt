package com.eraassistant.mobile.execution

import com.eraassistant.mobile.capability.ActionRiskLevel
import com.eraassistant.mobile.capability.CapabilityId
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.core.KnownApp
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionIntentCompilerTest {
    private fun compile(command: AssistantCommand) = ActionIntentCompiler.compile(
        command,
        actionId = "action-test-1",
        idempotencyKey = "idem-test-1",
        createdAtEpochMs = 1_000L
    )

    @Test
    fun torchCommandCompilesToTypedLowRiskRequest() {
        val compiled = compile(AssistantCommand.SetTorch(true))!!
        assertEquals(CapabilityId.ANDROID_DEVICE, compiled.request.capabilityId)
        assertEquals("device.torch.set", compiled.request.actionName)
        assertEquals(true, compiled.request.parameters["enabled"])
        assertEquals(ActionRiskLevel.LOW, compiled.request.riskLevel)
        assertEquals(TaskRisk.LOW, compiled.plan.steps.single().risk)
        assertEquals(TaskCapability.DEVICE_CONTROL, compiled.plan.steps.single().requiredCapabilities.single())
        assertFalse(compiled.request.requiresUserApproval)
    }

    @Test
    fun appLaunchUsesOnlyAnExplicitKnownAppLabel() {
        val compiled = compile(AssistantCommand.LaunchApp(KnownApp.WHATSAPP))!!
        assertEquals("device.app.launch", compiled.request.actionName)
        assertEquals("WhatsApp", compiled.request.parameters["appName"])
        assertEquals(TaskCapability.APP_LAUNCH, compiled.plan.steps.single().requiredCapabilities.single())
    }

    @Test
    fun messageDraftIsTypedAndNeverAcknowlegesSending() {
        val compiled = compile(AssistantCommand.ShareTextToWhatsApp("Hello, this is a draft"))!!
        assertEquals("device.whatsapp.share_draft", compiled.request.actionName)
        assertEquals("Hello, this is a draft", compiled.request.parameters["message"])
        assertEquals(TaskCapability.MESSAGE_DRAFT, compiled.plan.steps.single().requiredCapabilities.single())
        assertTrue(compiled.displaySummary.contains("draft", ignoreCase = true))
    }

    @Test
    fun phoneNumberIsNormalizedAndDialerOnlyIsRequested() {
        val compiled = compile(AssistantCommand.DialNumber("+91 98765-43210"))!!
        assertEquals("device.dialer.open", compiled.request.actionName)
        assertEquals("+919876543210", compiled.request.parameters["phoneNumber"])
        assertEquals(ActionRiskLevel.MEDIUM, compiled.request.riskLevel)
        assertFalse(compiled.request.actionName.contains("call", ignoreCase = true))
    }

    @Test
    fun invalidOrTooLongPhoneNumbersAreRejected() {
        assertNull(compile(AssistantCommand.DialNumber("12")))
        assertNull(compile(AssistantCommand.DialNumber("++919876543210")))
        assertNull(compile(AssistantCommand.DialNumber("123456789012345678901")))
    }

    @Test
    fun websiteRequiresHttpsAndRejectsEmbeddedCredentials() {
        assertNull(compile(AssistantCommand.OpenWebsite("http://example.com")))
        assertNull(compile(AssistantCommand.OpenWebsite("https://user:pass@example.com")))
        val compiled = compile(AssistantCommand.OpenWebsite("https://example.com/path"))!!
        assertEquals("device.website.open", compiled.request.actionName)
        assertEquals("https://example.com/path", compiled.request.parameters["url"])
    }

    @Test
    fun searchAndMapQueriesAreBoundedAndTyped() {
        val search = compile(AssistantCommand.OpenWebSearch("ERA architecture"))!!
        assertEquals("device.web.search", search.request.actionName)
        val map = compile(AssistantCommand.OpenMapSearch("Ranaghat station"))!!
        assertEquals("device.maps.search", map.request.actionName)
        assertNull(compile(AssistantCommand.OpenWebSearch(" ".repeat(2))))
    }

    @Test
    fun unsupportedAndCompoundCommandsNeverProduceAnAction() {
        assertNull(compile(AssistantCommand.Help))
        assertNull(compile(AssistantCommand.UnsupportedComplexTask("send money")))
        assertNull(compile(AssistantCommand.RunSequence(listOf(AssistantCommand.VolumeUp))))
        assertNull(compile(AssistantCommand.CancelCurrentTask))
    }

    @Test
    fun eachCompiledRequestHasUniqueExecutionIdentityByDefault() {
        val first = ActionIntentCompiler.compile(AssistantCommand.VolumeUp)!!
        val second = ActionIntentCompiler.compile(AssistantCommand.VolumeUp)!!
        assertFalse(first.request.actionId == second.request.actionId)
        assertFalse(first.request.idempotencyKey == second.request.idempotencyKey)
    }
}
