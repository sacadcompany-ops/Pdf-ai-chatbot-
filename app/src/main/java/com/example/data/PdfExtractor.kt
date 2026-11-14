package com.example.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.Inflater

object PdfExtractor {

    const val FILE_PREFIX = "@FILE:"
    private const val MAX_COMPRESSED_STREAM_BYTES = 2 * 1024 * 1024 // 2 MB cap per stream
    private const val MAX_DECOMPRESSED_STREAM_BYTES = 1024 * 1024 // 1 MB cap per decompressed stream
    private const val MAX_TOTAL_EXTRACTED_CHARS = 4_000_000 // ~4 MB text cap (~1500+ pages)
    private const val FALLBACK_SCAN_BYTES = 1024 * 1024 // 1 MB max for fallback ASCII scan

    data class ExtractionResult(
        val title: String,
        val fileName: String,
        val pageCount: Int,
        val extractedText: String
    )

    suspend fun extractTextFromUri(context: Context, uri: Uri): ExtractionResult = withContext(Dispatchers.IO) {
        var fileName = "document.pdf"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: "document.pdf"
                }
            }
        } catch (_: Exception) {
            // Keep default fileName if metadata query fails
        }

        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.pdf")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open selected PDF file.")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Selected PDF file is empty or unreadable.")
            }

            val pageCount = readPageCountSafely(tempFile)

            val storageDir = File(context.filesDir, "extracted_pdfs").apply { mkdirs() }
            val textFile = File(storageDir, "doc_${System.currentTimeMillis()}.txt")

            BufferedWriter(OutputStreamWriter(FileOutputStream(textFile), Charsets.UTF_8), 32 * 1024).use { writer ->
                writer.write("Document: $fileName\n")
                writer.write("Total Pages: $pageCount\n\n")
                extractPdfFileToWriter(tempFile, pageCount, writer)
            }

            val storedReference = "$FILE_PREFIX${textFile.absolutePath}"

            ExtractionResult(
                title = fileName.substringBeforeLast(".").ifBlank { "PDF Document" },
                fileName = fileName,
                pageCount = pageCount,
                extractedText = storedReference
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun readPageCountSafely(pdfFile: File): Int {
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.pageCount.coerceAtLeast(1)
                }
            }
        } catch (_: Throwable) {
            1
        }
    }

    /**
     * Streams a PDF file from disk in bounded memory (safe for 50MB–200MB+ PDF books),
     * decompresses text content streams, parses PDF text operators, and writes page-marked text
     * directly to [writer].
     */
    fun extractPdfFileToWriter(pdfFile: File, pageCount: Int, writer: BufferedWriter) {
        val streamKeyword = "stream".toByteArray(Charsets.US_ASCII)
        val endStreamKeyword = "endstream".toByteArray(Charsets.US_ASCII)

        val dictTail = ByteArray(256)
        var dictTailCount = 0
        var prevByte = 32

        var insideStream = false
        var skipStream = false
        var streamMatchIdx = 0
        var endMatchIdx = 0
        val streamBuf = ByteArrayOutputStream(32 * 1024)

        var sectionIndex = 0
        var totalCharsWritten = 0

        BufferedInputStream(FileInputStream(pdfFile), 64 * 1024).use { bis ->
            var b = bis.read()
            while (b != -1 && totalCharsWritten < MAX_TOTAL_EXTRACTED_CHARS) {
                if (!insideStream) {
                    // Maintain sliding 256-byte window of preceding dictionary tokens
                    if (dictTailCount < dictTail.size) {
                        dictTail[dictTailCount++] = b.toByte()
                    } else {
                        System.arraycopy(dictTail, 1, dictTail, 0, dictTail.size - 1)
                        dictTail[dictTail.size - 1] = b.toByte()
                    }

                    if (streamMatchIdx == 0) {
                        if (b == streamKeyword[0].toInt() && (prevByte <= 32 || prevByte == '>'.code || prevByte == ']'.code)) {
                            streamMatchIdx = 1
                        }
                    } else if (b == streamKeyword[streamMatchIdx].toInt()) {
                        streamMatchIdx++
                        if (streamMatchIdx == streamKeyword.size) {
                            // PDF spec requires EOL (\r\n or \n) immediately after 'stream'
                            val next = bis.read()
                            if (next == '\r'.code || next == '\n'.code) {
                                if (next == '\r'.code) {
                                    bis.mark(1)
                                    val lf = bis.read()
                                    if (lf != '\n'.code && lf != -1) {
                                        bis.reset()
                                    }
                                }
                                val dictPreview = String(dictTail, 0, dictTailCount, Charsets.ISO_8859_1)
                                skipStream = isBinaryMediaOrFontDict(dictPreview)
                                insideStream = true
                                endMatchIdx = 0
                                streamBuf.reset()
                            }
                            streamMatchIdx = 0
                        }
                    } else {
                        streamMatchIdx = if (b == streamKeyword[0].toInt() && (prevByte <= 32 || prevByte == '>'.code || prevByte == ']'.code)) 1 else 0
                    }
                    prevByte = b
                } else {
                    if (!skipStream) {
                        if (streamBuf.size() < MAX_COMPRESSED_STREAM_BYTES) {
                            streamBuf.write(b)
                        } else {
                            skipStream = true
                            streamBuf.reset()
                        }
                    }

                    if (b == endStreamKeyword[endMatchIdx].toInt()) {
                        endMatchIdx++
                        if (endMatchIdx == endStreamKeyword.size) {
                            if (!skipStream && streamBuf.size() > endStreamKeyword.size) {
                                val rawAll = streamBuf.toByteArray()
                                var dataLen = rawAll.size - endStreamKeyword.size
                                while (dataLen > 0 && (rawAll[dataLen - 1] == '\n'.code.toByte() || rawAll[dataLen - 1] == '\r'.code.toByte())) {
                                    dataLen--
                                }
                                if (dataLen > 0) {
                                    val parsed = extractTextFromStreamBytes(rawAll, dataLen)
                                    if (!parsed.isNullOrBlank()) {
                                        sectionIndex++
                                        val pageNum = sectionIndex.coerceAtMost(pageCount.coerceAtLeast(1))
                                        val label = if (sectionIndex <= pageCount) {
                                            "--- Page $pageNum ---\n"
                                        } else {
                                            "--- Page $pageNum (Section $sectionIndex) ---\n"
                                        }
                                        writer.write(label)
                                        writer.write(parsed)
                                        writer.write("\n\n")
                                        totalCharsWritten += label.length + parsed.length + 2
                                    }
                                }
                            }
                            insideStream = false
                            skipStream = false
                            endMatchIdx = 0
                            streamMatchIdx = 0
                            streamBuf.reset()
                            dictTailCount = 0
                            prevByte = 32
                        }
                    } else {
                        endMatchIdx = if (b == endStreamKeyword[0].toInt()) 1 else 0
                    }
                }
                b = bis.read()
            }
        }

        if (sectionIndex == 0) {
            writeBoundedFallbackText(pdfFile, pageCount, writer)
        }
    }

    private fun isBinaryMediaOrFontDict(dictPreview: String): Boolean {
        return dictPreview.contains("/Subtype /Image") ||
            dictPreview.contains("/Subtype/Image") ||
            dictPreview.contains("/DCTDecode") ||
            dictPreview.contains("/JPXDecode") ||
            dictPreview.contains("/JBIG2Decode") ||
            dictPreview.contains("/CCITTFaxDecode") ||
            dictPreview.contains("/FontFile")
    }

    private fun extractTextFromStreamBytes(rawAll: ByteArray, length: Int): String? {
        val decompressed = tryInflateBounded(rawAll, length)
        val content = decompressed ?: run {
            if (length > 512 * 1024) return null
            // Quick check: only scan uncompressed stream if it contains mostly printable ASCII
            val sampleLen = length.coerceAtMost(256)
            var printable = 0
            for (i in 0 until sampleLen) {
                val c = rawAll[i].toInt() and 0xFF
                if (c in 9..13 || c in 32..126) printable++
            }
            if (sampleLen > 0 && printable * 100 / sampleLen < 75) return null
            String(rawAll, 0, length, Charsets.ISO_8859_1)
        }

        if (!content.contains("Tj") && !content.contains("TJ")) return null
        return parsePdfTextOperators(content).takeIf { it.isNotBlank() }
    }

    private fun tryInflateBounded(compressed: ByteArray, length: Int): String? {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed, 0, length)
            val outputStream = ByteArrayOutputStream((length * 2).coerceAtMost(64 * 1024))
            val buffer = ByteArray(8192)
            var totalOut = 0
            var loops = 0
            while (!inflater.finished() && loops < 1000 && totalOut < MAX_DECOMPRESSED_STREAM_BYTES) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    val toWrite = count.coerceAtMost(MAX_DECOMPRESSED_STREAM_BYTES - totalOut)
                    outputStream.write(buffer, 0, toWrite)
                    totalOut += toWrite
                }
                loops++
            }
            val bytes = outputStream.toByteArray()
            if (bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else null
        } catch (_: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun writeBoundedFallbackText(pdfFile: File, pageCount: Int, writer: BufferedWriter) {
        val buffer = ByteArray(FALLBACK_SCAN_BYTES)
        val bytesRead = FileInputStream(pdfFile).use { it.read(buffer) }.coerceAtLeast(0)
        val lineSb = StringBuilder()
        val outSb = StringBuilder()

        for (idx in 0 until bytesRead) {
            val intVal = buffer[idx].toInt() and 0xFF
            if (intVal == 10 || intVal == 13) {
                val line = lineSb.toString().trim()
                lineSb.setLength(0)
                if (isUsefulFallbackLine(line)) {
                    outSb.append(line).append('\n')
                    if (outSb.length >= 40_000) break
                }
            } else if (intVal in 32..126) {
                lineSb.append(intVal.toChar())
            } else {
                lineSb.append(' ')
            }
        }

        val lastLine = lineSb.toString().trim()
        if (outSb.length < 40_000 && isUsefulFallbackLine(lastLine)) {
            outSb.append(lastLine)
        }

        val cleaned = outSb.toString().replace(Regex(" {2,}"), " ").trim()
        writer.write("--- Page 1 ---\n")
        if (cleaned.isNotBlank()) {
            writer.write(cleaned)
        } else {
            writer.write(
                "No selectable text found in this PDF (Total Pages: $pageCount). " +
                    "This file may contain scanned page images or non-standard encoding."
            )
        }
    }

    private fun isUsefulFallbackLine(line: String): Boolean {
        return line.length > 4 &&
            !line.startsWith("%PDF") &&
            !line.startsWith("/") &&
            !line.contains("endobj") &&
            !line.contains("xref") &&
            !line.contains("startxref") &&
            !line.contains("stream") &&
            line.any { it.isLetter() }
    }

    private fun parsePdfTextOperators(streamContent: String): String {
        val sb = StringBuilder()
        var searchPos = 0
        var foundAnyBt = false

        while (searchPos < streamContent.length) {
            val btIdx = streamContent.indexOf("BT", searchPos)
            if (btIdx == -1) break
            val etIdx = streamContent.indexOf("ET", btIdx + 2)
            if (etIdx == -1) break
            foundAnyBt = true
            val block = streamContent.substring(btIdx + 2, etIdx)
            val parsed = parseTextBlock(block)
            if (parsed.isNotEmpty()) {
                sb.append(parsed).append('\n')
            }
            searchPos = etIdx + 2
        }

        if (!foundAnyBt) {
            val parsed = parseTextBlock(streamContent)
            if (parsed.isNotEmpty()) {
                sb.append(parsed)
            }
        }

        return sb.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun parseTextBlock(block: String): String {
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
                            'r', 't' -> token.append(' ')
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
                if (block.startsWith("Td", i) || block.startsWith("TD", i) || block.startsWith("T*", i)) {
                    if (lineSb.isNotEmpty() && !lineSb.endsWith(" ") && !lineSb.endsWith("\n")) {
                        lineSb.append('\n')
                    }
                }
                i++
            }
        }
        return lineSb.toString().trim()
    }

    fun deleteStoredTextFile(storedExtractedText: String) {
        if (storedExtractedText.startsWith(FILE_PREFIX)) {
            val path = storedExtractedText.removePrefix(FILE_PREFIX)
            try {
                File(path).delete()
            } catch (_: Exception) {
                // Best effort cleanup
            }
        }
    }

    /**
     * Streams from an extracted text file (when prefixed with @FILE:) or scans an inline string,
     * retrieving the highest-scoring pages/sections for the query within [maxChars].
     */
    fun retrieveRelevantContext(query: String, storedExtractedText: String, maxChars: Int = 12000): String {
        if (!storedExtractedText.startsWith(FILE_PREFIX)) {
            return retrieveFromInlineText(query, storedExtractedText, maxChars)
        }

        val file = File(storedExtractedText.removePrefix(FILE_PREFIX))
        if (!file.exists()) {
            return "Document text file is unavailable. Please re-import the PDF."
        }
        if (file.length() <= maxChars) {
            return file.readText(Charsets.UTF_8)
        }

        val queryTerms = extractQueryTerms(query)
        val headerLines = mutableListOf<String>()
        val topChunks = mutableListOf<Triple<Int, Int, String>>() // (index, score, text)
        val currentChunk = StringBuilder()
        var chunkIndex = 0
        var headerDone = false

        fun flushChunk() {
            val chunkStr = currentChunk.toString().trim()
            currentChunk.setLength(0)
            if (chunkStr.isEmpty()) return
            val score = scoreChunk(chunkIndex, chunkStr, queryTerms)
            val trimmed = if (chunkStr.length > 3000) chunkStr.take(3000) + "..." else chunkStr
            topChunks.add(Triple(chunkIndex, score, trimmed))
            if (topChunks.size > 24) {
                // Keep only highest scoring 16 chunks in memory
                topChunks.sortByDescending { it.second }
                while (topChunks.size > 16) {
                    topChunks.removeAt(topChunks.lastIndex)
                }
            }
            chunkIndex++
        }

        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 32 * 1024).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (!headerDone && headerLines.size < 3 && !line.startsWith("--- Page ")) {
                    headerLines.add(line)
                } else {
                    headerDone = true
                    if (line.startsWith("--- Page ") && currentChunk.isNotEmpty()) {
                        flushChunk()
                    }
                    currentChunk.append(line).append('\n')
                    if (currentChunk.length >= 2500) {
                        flushChunk()
                    }
                }
                line = reader.readLine()
            }
        }
        flushChunk()

        val header = headerLines.joinToString("\n")
        return assembleTopChunks(header, topChunks, maxChars)
    }

    private fun retrieveFromInlineText(query: String, fullText: String, maxChars: Int): String {
        if (fullText.length <= maxChars) return fullText

        val lines = fullText.lines()
        val header = lines.take(3).joinToString("\n")
        val body = lines.drop(3).joinToString("\n")

        val rawSections = body.split(Regex("(?=--- Page \\d+)")).filter { it.isNotBlank() }
        val chunks = if (rawSections.size > 1) {
            rawSections
        } else {
            body.chunked(1500)
        }

        val queryTerms = extractQueryTerms(query)
        if (queryTerms.isEmpty()) {
            return (header + "\n\n" + body).take(maxChars)
        }

        val scored = chunks.mapIndexed { idx, chunk ->
            val trimmed = if (chunk.length > 3000) chunk.take(3000) + "..." else chunk
            Triple(idx, scoreChunk(idx, trimmed, queryTerms), trimmed)
        }

        return assembleTopChunks(header, scored, maxChars)
    }

    private fun extractQueryTerms(query: String): Set<String> {
        return query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}+\\-*/=]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun scoreChunk(idx: Int, chunk: String, queryTerms: Set<String>): Int {
        if (queryTerms.isEmpty()) return if (idx == 0) 2 else 1
        val lower = chunk.lowercase()
        return queryTerms.sumOf { term -> if (lower.contains(term)) 2 else 0 } + (if (idx == 0) 1 else 0)
    }

    private fun assembleTopChunks(
        header: String,
        scored: List<Triple<Int, Int, String>>,
        maxChars: Int
    ): String {
        val selected = mutableListOf<Pair<Int, String>>()
        var currentLen = header.length + 10

        for ((idx, _, chunk) in scored.sortedByDescending { it.second }) {
            if (currentLen + chunk.length + 4 <= maxChars) {
                selected.add(idx to chunk)
                currentLen += chunk.length + 4
            }
        }

        val orderedBody = selected.sortedBy { it.first }.joinToString("\n\n") { it.second }
        return if (header.isNotBlank()) "$header\n\n$orderedBody" else orderedBody
    }
}
