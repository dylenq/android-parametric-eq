package com.dylenq.eq.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.dylenq.eq.core.EqException
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The largest EQ file the app reads; the core checks the same limit again. */
internal const val MAX_EQ_FILE_BYTES = 1_000_000

/** A file as the picker gave it: its display name and decoded text. */
internal class EqFileText(val name: String, val text: String)

/**
 * Strict UTF-8 text of an EQ file, or null when it is not text: invalid UTF-8 or any NUL byte
 * (binary-looking). A leading byte order mark is removed; CRLF is left for the parser.
 */
internal fun decodeEqText(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        return null
    }
    return text.removePrefix("﻿")
}

/** Reads at most [limit] bytes; null when the stream has more. It stops at limit + 1, never reading the rest. */
internal fun readBounded(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024) + 1)
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        // Ask for at most one byte past the limit, so an endless stream stops at limit + 1.
        val n = input.read(buf, 0, minOf(buf.size, limit + 1 - total))
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** The name an import is made from: the last path piece of [displayName], or "Imported". */
internal fun importNameFrom(displayName: String?): String =
    displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty().ifEmpty { "Imported" }

/** Stream IO for the system file picker, always off the main thread. */
internal object EqFileIo {
    /** Reads and decodes [uri]. Throws [EqException.FileTooLarge], [EqException.NotAnEqFile] or an IO error. */
    suspend fun read(resolver: ContentResolver, uri: Uri): EqFileText = withContext(Dispatchers.IO) {
        val bytes = (resolver.openInputStream(uri) ?: throw FileNotFoundException())
            .use { readBounded(it, MAX_EQ_FILE_BYTES) } ?: throw EqException.FileTooLarge()
        val text = decodeEqText(bytes) ?: throw EqException.NotAnEqFile()
        EqFileText(importNameFrom(displayName(resolver, uri)), text)
    }

    /** Writes [text] to [uri], replacing what was there. */
    suspend fun write(resolver: ContentResolver, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        (resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException())
            .use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) return c.getString(i)
                }
            }
        } catch (e: Exception) {
            // A provider without the column or a failing query: fall back to the Uri's own last segment.
        }
        return uri.lastPathSegment
    }
}
