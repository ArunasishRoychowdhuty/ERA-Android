package com.eraassistant.mobile.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntegrationRoutePolicyTest {
    @Test
    fun officialApiIsPreferredOverUiAutomation() {
        val result = IntegrationRoutePolicy.choose(
            listOf(
                IntegrationCandidate(IntegrationTransport.ACCESSIBILITY_TREE, true),
                IntegrationCandidate(IntegrationTransport.BROWSER_DOM, true),
                IntegrationCandidate(IntegrationTransport.OFFICIAL_API, true)
            ),
            IntegrationRouteContext(androidApiLevel = 35, screenInspectionConsented = true)
        )
        assertEquals(IntegrationTransport.OFFICIAL_API, result.selected)
    }

    @Test
    fun appFunctionsAreSkippedBeforeAndroid16AndIntentIsUsedInstead() {
        val result = IntegrationRoutePolicy.choose(
            listOf(
                IntegrationCandidate(IntegrationTransport.APP_FUNCTIONS, true),
                IntegrationCandidate(IntegrationTransport.ANDROID_INTENT, true)
            ),
            IntegrationRouteContext(androidApiLevel = 35)
        )
        assertEquals(IntegrationTransport.ANDROID_INTENT, result.selected)
        assertEquals(
            "AppFunctions requires API 36+, or API 34+ with the App Function Extension available.",
            result.rejected[IntegrationTransport.APP_FUNCTIONS]
        )
    }

    @Test
    fun appFunctionsCanBeSelectedOnApi35OnlyWhenExtensionSupportIsConfirmed() {
        val result = IntegrationRoutePolicy.choose(
            listOf(IntegrationCandidate(IntegrationTransport.APP_FUNCTIONS, true)),
            IntegrationRouteContext(
                androidApiLevel = 35,
                appFunctionsExtensionAvailable = true
            )
        )
        assertEquals(IntegrationTransport.APP_FUNCTIONS, result.selected)
    }

    @Test
    fun accessibilityIsNotSelectedWithoutScreenInspectionConsent() {
        val result = IntegrationRoutePolicy.choose(
            listOf(IntegrationCandidate(IntegrationTransport.ACCESSIBILITY_TREE, true)),
            IntegrationRouteContext(androidApiLevel = 35)
        )
        assertNull(result.selected)
        assertEquals(
            "Screen-inspection consent is not active.",
            result.rejected[IntegrationTransport.ACCESSIBILITY_TREE]
        )
    }

    @Test
    fun screenshotGestureRequiresFreshFrameHighConfidenceAndExactApproval() {
        val candidate = IntegrationCandidate(
            transport = IntegrationTransport.SCREENSHOT_GESTURE,
            available = true,
            confidence = 0.97
        )
        val noApproval = IntegrationRoutePolicy.choose(
            listOf(candidate),
            IntegrationRouteContext(
                androidApiLevel = 35,
                screenInspectionConsented = true,
                explicitVisualCaptureRequested = true,
                visualObservationFresh = true
            )
        )
        assertNull(noApproval.selected)

        val approved = IntegrationRoutePolicy.choose(
            listOf(candidate),
            IntegrationRouteContext(
                androidApiLevel = 35,
                screenInspectionConsented = true,
                explicitVisualCaptureRequested = true,
                visualActionApproved = true,
                visualObservationFresh = true
            )
        )
        assertEquals(IntegrationTransport.SCREENSHOT_GESTURE, approved.selected)
    }

    @Test
    fun staleOrLowConfidenceVisualTargetsAreRejected() {
        val stale = IntegrationRoutePolicy.choose(
            listOf(IntegrationCandidate(IntegrationTransport.SCREENSHOT_GESTURE, true, confidence = 0.99)),
            IntegrationRouteContext(35, true, true, true, false)
        )
        assertNull(stale.selected)

        val lowConfidence = IntegrationRoutePolicy.choose(
            listOf(IntegrationCandidate(IntegrationTransport.SCREENSHOT_GESTURE, true, confidence = 0.70)),
            IntegrationRouteContext(35, true, true, true, true)
        )
        assertNull(lowConfidence.selected)
    }
}
