package com.eraassistant.mobile.observation

import org.junit.Assert.*
import org.junit.Test

class NormalizedObservationBoundsTest {
    private fun observation(elements: List<NormalizedElement>) = NormalizedObservation(
        observationId = "obs-test",
        source = ObservationSource.SYNTHETIC_TEST,
        capturedAtEpochMs = System.currentTimeMillis(),
        elements = elements
    )

    @Test
    fun elementFieldsHaveIndividualSizeLimits() {
        var rejected = false
        try {
            NormalizedElement("@e1", "button", label = "x".repeat(NormalizedElement.MAX_TEXT_LENGTH + 1))
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun aggregateObservationPayloadIsBounded() {
        val elements = (0 until 150).map { index ->
            NormalizedElement("@e$index", "button", label = "x".repeat(NormalizedElement.MAX_TEXT_LENGTH))
        }
        var rejected = false
        try { observation(elements) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun aggregateBudgetTrimsElementsAndCanBuildMarkedTruncatedSnapshot() {
        val input = (0 until 200).map { index ->
            NormalizedElement("@e$index", "button", label = "x".repeat(400))
        }
        val summary = "Large screen"
        val kept = NormalizedObservation.boundElements(input, summary)
        assertTrue(kept.size < input.size)
        val snapshot = NormalizedObservation(
            observationId = "bounded-observation",
            source = ObservationSource.SYNTHETIC_TEST,
            capturedAtEpochMs = System.currentTimeMillis(),
            summary = summary,
            elements = kept,
            totalElementsCount = input.size,
            isTruncated = kept.size < input.size
        )
        assertTrue(snapshot.isTruncated)
        assertTrue(snapshot.elements.size < snapshot.totalElementsCount)
    }

    @Test
    fun plannerContextClampsCallerRequestedNodeCount() {
        val value = observation(listOf(
            NormalizedElement("@e1", "button", label = "Open dashboard", isClickable = true)
        )).toPlannerContext(maxNodes = Int.MAX_VALUE)
        assertTrue(value.contains("Open dashboard"))
    }
}
