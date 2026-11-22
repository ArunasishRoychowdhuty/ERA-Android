package com.eraassistant.mobile.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafStoragePolicyTest {
    @Test
    fun onlyExplicitTextMimeTypesArePreviewable() {
        assertTrue(SafStoragePolicy.isReadableTextMime("text/plain"))
        assertTrue(SafStoragePolicy.isReadableTextMime("application/json"))
        assertTrue(SafStoragePolicy.isReadableTextMime("text/markdown"))
        assertFalse(SafStoragePolicy.isReadableTextMime("image/png"))
        assertFalse(SafStoragePolicy.isReadableTextMime("application/pdf"))
        assertFalse(SafStoragePolicy.isReadableTextMime("application/octet-stream"))
    }

    @Test
    fun displayNamesDoNotExposePathSeparatorsOrControlCharacters() {
        assertEquals("..report.txt", SafStoragePolicy.safeDisplayName("../report.txt"))
        assertEquals("Unnamed document", SafStoragePolicy.safeDisplayName("\u0000\u0001"))
    }

    @Test
    fun previewIsBoundedAndReportsTruncation() {
        val source = "x".repeat(SafStoragePolicy.MAX_PREVIEW_CHARS + 25)
        val (preview, truncated) = SafStoragePolicy.preview(source)
        assertEquals(SafStoragePolicy.MAX_PREVIEW_CHARS, preview.length)
        assertTrue(truncated)
        val (small, smallTruncated) = SafStoragePolicy.preview("hello")
        assertEquals("hello", small)
        assertFalse(smallTruncated)
    }

    @Test
    fun documentReferencesAreBoundedAndRejectControlCharacters() {
        assertTrue(SafStoragePolicy.validateDocumentId("provider:id/child"))
        assertFalse(SafStoragePolicy.validateDocumentId(""))
        assertFalse(SafStoragePolicy.validateDocumentId("id\nforged"))
        assertFalse(SafStoragePolicy.validateDocumentId("x".repeat(1_025)))
    }
}
