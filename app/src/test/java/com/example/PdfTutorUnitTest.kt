package com.example

import com.example.data.PdfExtractor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.Deflater

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
            storedExtractedText = fullText,
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

    @Test
    fun extractPdfFileToWriter_streamsCompressedTextAndSkipsImageStreams_andReadsFromFile() {
        val pdfFile = File.createTempFile("test_book", ".pdf")
        val outTextFile = File.createTempFile("test_book_out", ".txt")
        try {
            val mathContentStream = "BT /F1 12 Tf 72 720 Td (Chapter 4: Solve 3x + 5 = 20 step by step) Tj ET"
            val compressedMath = deflateBytes(mathContentStream.toByteArray(Charsets.UTF_8))
            val fakeImageBytes = ByteArray(1024 * 64) { 0x55 }

            FileOutputStream(pdfFile).use { fos ->
                fos.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
                fos.write("1 0 obj\n<< /Subtype /Image /Filter /DCTDecode /Length ${fakeImageBytes.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
                fos.write(fakeImageBytes)
                fos.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
                fos.write("2 0 obj\n<< /Filter /FlateDecode /Length ${compressedMath.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
                fos.write(compressedMath)
                fos.write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.US_ASCII))
            }

            BufferedWriter(OutputStreamWriter(FileOutputStream(outTextFile), Charsets.UTF_8)).use { writer ->
                writer.write("Document: big_math_book.pdf\nTotal Pages: 2\n\n")
                PdfExtractor.extractPdfFileToWriter(pdfFile, 2, writer)
            }

            val fileRef = "${PdfExtractor.FILE_PREFIX}${outTextFile.absolutePath}"
            val retrieved = PdfExtractor.retrieveRelevantContext(
                query = "3x + 5 = 20",
                storedExtractedText = fileRef,
                maxChars = 12000
            )

            assertTrue("Expected extracted math text from compressed stream", retrieved.contains("Solve 3x + 5 = 20 step by step"))
            assertFalse("Expected image binary data to be skipped", retrieved.contains("UUUUUU"))
        } finally {
            pdfFile.delete()
            outTextFile.delete()
        }
    }

    private fun deflateBytes(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            baos.write(buf, 0, count)
        }
        deflater.end()
        return baos.toByteArray()
    }
}
