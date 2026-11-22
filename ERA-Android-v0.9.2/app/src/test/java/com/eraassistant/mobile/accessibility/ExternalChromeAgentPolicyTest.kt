package com.eraassistant.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalChromeAgentPolicyTest {
    @Test fun recognizesOnlyChromePackages() {
        assertTrue(ExternalChromeAgentPolicy.isSupportedPackage("com.android.chrome"))
        assertTrue(ExternalChromeAgentPolicy.isSupportedPackage("com.chrome.beta"))
        assertTrue(ExternalChromeAgentPolicy.isSupportedPackage("com.chrome.dev"))
        assertFalse(ExternalChromeAgentPolicy.isSupportedPackage("com.google.android.webview"))
        assertFalse(ExternalChromeAgentPolicy.isSupportedPackage("com.example.app"))
    }

    @Test fun omitsEditableValuesAndChromeAddressBarContent() {
        assertTrue(ExternalChromeAgentPolicy.shouldOmitNodeText(
            "com.android.chrome", editable = true, password = false,
            resourceId = "com.android.chrome:id/search_box_text", text = "private query"
        ))
        assertTrue(ExternalChromeAgentPolicy.shouldOmitNodeText(
            "com.android.chrome", editable = false, password = false,
            resourceId = "com.android.chrome:id/url_bar", text = "https://example.com/path?q=secret"
        ))
        assertTrue(ExternalChromeAgentPolicy.shouldOmitNodeText(
            "com.example.app", editable = false, password = true,
            resourceId = null, text = "secret"
        ))
    }

    @Test fun stripsUrlQueryAndFragmentFromChromeVisibleText() {
        assertEquals(
            "Open https://example.com/path",
            ExternalChromeAgentPolicy.sanitizeVisibleText(
                "com.android.chrome",
                "Open https://example.com/path?token=secret#section"
            )
        )
        assertEquals(
            "Open https://example.com/path",
            ExternalChromeAgentPolicy.sanitizeVisibleText(
                "com.android.chrome",
                "Open https://example.com/path#section"
            )
        )
    }

    @Test fun leavesNonChromeVisibleTextUnchanged() {
        assertEquals(
            "https://example.com/path?q=retained",
            ExternalChromeAgentPolicy.sanitizeVisibleText(
                "com.example.app", "https://example.com/path?q=retained"
            )
        )
    }
}
