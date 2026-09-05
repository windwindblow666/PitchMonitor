package com.pitchmonitor.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit mono PCM frames into a WAV file: a placeholder 44-byte RIFF
 * header is written up-front, audio follows as frames arrive, and [finish]
 * seeks back to patch the real sizes. Crash-safe — an unfinished file simply
 * has a broken header and is ignored.
 */
class WavRecorder(
    file: File,
    private val sampleRate: Int,
) {
    private val raf = RandomAccessFile(file, "rw")
    private val path = file.absolutePath
    private var dataLen = 0L
    private var closed = false

    init {
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(pcm: ShortArray) {
        if (closed) return
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) buf.putShort(s)
        raf.write(buf.array())
        dataLen += pcm.size * 2
    }

    /** Patches the RIFF header and closes the file. */
    @Synchronized
    fun finish() {
        if (closed) return
        try {
            val chunkSize = dataLen + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(chunkSize.toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)                       // PCM
            header.putShort(1)                       // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)            // byte rate (16-bit mono)
            header.putShort(2)                       // block align
            header.putShort(16)                      // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataLen.toInt())
            raf.seek(0)
            raf.write(header.array())
        } finally {
            raf.close()
            closed = true
        }
    }

    /** Closes and deletes the file (user discarded the recording). */
    @Synchronized
    fun abort() {
        if (closed) return
        try {
            raf.close()
        } catch (_: Exception) {}
        closed = true
        File(path).delete()
    }
}
