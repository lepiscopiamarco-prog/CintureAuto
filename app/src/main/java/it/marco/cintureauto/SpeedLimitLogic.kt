package it.marco.cintureauto

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Logica "pura" per capire su quale strada ti trovi e quale limite vale.
 * Non usa nulla di Android: così si può provare anche sul computer.
 */

/** Un tratto di strada di OpenStreetMap, con i suoi punti (latitudine/longitudine). */
class RoadWay(
    val id: Long,
    val highway: String,
    val name: String?,
    val maxspeed: String?,
    val maxspeedForward: String?,
    val maxspeedBackward: String?,
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Tipo di limite scritto a parte (maxspeed:type, source:maxspeed, zone:maxspeed), es. "IT:urban". */
    val zoneTag: String? = null
)

/**
 * Risultato dell'abbinamento posizione → strada.
 * [limitKmh] è null se la strada non ha un limite noto.
 * [fromTag] è true se il limite è scritto nei dati, false se è stato dedotto dal tipo di strada.
 */
class LimitMatch(
    val limitKmh: Int?,
    val fromTag: Boolean,
    val roadName: String?,
    val highway: String,
    val distanceM: Double,
    /** Per i limiti stimati: "centro abitato" o "fuori dal centro abitato" (null se non serve). */
    val estimateNote: String? = null
)

object SpeedLimitLogic {

    private const val M_PER_DEG_LAT = 111_132.0
    private const val M_PER_DEG_LON_EQUATOR = 111_320.0

    /** Le strade che incrociano la direzione di marcia con un angolo maggiore di questo si scartano. */
    private const val MAX_LINE_DIFF_DEG = 75.0

    /** Quanto "costa", in metri per grado, una strada che non è parallela alla direzione di marcia. */
    private const val ANGLE_PENALTY_M_PER_DEG = 0.4

    private val NUMBER = Regex("^(\\d{1,3})\\s*(mph|km/h|kmh|kph)?$")

    /**
     * Legge il valore "maxspeed" di OpenStreetMap.
     * Capisce: "50", "50 km/h", "30 mph", "IT:urban" (50), "IT:rural" (90), "IT:trunk" (110),
     * "IT:motorway" (130). Per "none", "walk", "signals" e simili restituisce null.
     */
    fun parseMaxspeed(raw: String?): Int? {
        if (raw == null) return null
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null
        val first = text.split(';')[0].trim()

        when (first) {
            "it:urban" -> return 50
            "it:rural" -> return 90
            "it:trunk" -> return 110
            "it:motorway" -> return 130
        }

        val m = NUMBER.matchEntire(first) ?: return null
        var value = m.groupValues[1].toInt()
        if (m.groupValues[2] == "mph") value = (value * 1.609344).roundToInt()
        return if (value in 5..200) value else null
    }

    /**
     * Limite "di legge" italiano da usare quando la strada non ha un limite scritto nei dati.
     * Per le strade ordinarie dipende dal centro abitato: 50 dentro, 90 fuori (Codice della Strada,
     * art. 142). È una stima, segnalata come tale.
     */
    fun defaultForHighway(highway: String, urban: Boolean = false): Int? = when (highway) {
        "motorway" -> 130
        "motorway_link" -> 80
        "trunk" -> if (urban) 70 else 110
        "trunk_link" -> if (urban) 50 else 70
        "primary", "secondary", "tertiary", "unclassified",
        "primary_link", "secondary_link", "tertiary_link" -> if (urban) 50 else 90
        "residential" -> 50
        "living_street" -> 30
        else -> null
    }

    private val URBAN_HINT_TYPES = setOf("residential", "living_street")

    /** Distanza minima (metri) tra il punto e un tratto di strada. */
    private fun distanceToWay(way: RoadWay, lat: Double, lon: Double, kLat: Double, kLon: Double): Double {
        val n = min(way.lats.size, way.lons.size)
        if (n < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        var px = (way.lons[0] - lon) * kLon
        var py = (way.lats[0] - lat) * kLat
        for (i in 1 until n) {
            val x = (way.lons[i] - lon) * kLon
            val y = (way.lats[i] - lat) * kLat
            val d = distanceToSegment(px, py, x, y)
            if (d < best) best = d
            px = x
            py = y
        }
        return best
    }

    /**
     * Stima se il punto è in un centro abitato: ci sono almeno 3 vie residenziali nel raggio
     * di 200 metri. In aperta campagna le vie residenziali sono rare.
     */
    fun looksUrban(ways: List<RoadWay>, lat: Double, lon: Double): Boolean {
        val kLat = M_PER_DEG_LAT
        val kLon = M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(lat))
        var count = 0
        for (w in ways) {
            if (w.highway in URBAN_HINT_TYPES && distanceToWay(w, lat, lon, kLat, kLon) <= 200.0) {
                count += 1
                if (count >= 3) return true
            }
        }
        return false
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLat = Math.toRadians((lat1 + lat2) / 2.0)
        val dx = (lon2 - lon1) * M_PER_DEG_LON_EQUATOR * cos(midLat)
        val dy = (lat2 - lat1) * M_PER_DEG_LAT
        return sqrt(dx * dx + dy * dy)
    }

