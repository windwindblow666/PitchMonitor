package com.pitchmonitor.data

import android.content.Context
import com.pitchmonitor.model.PitchSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists recording sessions as JSON files under filesDir/pitch_sessions.
 * One file per session, named <id>.json. Zero external dependencies
 * (org.json ships with Android).
 */
object SessionStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "pitch_sessions").apply { mkdirs() }

    private fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.json")

    fun save(ctx: Context, s: PitchSession) {
        val fArr = JSONArray()
        for (f in s.freqs) fArr.put(f?.let { it.toDouble() } ?: JSONObject.NULL)
        val tArr = JSONArray()
        for (t in s.timesMs) tArr.put(t)

        val obj = JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("createdAt", s.createdAt)
            .put("durationMs", s.durationMs)
            .put("t", tArr)
            .put("f", fArr)

        val tmp = File(dir(ctx), "${s.id}.json.tmp")
        tmp.writeText(obj.toString())
        val dst = file(ctx, s.id)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    fun list(ctx: Context): List<PitchSession> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { parse(it) }.sortedByDescending { it.createdAt }
    }

    fun load(ctx: Context, id: Long): PitchSession? = parse(file(ctx, id))

    fun delete(ctx: Context, id: Long): Boolean = file(ctx, id).delete()

    private fun parse(f: File): PitchSession? = try {
        val o = JSONObject(f.readText())
        val tArr = o.getJSONArray("t")
        val fArr = o.getJSONArray("f")
        val n = minOf(tArr.length(), fArr.length())
        val times = ArrayList<Long>(n)
        val freqs = ArrayList<Float?>(n)
        for (i in 0 until n) {
            times.add(tArr.getLong(i))
            freqs.add(if (fArr.isNull(i)) null else fArr.getDouble(i).toFloat())
        }
        PitchSession(
            id = o.getLong("id"),
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            durationMs = o.getLong("durationMs"),
            timesMs = times,
            freqs = freqs,
        )
    } catch (_: Exception) {
        null
    }
}
