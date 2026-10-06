package it.marco.cintureauto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Testi in italiano per durate, distanze e date. Senza Android, così si prova sul computer. */
object TextFormat {

    /** 45 s · 12 min · 1 h 05 min */
    fun duration(sec: Long): String {
        val s = sec.coerceAtLeast(0L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        return when {
            h > 0L -> "$h h ${m.toString().padStart(2, '0')} min"
            m > 0L -> "$m min"
            else -> "$s s"
        }
    }

    /** 8,4 km */
    fun km(km: Double): String = String.format(Locale.ITALY, "%.1f km", km)

    /** 92 km/h */
    fun kmh(v: Double): String = "${Math.round(v)} km/h"

    /** adesso · 12 min fa · 3 ore fa · ieri · 5 giorni fa */
    fun ago(nowMs: Long, thenMs: Long): String {
        val sec = ((nowMs - thenMs) / 1000L).coerceAtLeast(0L)
        val min = sec / 60L
        val hours = min / 60L
        val days = hours / 24L
        return when {
            sec < 60L -> "adesso"
            min < 60L -> "$min min fa"
            hours < 24L -> if (hours == 1L) "1 ora fa" else "$hours ore fa"
            days == 1L -> "ieri"
            else -> "$days giorni fa"
        }
    }

    /** 06/10/2026 18:42 */
    fun dateTime(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        format("dd/MM/yyyy HH:mm", ms, tz)

    /** 06/10 18:42 */
    fun shortDateTime(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        format("dd/MM HH:mm", ms, tz)

    /** 45.46427, 9.18951 */
    fun coord(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    private fun format(pattern: String, ms: Long, tz: TimeZone): String {
        val f = SimpleDateFormat(pattern, Locale.ITALY)
        f.timeZone = tz
        return f.format(Date(ms))
    }
}
