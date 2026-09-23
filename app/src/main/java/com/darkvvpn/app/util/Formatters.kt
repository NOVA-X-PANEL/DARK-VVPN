package com.darkvvpn.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formatting helpers shared by the Home screen and the notification.
 *
 * Everything here is pure and therefore unit-testable without Android.
 */
object Formatters {

    /** `1.4 MB/s`, `812 KB/s`, `0 B/s`. */
    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    /** `1.4 GB`, `812 MB`, `12 KB`, `0 B`. */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return if (i == 0) {
            "${value} ${units[i]}"
        } else {
            String.format(Locale.US, "%.1f %s", v, units[i])
        }
    }

    /** `00:42`, `12:05`, `2:03:11`. */
    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = TimeUnit.SECONDS.toHours(s)
        val m = TimeUnit.SECONDS.toMinutes(s) % 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }

    /** `42 ms`, or `—` when unknown. */
    fun ping(ms: Int?): String = ms?.let { "$it ms" } ?: "—"
}
