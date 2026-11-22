package com.eraassistant.mobile.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceApprovalEngineTest {
    private var now = 10_000L
    private var sequence = 0
    private val engine = VoiceApprovalEngine(
        clock = { now },
        idFactory = { "voice-approval-${++sequence}" }
    )

    private fun request(binding: String = "action|target|value") =
        engine.request(
            actionId = "browser.form.fill",
            actionSummary = "Fill the selected field",
            targetSummary = "Selected website field",
            binding = binding,
            ttlMs = 10_000L
        )

    @Test
    fun exactApprovedPhraseConsumesOnlyTheMatchingPendingRequest() {
        val prompt = request()
        val result = engine.consumeSpokenCommand("  Approved.  ", "action|target|value")
        assertEquals(VoiceApprovalResult.Approved(prompt.requestId), result)
        assertFalse(engine.isPending())
        assertEquals(VoiceApprovalResult.NoPending, engine.consumeSpokenCommand("Approved", "action|target|value"))
    }

    @Test
    fun unrelatedSpeechInvalidatesPendingApproval() {
        val prompt = request()
        assertEquals(
            VoiceApprovalResult.Unrecognized(prompt.requestId),
            engine.consumeSpokenCommand("Open another app", "action|target|value")
        )
        assertFalse(engine.isPending())
        assertEquals(VoiceApprovalResult.NoPending, engine.consumeSpokenCommand("Approved", "action|target|value"))
    }

    @Test
    fun cancellationConsumesPendingApproval() {
        val prompt = request()
        assertEquals(
            VoiceApprovalResult.Denied(prompt.requestId),
            engine.consumeSpokenCommand("Cancel!", "action|target|value")
        )
        assertFalse(engine.isPending())
    }

    @Test
    fun expiredApprovalCannotBeConsumed() {
        val prompt = request()
        now = prompt.expiresAtEpochMs + 1
        assertEquals(
            VoiceApprovalResult.Expired(prompt.requestId),
            engine.consumeSpokenCommand("Approved", "action|target|value")
        )
        assertFalse(engine.isPending())
    }

    @Test
    fun changedActionBindingInvalidatesApproval() {
        val prompt = request()
        assertEquals(
            VoiceApprovalResult.BindingChanged(prompt.requestId),
            engine.consumeSpokenCommand("Approved", "action|different-target|value")
        )
        assertFalse(engine.isPending())
    }

    @Test
    fun approvalMustBeAnExactPhrase() {
        request()
        assertEquals(
            VoiceApprovalResult.Unrecognized("voice-approval-1"),
            engine.consumeSpokenCommand("Approved, and submit it", "action|target|value")
        )
    }

    @Test
    fun bengaliApprovalAndDenialPhrasesAreRecognizedExactly() {
        assertTrue(VoiceApprovalEngine.isApprovalOrCancellationPhrase("অনুমোদিত।"))
        assertTrue(VoiceApprovalEngine.isApprovalOrCancellationPhrase("বাতিল"))
        assertFalse(VoiceApprovalEngine.isApprovalOrCancellationPhrase("হ্যাঁ, সব করে দাও"))
    }
}
