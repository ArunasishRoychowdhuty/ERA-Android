package com.eraassistant.mobile.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SensitiveInputPolicyTest {
    @Test
    fun rejectsCredentialAndPaymentFieldLabels() {
        val labels = listOf(
            "Password",
            "One-time password",
            "OTP",
            "PIN",
            "CVV",
            "Credit card number",
            "Bank account",
            "UPI ID",
            "API key",
            "Access token",
            "পাসওয়ার্ড",
            "ওটিপি",
            "ইউপিআই"
        )
        labels.forEach { label ->
            assertNotNull("Expected sensitive field marker for $label",
                SensitiveInputPolicy.rejectionReason(label, null, null, null, "plain value"))
        }
    }

    @Test
    fun rejectsCredentialLikePayloadWithoutFieldMetadata() {
        val payloads = listOf(
            "password=hunter2",
            "Bearer abcdefghijklmnopqrstuvwxyz123",
            "api_key=REDACTED_SAMPLE_VALUE",
            "4111 1111 1111 1111",
            "student@oksbi",
            "user@okaxis"
        )
        payloads.forEach { payload ->
            assertNotNull("Expected credential-like value to be rejected",
                SensitiveInputPolicy.rejectionReason("Description", null, null, null, payload))
        }
    }

    @Test
    fun permitsOrdinaryNonCredentialText() {
        assertFalse(SensitiveInputPolicy.containsCredentialLikePayload("Please submit my assignment tomorrow."))
        assertFalse(SensitiveInputPolicy.containsCredentialLikePayload("Meeting at 5 PM in the library."))
        assertFalse(SensitiveInputPolicy.isSensitiveField("Search products"))
    }
}
