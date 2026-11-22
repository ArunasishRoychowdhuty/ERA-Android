package com.eraassistant.mobile.social

import org.junit.Assert.*
import org.junit.Test

class InstagramPreparationPolicyTest {
    @Test fun requiresSelectedMedia() {
        assertFalse(InstagramPreparationPolicy.validate("Hello", "#era", null).valid)
    }
    @Test fun normalizesAndDeduplicatesHashtags() {
        assertEquals("#Era #Android_Dev", InstagramPreparationPolicy.normalizeHashtags("Era #era, Android_Dev"))
    }
    @Test fun createsCaptionOnlyDraft() {
        val result = InstagramPreparationPolicy.validate("A draft", "", "content://media/1")
        assertTrue(result.valid)
        assertEquals("A draft", result.shareText)
    }
    @Test fun createsCombinedDraftWithoutPublishing() {
        val result = InstagramPreparationPolicy.validate("A draft", "era assistant", "content://media/1")
        assertTrue(result.valid)
        assertEquals("A draft\\n\\n#era #assistant", result.shareText)
        assertTrue(result.message.contains("manual"))
    }
}
