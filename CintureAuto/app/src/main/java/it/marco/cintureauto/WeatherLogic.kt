package it.marco.cintureauto

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Notifica meteo preventiva: logica "pura" (senza Android), così si prova sul computer.
 * Dati: Open-Meteo (gratuito, senza account), previsione oraria nel punto di parcheggio.
 */

/** Previsione oraria: istanti (ms), temperature (°C) e codici meteo WMO. */
class WeatherData(
    val utcOffsetSec: Int,
    val timesMs: LongArray,
    val tempsC: DoubleArray,
    val codes: IntArray
)

/** Cosa minaccia l'auto parcheggiata nelle prossime ore (null = niente). */
data class WeatherRisk(
    val hailAtMs: Long?,
    val frostAtMs: Long?,
    val frostMinC: Double?
) {
    val any: Boolean get() = hailAtMs != null || frostAtMs != null

    /** Identifica il tipo di avviso, per non ripetere la stessa notifica. */
    val key: String get() = (if (hailAtMs != null) "H" else "") + (if (frostAtMs != null) "F" else "")
}

object WeatherLogic {

    /** Codici WMO "temporale con grandine" (lieve / forte). */
    private val HAIL_CODES = setOf(96, 99)

    /** Gelata: temperatura prevista a 2 m dal suolo uguale o sotto questo valore. */
    const val FROST_MAX_C = 0.0

    /** Ore considerate "notte" (ora locale): dalle 21 alle 8 comprese. */
    private const val NIGHT_FROM_HOUR = 21
    private const val NIGHT_TO_HOUR = 8

    /** Si guarda avanti di tante ore. */
    const val LOOKAHEAD_MS = 18L * 3_600_000L

