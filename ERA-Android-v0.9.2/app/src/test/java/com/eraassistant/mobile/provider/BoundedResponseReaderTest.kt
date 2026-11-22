package com.eraassistant.mobile.provider

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BoundedResponseReaderTest {
    @Test fun acceptsResponseAtExactLimit() {
        val result = BoundedResponseReader.readUtf8(ByteArrayInputStream("abc".toByteArray()), 3)
        assertEquals("abc", result)
    }

    @Test fun rejectsResponseAboveLimitWithoutReadingWholeBody() {
        try {
            BoundedResponseReader.readUtf8(ByteArrayInputStream("abcdefghij".toByteArray()), 3)
            fail("Expected an oversized response to be rejected.")
        } catch (expected: IOException) {
            assertEquals("Provider response exceeded the 3 character limit.", expected.message)
        }
    }

    @Test fun countsDecodedUnicodeCharacters() {
        val source = "কথা"
        val result = BoundedResponseReader.readUtf8(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)), 3)
        assertEquals(source, result)
    }
}
