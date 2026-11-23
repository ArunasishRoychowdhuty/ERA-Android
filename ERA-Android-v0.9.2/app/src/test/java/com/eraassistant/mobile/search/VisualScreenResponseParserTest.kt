package com.eraassistant.mobile.search
import org.junit.Assert.*
import org.junit.Test
class VisualScreenResponseParserTest {
    @Test fun parsesOnlyBoundedNormalizedVisibleControls() {
        val parsed = VisualScreenResponseParser.parse("""{"summary":"Settings page","controls":[{"label":"Continue","role":"button","region":{"left":0.1,"top":0.2,"right":0.4,"bottom":0.3},"confidence":0.97,"actionHint":"Next page"},{"label":"Password","role":"control","region":{"left":0.1,"top":0.5,"right":0.8,"bottom":0.6},"confidence":0.99}]}""")
        assertEquals("Settings page", parsed.summary)
        assertEquals(1, parsed.controls.size)
        assertEquals("Continue", parsed.controls.single().label)
        assertTrue(parsed.controls.single().confidence > 0.9)
    }
    @Test fun rejectsUnboundedRegionsAndUnsupportedRoles() {
        val parsed = VisualScreenResponseParser.parse("""{"controls":[{"label":"Outside","role":"button","region":{"left":-1,"top":0,"right":2,"bottom":1},"confidence":0.99},{"label":"Unknown","role":"script","region":{"left":0,"top":0,"right":1,"bottom":1},"confidence":0.99}]}""")
        assertTrue(parsed.controls.isEmpty())
    }
}
