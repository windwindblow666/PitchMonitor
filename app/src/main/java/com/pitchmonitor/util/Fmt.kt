package com.pitchmonitor.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers shared by UI screens. */
object Fmt {

    /** 83_000 → "1:23" (clock style, for stopwatch / progress). */
    fun clock(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    /** 83_000 → "1分23秒" (for session names / cards). */
    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "%d时%02d分%02d秒".format(h, m, s)
            m > 0 -> "%d分%02d秒".format(m, s)
            else -> "%d秒".format(s)
        }
    }

    /** Default session name: "09-05 11:20 · 1分23秒". */
    fun defaultSessionName(createdAt: Long, durationMs: Long): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
        return "$time · ${duration(durationMs)}"
    }
}