    fun buildUrl(lat: Double, lon: Double): String {
        // posizione arrotondata a 0,01° (circa 1 km): basta per la previsione e rivela meno
        return String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                "&hourly=temperature_2m,weather_code&forecast_days=2&timeformat=unixtime&timezone=auto",
            lat, lon
        )
    }

    /** Legge la risposta di Open-Meteo. Restituisce null se è rovinata. */
    fun parse(text: String): WeatherData? {
        return try {
            val root = JSONObject(text)
            val hourly = root.optJSONObject("hourly") ?: return null
            val times = hourly.optJSONArray("time") ?: return null
            val temps = hourly.optJSONArray("temperature_2m") ?: return null
            val codes = hourly.optJSONArray("weather_code") ?: return null
            val n = minOf(times.length(), temps.length(), codes.length())
            if (n == 0) return null
            WeatherData(
                utcOffsetSec = root.optInt("utc_offset_seconds", 0),
                timesMs = LongArray(n) { times.optLong(it, 0L) * 1000L },
                tempsC = DoubleArray(n) { temps.optDouble(it, Double.NaN) },
                codes = IntArray(n) { codes.optInt(it, -1) }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isNight(timeMs: Long, offsetSec: Int): Boolean {
        val localHour = Math.floorMod((timeMs / 1000L + offsetSec) / 3600L, 24L).toInt()
        return localHour >= NIGHT_FROM_HOUR || localHour <= NIGHT_TO_HOUR
    }

    /** Cerca grandine (qualsiasi ora) e gelata (solo di notte) da adesso alle prossime 18 ore. */
    fun evaluate(data: WeatherData, nowMs: Long): WeatherRisk {
        var hailAt: Long? = null
        var frostAt: Long? = null
        var frostMin: Double? = null
        val from = nowMs - 3_600_000L    // l'ora in corso conta ancora
        val to = nowMs + LOOKAHEAD_MS
        for (i in data.timesMs.indices) {
            val t = data.timesMs[i]
            if (t < from || t > to) continue
            if (hailAt == null && data.codes[i] in HAIL_CODES) hailAt = t
            val temp = data.tempsC[i]
            if (temp.isFinite() && temp <= FROST_MAX_C && isNight(t, data.utcOffsetSec)) {
                if (frostMin == null || temp < frostMin) {
                    frostMin = temp
                    frostAt = t
                }
            }
        }
        return WeatherRisk(hailAt, frostAt, frostMin)
    }

    /** Titolo e testo della notifica, in italiano. */
    fun message(risk: WeatherRisk, tz: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val f = SimpleDateFormat("HH:mm", Locale.ITALY)
        f.timeZone = tz
        val parts = mutableListOf<String>()
        risk.hailAtMs?.let {
            parts.add("Prevista grandine verso le ${f.format(Date(it))} nella zona dove hai parcheggiato.")
        }
        if (risk.frostAtMs != null && risk.frostMinC != null) {
            val temp = Math.round(risk.frostMinC).toInt()
            parts.add("Gelata notturna: previsti $temp °C verso le ${f.format(Date(risk.frostAtMs))}.")
        }
        val advice = if (risk.hailAtMs != null) {
            "Se puoi, sposta l'auto al riparo (garage, parcheggio coperto) o coprila con un telo."
        } else {
            "Copri il parabrezza o, se puoi, metti l'auto al riparo."
        }
        val title = when {
            risk.hailAtMs != null && risk.frostAtMs != null -> "⚠ Grandine e gelo dove è parcheggiata l'auto"
            risk.hailAtMs != null -> "⚠ Grandine in arrivo dove è parcheggiata l'auto"
            else -> "❄ Gelata notturna dove è parcheggiata l'auto"
        }
        return title to (parts.joinToString(" ") + " " + advice)
    }

    // -----------------------------------------------------------------------------------------
    // "Ehi Mercedes, che tempo fa?" — meteo nel punto in cui ti trovi
    // -----------------------------------------------------------------------------------------

    /** Meteo adesso, oggi e domani. */
    data class Outlook(
        val nowTempC: Double,
        val nowCode: Int,
        val todayMaxC: Double,
        val todayMinC: Double,
        val todayCode: Int,
        val todayRainPct: Int,
        val tomorrowMaxC: Double,
        val tomorrowMinC: Double,
        val tomorrowCode: Int,
        val tomorrowRainPct: Int
    )

    fun buildOutlookUrl(lat: Double, lon: Double): String = String.format(
        Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=2&timezone=auto",
        lat, lon
    )

    fun parseOutlook(text: String): Outlook? {
        return try {
            val root = JSONObject(text)
            val current = root.optJSONObject("current") ?: return null
            val daily = root.optJSONObject("daily") ?: return null
            val codes = daily.optJSONArray("weather_code") ?: return null
            val max = daily.optJSONArray("temperature_2m_max") ?: return null
            val min = daily.optJSONArray("temperature_2m_min") ?: return null
            val rain = daily.optJSONArray("precipitation_probability_max")
            if (codes.length() < 2 || max.length() < 2 || min.length() < 2) return null
            val nowT = current.optDouble("temperature_2m")
            if (!nowT.isFinite()) return null
            Outlook(
                nowTempC = nowT,
                nowCode = current.optInt("weather_code", -1),
                todayMaxC = max.optDouble(0, Double.NaN),
                todayMinC = min.optDouble(0, Double.NaN),
                todayCode = codes.optInt(0, -1),
                todayRainPct = rain?.optInt(0, -1) ?: -1,
                tomorrowMaxC = max.optDouble(1, Double.NaN),
                tomorrowMinC = min.optDouble(1, Double.NaN),
                tomorrowCode = codes.optInt(1, -1),
                tomorrowRainPct = rain?.optInt(1, -1) ?: -1
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Descrizione in italiano dei codici meteo WMO. */
    fun describeCode(code: Int): String = when (code) {
        0 -> "cielo sereno"
        1 -> "cielo quasi sereno"
        2 -> "parzialmente nuvoloso"
        3 -> "cielo coperto"
        45, 48 -> "nebbia"
        51, 53, 55 -> "pioggerella"
        56, 57 -> "pioggerella gelata"
        61 -> "pioggia debole"
        63 -> "pioggia"
        65 -> "pioggia forte"
        66, 67 -> "pioggia gelata"
        71 -> "neve debole"
        73 -> "neve"
        75 -> "neve forte"
        77 -> "nevischio"
        80, 81 -> "rovesci di pioggia"
        82 -> "rovesci violenti"
        85, 86 -> "rovesci di neve"
        95 -> "temporale"
        96, 99 -> "temporale con grandine"
        else -> "tempo variabile"
    }

    private fun deg(v: Double): String {
        val n = Math.round(v).toInt()
        return if (n == 1 || n == -1) "$n grado" else "$n gradi"
    }

    /** La risposta parlata. [place] è il nome della località, se noto. */
    fun spokenOutlook(o: Outlook, place: String?, tomorrow: Boolean): String {
        val where = if (place.isNullOrBlank()) "Qui" else "A $place"
        val sb = StringBuilder()
        if (!tomorrow) {
            sb.append("$where adesso ci sono ${deg(o.nowTempC)}, ${describeCode(o.nowCode)}. ")
            sb.append("Oggi ${describeCode(o.todayCode)}")
            if (o.todayMaxC.isFinite() && o.todayMinC.isFinite()) {
                sb.append(", massima ${deg(o.todayMaxC)} e minima ${deg(o.todayMinC)}")
            }
            sb.append(rainText(o.todayRainPct))
        } else {
            sb.append("${if (place.isNullOrBlank()) "Qui" else "A $place"} domani ${describeCode(o.tomorrowCode)}")
            if (o.tomorrowMaxC.isFinite() && o.tomorrowMinC.isFinite()) {
                sb.append(", massima ${deg(o.tomorrowMaxC)} e minima ${deg(o.tomorrowMinC)}")
            }
            sb.append(rainText(o.tomorrowRainPct))
        }
        return sb.toString().trim()
    }

    private fun rainText(pct: Int): String = when {
        pct < 0 -> "."
        pct < 15 -> ". Non è prevista pioggia."
        else -> ". Probabilità di pioggia $pct per cento."
    }
}

