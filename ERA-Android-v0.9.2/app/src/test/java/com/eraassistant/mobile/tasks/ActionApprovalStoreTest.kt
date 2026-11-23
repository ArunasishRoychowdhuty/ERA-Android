package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.capability.*
import org.junit.Assert.*
import org.junit.Test

class ActionApprovalStoreTest {
    private fun request(text: String = "confirm") = StructuredActionRequest(
        actionId = "action-1",
        idempotencyKey = "idem-1",
        capabilityId = CapabilityId.NATIVE_WEBVIEW,
        actionName = "browser.webview.click",
        parameters = mapOf("ref" to "@e2", "note" to text),
        targetObservationId = "obs-fresh",
        riskLevel = ActionRiskLevel.HIGH,
        requiresUserApproval = true
    )

    @Test
    fun receiptIsBoundToStepRequestAndObservationAndCanBeConsumedOnce() {
        var now = 10_000L
        val store = ActionApprovalStore(clock = { now })
        val req = request()
        val token = store.issue("step-4", req, "obs-fresh")

        // The pre-dispatch gate may inspect the binding, but only durable dispatch consumes it.
        assertTrue(store.matches(token, "step-4", req, "obs-fresh"))
        assertTrue(store.matches(token, "step-4", req, "obs-fresh"))
        assertTrue(store.consume(token, "step-4", req, "obs-fresh"))
        assertFalse(store.matches(token, "step-4", req, "obs-fresh"))
        assertFalse(store.consume(token, "step-4", req, "obs-fresh"))

        val changedRequestToken = store.issue("step-4", req, "obs-fresh")
        assertFalse(store.consume(changedRequestToken, "step-4", request(text = "changed"), "obs-fresh"))

        val wrongStepToken = store.issue("step-4", req, "obs-fresh")
        assertFalse(store.consume(wrongStepToken, "step-5", req, "obs-fresh"))

        val wrongObservationToken = store.issue("step-4", req, "obs-fresh")
        assertFalse(store.consume(wrongObservationToken, "step-4", req, "obs-after-change"))

        val expiredToken = store.issue("step-4", req, "obs-fresh")
        now += 60_001L
        assertFalse(store.consume(expiredToken, "step-4", req, "obs-fresh"))
    }
}
