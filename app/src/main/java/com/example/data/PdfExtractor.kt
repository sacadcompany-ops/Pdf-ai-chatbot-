package com.example.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Inflater

object PdfExtractor {

    data class ExtractionResult(
        val title: String,
        val fileName: String,
        val pageCount: Int,
        val extractedText: String
    )

    suspend fun extractTextFromUri(context: Context, uri: Uri): ExtractionResult = withContext(Dispatchers.IO) {
        var fileName = "document.pdf"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex) ?: "document.pdf"
            }
        }

        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        val pageCount = try {
            val fileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val count = renderer.pageCount
            renderer.close()
            fileDescriptor.close()
            count
        } catch (_: Exception) {
            1
        }

        val rawBytes = tempFile.readBytes()
        tempFile.delete()

        val extractedPages = extractReadablePdfText(rawBytes, pageCount)

        val fullText = buildString {
            append("Document: $fileName\n")
            append("Total Pages: $pageCount\n\n")
            append(extractedPages)
        }

        ExtractionResult(
            title = fileName.substringBeforeLast("."),
            fileName = fileName,
            pageCount = pageCount,
            extractedText = fullText
        )
    }

    private fun extractReadablePdfText(rawBytes: ByteArray, pageCount: Int): String {
        val rawLatin = String(rawBytes, Charsets.ISO_8859_1)
        val streamTexts = mutableListOf<String>()

        // Decompress FlateDecode / content streams and extract PDF text operators
        val streamRegex = Regex("stream\\r?\\n([\\s\\S]*?)\\r?\\n?endstream")
        for (match in streamRegex.findAll(rawLatin)) {
            val streamData = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            val decompressed = tryInflate(streamData)
            val content = decompressed ?: match.groupValues[1]

            // Check if this stream contains PDF text blocks (BT ... ET)
            if (content.contains("Tj") || content.contains("TJ")) {
                val parsed = parsePdfTextOperators(content)
                if (parsed.isNotBlank()) {
                    streamTexts.add(parsed)
                }
            }
        }

        val combined = if (streamTexts.isNotEmpty()) {
            val nonEmptyPages = streamTexts.filter { it.isNotBlank() }
            if (pageCount > 1 && nonEmptyPages.size >= pageCount) {
                nonEmptyPages.take(pageCount).mapIndexed { index, text ->
                    "--- Page ${index + 1} ---\n$text"
                }.joinToString("\n\n")
            } else {
                nonEmptyPages.mapIndexed { index, text ->
                    val pageNum = (index + 1).coerceAtMost(pageCount.coerceAtLeast(1))
                    "--- Page $pageNum (Section ${index + 1}) ---\n$text"
                }.joinToString("\n\n")
            }
        } else {
            // Fallback: extract readable printable lines while stripping PDF structural tokens
            val fallback = rawBytes.map {
                val intVal = it.toInt() and 0xFF
                if (intVal in 32..126 || intVal == 10 || intVal == 13) intVal.toChar() else ' '
            }.joinToString("")
                .lines()
                .map { it.trim() }
                .filter { line ->
                    line.length > 3 &&
                        !line.startsWith("%PDF") &&
                        !line.startsWith("/") &&
                        !line.contains("endobj") &&
                        !line.contains("xref") &&
                        !line.contains("startxref")
                }
                .joinToString("\n")
                .replace(Regex("\\s{2,}"), " ")

            buildString {
                append("--- Page 1 ---\n")
                append(fallback.ifBlank { "No selectable text found in PDF." })
            }
        }

        return combined
    }

    private fun tryInflate(compressed: ByteArray): String? {
        return try {
            val inflater = Inflater()
            inflater.setInput(compressed)
            val outputStream = ByteArrayOutputStream(compressed.size * 2)
            val buffer = ByteArray(4096)
            var loops = 0
            while (!inflater.finished() && loops < 1000) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    outputStream.write(buffer, 0, count)
                }
                loops++
            }
            inflater.end()
            val bytes = outputStream.toByteArray()
            if (bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePdfTextOperators(streamContent: String): String {
        val sb = StringBuilder()
        // Match literal strings inside (...) Tj or [...] TJ
        val btBlocks = Regex("BT([\\s\\S]*?)ET").findAll(streamContent)
        val blocksToScan = if (btBlocks.any()) btBlocks.map { it.groupValues[1] } else sequenceOf(streamContent)

        for (block in blocksToScan) {
            val lineSb = StringBuilder()
            var i = 0
            while (i < block.length) {
                if (block[i] == '(') {
                    val strStart = i + 1
                    var depth = 1
                    var j = strStart
                    val token = StringBuilder()
                    while (j < block.length && depth > 0) {
                        val ch = block[j]
                        if (ch == '\\' && j + 1 < block.length) {
                            val next = block[j + 1]
                            when (next) {
                                'n' -> token.append('\n')
                                'r' -> token.append(' ')
                                't' -> token.append(' ')
                                '(', ')', '\\' -> token.append(next)
                                else -> token.append(next)
                            }
                            j += 2
                        } else if (ch == '(') {
                            depth++
                            token.append(ch)
                            j++
                        } else if (ch == ')') {
                            depth--
                            if (depth > 0) token.append(ch)
                            j++
                        } else {
                            if (ch.code in 32..126 || ch.code >= 160) {
                                token.append(ch)
                            }
                            j++
                        }
                    }
                    if (token.isNotEmpty()) {
                        lineSb.append(token)
                    }
                    i = j
                } else {
                    // Detect line breaks from Td / TD / T* / ' operators
                    if (block.startsWith("Td", i) || block.startsWith("TD", i) || block.startsWith("T*", i)) {
                        if (lineSb.isNotEmpty() && !lineSb.endsWith(" ") && !lineSb.endsWith("\n")) {
                            lineSb.append('\n')
                        }
                    }
                    i++
                }
            }
            val cleanedBlock = lineSb.toString().trim()
            if (cleanedBlock.isNotEmpty()) {
                sb.append(cleanedBlock).append('\n')
            }
        }

        return sb.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Retrieves the most relevant sections/pages from the PDF text for the user's query
     * while preserving page markers and staying within safe free-tier token limits.
     */
    fun retrieveRelevantContext(query: String, fullText: String, maxChars: Int = 12000): String {
        if (fullText.length <= maxChars) return fullText

        val lines = fullText.lines()
        val header = lines.take(3).joinToString("\n")
        val body = lines.drop(3).joinToString("\n")

        // Split by page markers or chunk into ~1500-char blocks
        val rawSections = body.split(Regex("(?=--- Page \\d+)")).filter { it.isNotBlank() }
        val chunks = if (rawSections.size > 1) {
            rawSections
        } else {
            body.chunked(1500)
        }

        val queryTerms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}+\\-*/=]+"))
            .filter { it.length >= 2 }
            .toSet()

        if (queryTerms.isEmpty()) {
            return (header + "\n\n" + body).take(maxChars)
        }

        val scored = chunks.mapIndexed { idx, chunk ->
            val lower = chunk.lowercase()
            val score = queryTerms.sumOf { term ->
                if (lower.contains(term)) 2 else 0
            } + (if (idx == 0) 1 else 0)
            Triple(idx, score, chunk)
        }

        val selected = mutableListOf<Pair<Int, String>>()
        var currentLen = header.length + 10

        // Take highest scoring chunks first, then sort back into original page order
        for ((idx, _, chunk) in scored.sortedByDescending { it.second }) {
            val trimmedChunk = if (chunk.length > 3000) chunk.take(3000) + "..." else chunk
            if (currentLen + trimmedChunk.length + 4 <= maxChars) {
                selected.add(idx to trimmedChunk)
                currentLen += trimmedChunk.length + 4
            }
        }

        val orderedBody = selected.sortedBy { it.first }.joinToString("\n\n") { it.second }
        return "$header\n\n$orderedBody"
    }
}
