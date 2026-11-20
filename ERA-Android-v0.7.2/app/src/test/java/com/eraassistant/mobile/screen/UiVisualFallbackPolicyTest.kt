package com.eraassistant.mobile.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class UiVisualFallbackPolicyTest {
    @Test fun sufficientSemanticTreeStaysInSemanticMode() {
        assertEquals(
            UiUnderstandingDecision.USE_SEMANTIC_UI,
            UiVisualFallbackPolicy.decide(UiUnderstandingInput(false, true, true))
        )
    }

    @Test fun missingOrInsufficientSemanticsAsksBeforeVisualCapture() {
        assertEquals(
            UiUnderstandingDecision.ASK_BEFORE_VISUAL_FALLBACK,
            UiVisualFallbackPolicy.decide(UiUnderstandingInput(false, true, false))
        )
        assertEquals(
            UiUnderstandingDecision.ASK_BEFORE_VISUAL_FALLBACK,
            UiVisualFallbackPolicy.decide(UiUnderstandingInput(false, false, false))
        )
    }

    @Test fun explicitVisualRequestSelectsVisualMode() {
        assertEquals(
            UiUnderstandingDecision.VISUAL_REQUESTED_BY_USER,
            UiVisualFallbackPolicy.decide(UiUnderstandingInput(true, true, true))
        )
    }
}