    /** Differenza tra due direzioni in gradi, da 0 a 180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Distanza dall'origine (0,0) al segmento che va da (ax,ay) a (bx,by). */
    private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = 0.0
        if (len2 > 0.0) {
            t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        }
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    /**
     * Trova la strada su cui ti trovi e ne ricava il limite.
     *
     * @param headingDeg direzione di marcia in gradi (0 = nord), o null se non è affidabile
     * @param accuracyM precisione del GPS in metri, o 0 se sconosciuta
     * @return null se nessuna strada è abbastanza vicina
     */
    fun match(
        ways: List<RoadWay>,
        lat: Double,
        lon: Double,
        headingDeg: Double?,
        accuracyM: Double
    ): LimitMatch? {
        val kLat = M_PER_DEG_LAT
        val kLon = M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(lat))
        val maxDist = if (accuracyM > 0.0) (accuracyM * 1.5).coerceIn(25.0, 60.0) else 35.0

        var bestWay: RoadWay? = null
        var bestScore = Double.MAX_VALUE
        var bestDist = 0.0
        var bestForward = true

        for (way in ways) {
            val n = min(way.lats.size, way.lons.size)
            if (n >= 2) {
                var px = (way.lons[0] - lon) * kLon
                var py = (way.lats[0] - lat) * kLat
                for (i in 1 until n) {
                    val x = (way.lons[i] - lon) * kLon
                    val y = (way.lats[i] - lat) * kLat
                    val d = distanceToSegment(px, py, x, y)
                    if (d <= maxDist) {
                        var score = d
                        var forward = true
                        var usable = true
                        if (headingDeg != null) {
                            val segBearing = (Math.toDegrees(atan2(x - px, y - py)) + 360.0) % 360.0
                            val diff = angleDiff(segBearing, headingDeg)
                            val lineDiff = min(diff, 180.0 - diff)
                            if (lineDiff > MAX_LINE_DIFF_DEG) usable = false
                            score += lineDiff * ANGLE_PENALTY_M_PER_DEG
                            forward = diff < 90.0
                        }
                        if (usable && score < bestScore) {
                            bestScore = score
                            bestWay = way
                            bestDist = d
                            bestForward = forward
                        }
                    }
                    px = x
                    py = y
                }
            }
        }

        val way = bestWay ?: return null

        // 1) limite specifico per il verso di marcia, 2) limite generale della strada
        val directional = if (headingDeg == null) null
        else if (bestForward) way.maxspeedForward else way.maxspeedBackward
        var limit: Int? = parseMaxspeed(directional) ?: parseMaxspeed(way.maxspeed)

        // Direzione sconosciuta: se i due versi hanno limiti diversi, prendo il più basso
        if (limit == null && headingDeg == null) {
            val f = parseMaxspeed(way.maxspeedForward)
            val b = parseMaxspeed(way.maxspeedBackward)
            limit = if (f != null && b != null) min(f, b) else (f ?: b)
        }

        // 3) tipo di limite scritto a parte (es. "IT:urban" = centro abitato)
        if (limit == null) limit = parseMaxspeed(way.zoneTag)

        var fromTag = limit != null
        var note: String? = null
        if (limit == null) {
            val urban = way.highway in URBAN_HINT_TYPES || looksUrban(ways, lat, lon)
            limit = defaultForHighway(way.highway, urban)
            if (limit != null && way.highway !in setOf("motorway", "residential", "living_street")) {
                note = if (urban) "centro abitato" else "fuori dal centro abitato"
            }
        }
        if (limit == null) fromTag = false

        return LimitMatch(limit, fromTag, way.name, way.highway, bestDist, note)
    }
}

/**
 * Tiene il limite "in uso" e lo cambia solo quando la nuova lettura si ripete,
 * per non farsi ingannare da un singolo salto del GPS o da un incrocio.
 */
class LimitTracker(
    private val confirmFixes: Int = 3,
    private val holdMs: Long = 20_000L
) {
    /** Limite in uso: 0 = nessun limite noto. */
    var limitKmh: Int = 0
        private set

    var roadName: String? = null
        private set

    /** True se il limite è scritto nei dati, false se dedotto dal tipo di strada. */
    var fromTag: Boolean = false
        private set

    /** Per i limiti stimati: "centro abitato" / "fuori dal centro abitato". */
    var estimateNote: String? = null
        private set

    /** True se una strada è stata riconosciuta di recente. */
    var hasRoad: Boolean = false
        private set

    private var candidate = -1
    private var candidateCount = 0
    private var lastSeenAt = 0L

    /** @return true se il limite in uso è cambiato. */
    fun update(match: LimitMatch?, nowMs: Long): Boolean {
        if (match == null) {
            // Nessuna strada vicina (galleria, GPS impreciso): tengo l'ultimo valore per un po'
            return if (hasRoad && nowMs - lastSeenAt > holdMs) clear() else false
        }

        lastSeenAt = nowMs
        val value = match.limitKmh ?: 0

        if (!hasRoad) {
            hasRoad = true
            return apply(value, match)
        }

        if (value == limitKmh) {
            candidate = -1
            candidateCount = 0
            roadName = match.roadName
            fromTag = match.fromTag
            estimateNote = match.estimateNote
            return false
        }

        if (value == candidate) candidateCount += 1 else {
            candidate = value
            candidateCount = 1
        }
        return if (candidateCount >= confirmFixes) apply(value, match) else false
    }

    /** Dimentica tutto (per esempio quando il GPS si spegne). */
    fun clear(): Boolean {
        val changed = limitKmh != 0
        limitKmh = 0
        roadName = null
        fromTag = false
        estimateNote = null
        hasRoad = false
        candidate = -1
        candidateCount = 0
        return changed
    }

    private fun apply(value: Int, match: LimitMatch): Boolean {
        val changed = value != limitKmh
        limitKmh = value
        roadName = match.roadName
        fromTag = match.fromTag
        estimateNote = match.estimateNote
        candidate = -1
        candidateCount = 0
        return changed
    }
}
