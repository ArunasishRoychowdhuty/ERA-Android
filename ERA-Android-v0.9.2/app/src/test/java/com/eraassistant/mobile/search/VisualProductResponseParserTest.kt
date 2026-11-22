package com.eraassistant.mobile.search

import org.junit.Assert.*
import org.junit.Test

class VisualProductResponseParserTest {
    @Test fun parsesRecognizedProductAndBoundsConfidence() {
        val result = VisualProductResponseParser.parse(
            """{"productName":"Wireless Mouse","brand":"Logitech","model":"M185","category":"Computer accessory","confidence":0.92,"visualNotes":"Black compact mouse"}"""
        )
        assertEquals("Wireless Mouse", result.productName)
        assertEquals("Logitech Wireless Mouse M185", result.shoppingQuery)
        assertEquals(0.92, result.confidence, 0.001)
    }

    @Test fun clampsConfidenceAndToleratesUnknownBrand() {
        val result = VisualProductResponseParser.parse(
            """{"productName":"Desk Lamp","brand":"","model":"","category":"Lighting","confidence":9.0,"visualNotes":""}"""
        )
        assertEquals(1.0, result.confidence, 0.0)
        assertEquals("Desk Lamp", result.shoppingQuery)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingProductName() {
        VisualProductResponseParser.parse("""{"brand":"Unknown","confidence":0.2}""")
    }
}
