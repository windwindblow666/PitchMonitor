package com.pitchmonitor.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.pitchmonitor.model.PitchSession

data class ExportResult(val uri: Uri, val displayName: String)

/** Exports session artifacts (WAV audio, CSV pitch data) to public Downloads. */
object Exporter {

    /** Characters not allowed in file names on Android. */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    fun sanitizeFileName(name: String): String =
        ILLEGAL.replace(name, " ").trim().ifEmpty { "recording" }

    /** Display name of a content Uri (for imported files), or null. */
    fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Writes [bytes] into MediaStore Downloads. No storage permission needed
     * (API 29+) for URIs the app inserted itself. Returns the content Uri and
     * the final display name (collisions get " (1)" suffixes from MediaStore).
     */
    fun exportToDownloads(
        ctx: Context,
        bytes: ByteArray,
        baseName: String,
        extension: String,
        mime: String,
    ): ExportResult {
        val safe = sanitizeFileName(baseName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$safe.$extension")
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入下载目录")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // read back the actual name (MediaStore dedupes collisions)
        var displayName = "$safe.$extension"
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0) ?: displayName
        }
        return ExportResult(uri, displayName)
    }

    fun exportAudio(ctx: Context, session: PitchSession, wav: ByteArray): ExportResult =
        exportToDownloads(ctx, wav, session.name, "wav", "audio/wav")

    fun exportCsv(ctx: Context, session: PitchSession): ExportResult =
        exportToDownloads(ctx, buildCsv(session).toByteArray(Charsets.UTF_8), "${session.name}_pitch", "csv", "text/csv")

    /** time_s, frequency_hz, note, cents — empty fields where no pitch. */
    fun buildCsv(session: PitchSession): String {
        val sb = StringBuilder("time_s,frequency_hz,note,cents\n")
        for (i in session.timesMs.indices) {
            val t = "%.3f".format(session.timesMs[i] / 1000.0)
            val freq = session.freqs.getOrElse(i) { null }
            if (freq == null) {
                sb.append(t).append(",,,\n")
            } else {
                val info = NoteUtil.freqToNote(freq)
                sb.append(t).append(',')
                    .append("%.2f".format(freq)).append(',')
                    .append(info?.let { it.name + it.octave } ?: "").append(',')
                    .append(info?.cents?.let { "%.1f".format(it) } ?: "").append('\n')
            }
        }
        return sb.toString()
    }
}
