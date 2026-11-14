package com.example

import com.example.data.PdfExtractor
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTutorUnitTest {

    @Test
    fun retrieveRelevantContext_prioritizesMathPageAndPreservesPageMarker() {
        val page1 = "--- Page 1 ---\n" + ("Introduction to world history and ancient trade routes. ".repeat(40))
        val page2 = "--- Page 2 ---\nQuadratic formula: x = (-b +- sqrt(b^2 - 4ac)) / (2a). Solve 2x^2 + 5x - 3 = 0."
        val page3 = "--- Page 3 ---\n" + ("Geography of rivers and mountains in East Africa. ".repeat(40))
        val fullText = """
            Document: math_and_notes.pdf
            Total Pages: 3

            $page1

            $page2

            $page3
        """.trimIndent()

        val context = PdfExtractor.retrieveRelevantContext(
            query = "Solve quadratic 2x^2 + 5x - 3 = 0 step by step",
            fullText = fullText,
            maxChars = 1500
        )

        assertTrue("Expected page 2 marker to be preserved", context.contains("--- Page 2 ---"))
        assertTrue("Expected quadratic equation to be retrieved", context.contains("2x^2 + 5x - 3 = 0"))
        assertTrue("Expected context length within maxChars", context.length <= 1500)
    }

    @Test
    fun retrieveRelevantContext_returnsFullTextWhenBelowLimit() {
        val fullText = "Document: algebra.pdf\nTotal Pages: 1\n\n--- Page 1 ---\n3x + 7 = 19"
        val result = PdfExtractor.retrieveRelevantContext("3x + 7", fullText, maxChars = 12000)
        assertTrue(result == fullText)
    }
}
