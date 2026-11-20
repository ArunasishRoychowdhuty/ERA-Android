package com.eraassistant.mobile.contacts

import org.junit.Assert.*
import org.junit.Test

class CallConfirmationPolicyTest {
    @Test fun requiresUsableSelectedNumber() {
        assertNull(CallConfirmationPolicy.create("Test", null, 1000L))
        assertNull(CallConfirmationPolicy.create("Test", "12", 1000L))
    }
    @Test fun confirmationNormalizesBengaliDigitsAndExpires() {
        val pending = CallConfirmationPolicy.create("Ma", "+৯১ ৯৮৭৬৫৪৩২১০", 10_000L)!!
        assertEquals("+919876543210", pending.dialNumber)
        assertTrue(CallConfirmationPolicy.canConfirm(pending, 10_500L))
        assertFalse(CallConfirmationPolicy.canConfirm(pending, 140_001L))
    }
    @Test fun rejectsFutureCreatedPayload() {
        val pending = CallConfirmationPolicy.create("Friend", "9876543210", 20_000L)!!
        assertFalse(CallConfirmationPolicy.canConfirm(pending, 19_999L))
    }
}
