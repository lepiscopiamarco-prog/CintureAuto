package it.marco.cintureauto

import java.util.Locale
import kotlin.math.roundToLong

/*
 * Registro dei viaggi: logica "pura" (senza Android), così si può provare sul computer.
 */

/** Un viaggio completo, dal collegamento Bluetooth all'auto fino alla disconnessione. */
data class Trip(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val maxSpeedKmh: Double,
    /** Secondi in cui l'auto si stava davvero muovendo (esclusi semafori, soste, code ferme). */
    val movingSec: Long,
    /** Quante volte è suonato l'avviso di limite superato; -1 = controllo velocità spento. */
    val overspeedCount: Int,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double
) {
    val durationSec: Long get() = ((endMs - startMs) / 1000L).coerceAtLeast(0L)
    val distanceKm: Double get() = distanceM / 1000.0
    val avgMovingKmh: Double get() = if (movingSec > 0L) distanceM / movingSec * 3.6 else 0.0
    val hasEnd: Boolean get() = endLat.isFinite() && endLon.isFinite()
    val speedMonitored: Boolean get() = overspeedCount >= 0

    /** Scarta i "viaggi" finti: pochi metri o pochi secondi (es. Bluetooth che si riconnette). */
    fun worthKeeping(): Boolean =
        distanceM >= TripRecorder.MIN_KEEP_DISTANCE_M && durationSec >= TripRecorder.MIN_KEEP_SEC
}

/** Accumula le letture del GPS di un viaggio. Non usa Android: riceve solo numeri. */
class TripRecorder(private val startMs: Long) {

    companion object {
        /** Letture meno precise di così (in metri) vengono ignorate. */
        const val MAX_ACCURACY_M = 50.0

        /** Sotto questa velocità l'auto è considerata ferma: il "rumore" del GPS non fa km. */
        const val MOVING_KMH = 3.0

        /** Se tra due letture passa più tempo (galleria, garage) la distanza non si calcola. */
        const val MAX_GAP_SEC = 120.0

        /** Velocità implicita oltre la quale una lettura è considerata un "salto" del GPS (70 m/s = 252 km/h). */
        const val MAX_PLAUSIBLE_MS = 70.0

        /** Velocità massima plausibile per un'auto: oltre, il dato è scartato. */
        const val MAX_PLAUSIBLE_KMH = 300.0

        /** Al massimo questo tempo per lettura viene contato come "in movimento". */
        const val MOVE_STEP_CAP_SEC = 15.0

        /** Dopo tante letture "saltate" di fila si riparte dalla lettura corrente. */
        const val MAX_REJECTED_STREAK = 5

        const val MIN_KEEP_DISTANCE_M = 300.0
        const val MIN_KEEP_SEC = 60L
    }

    var distanceM: Double = 0.0
        private set
    var maxSpeedKmh: Double = 0.0
        private set

    private var movingSec = 0.0
    private var overspeed = 0
    private var monitored = false

    private var startLat = Double.NaN
    private var startLon = Double.NaN
    private var endLat = Double.NaN
    private var endLon = Double.NaN

    private var hasLast = false
    private var lastTimeMs = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastSpeed = 0.0
    private var rejectedStreak = 0

    /**
     * @param timeMs istante della lettura, in millisecondi (qualunque orologio, purché sempre lo stesso)
     * @param speedKmh velocità letta dal GPS
     * @param accuracyM precisione stimata in metri (0 = non nota)
     */
    fun onFix(timeMs: Long, lat: Double, lon: Double, speedKmh: Double, accuracyM: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        if (accuracyM.isFinite() && accuracyM > MAX_ACCURACY_M) return
        val speed = if (speedKmh.isFinite() && speedKmh >= 0.0) speedKmh else 0.0

        if (hasLast) {
            val dt = (timeMs - lastTimeMs) / 1000.0
            if (dt <= 0.0) return
            if (dt <= MAX_GAP_SEC) {
                val d = SpeedLimitLogic.distanceMeters(lastLat, lastLon, lat, lon)
                if (d / dt > MAX_PLAUSIBLE_MS) {
                    rejectedStreak += 1
                    if (rejectedStreak < MAX_REJECTED_STREAK) return
                    // troppe letture "impossibili" di fila: forse era quella vecchia a essere sbagliata
                } else if (maxOf(speed, lastSpeed) >= MOVING_KMH) {
                    distanceM += d
                    movingSec += minOf(dt, MOVE_STEP_CAP_SEC)
                }
            }
        }
        rejectedStreak = 0

        hasLast = true
        lastTimeMs = timeMs
        lastLat = lat
        lastLon = lon
        lastSpeed = speed

        if (startLat.isNaN()) {
            startLat = lat
            startLon = lon
        }
        endLat = lat
        endLon = lon
        if (speed <= MAX_PLAUSIBLE_KMH && speed > maxSpeedKmh) maxSpeedKmh = speed
    }

    /** Il controllo velocità è attivo: da ora i superamenti si contano (anche se sono zero). */
    fun markMonitored() {
        monitored = true
    }

    /** È iniziato un nuovo superamento del limite (non conta le ripetizioni dell'avviso). */
    fun noteOverspeed() {
        monitored = true
        overspeed += 1
    }

    /** Fotografia del viaggio fino a questo momento. */
    fun snapshot(nowMs: Long): Trip = Trip(
        startMs = startMs,
        endMs = maxOf(nowMs, startMs),
        distanceM = distanceM,
        maxSpeedKmh = maxSpeedKmh,
        movingSec = movingSec.roundToLong(),
        overspeedCount = if (monitored) overspeed else -1,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon
    )

    fun finish(nowMs: Long): Trip = snapshot(nowMs)
}

/** Trasforma un viaggio in una riga di testo e viceversa (per salvarlo sul telefono). */
object TripCodec {

    private const val TAG = "T1"

    fun encode(t: Trip): String = listOf(
        TAG,
        t.startMs.toString(),
        t.endMs.toString(),
        num(t.distanceM, 1),
        num(t.maxSpeedKmh, 1),
        t.movingSec.toString(),
        t.overspeedCount.toString(),
        num(t.startLat, 6),
        num(t.startLon, 6),
        num(t.endLat, 6),
        num(t.endLon, 6)
    ).joinToString(";")

    /** Restituisce null se la riga è vuota o rovinata. */
    fun decode(line: String): Trip? {
        val p = line.trim().split(';')
        if (p.size < 11 || p[0] != TAG) return null
        return try {
            Trip(
                startMs = p[1].toLong(),
                endMs = p[2].toLong(),
                distanceM = parse(p[3]),
                maxSpeedKmh = parse(p[4]),
                movingSec = p[5].toLong(),
                overspeedCount = p[6].toInt(),
                startLat = parse(p[7]),
                startLon = parse(p[8]),
                endLat = parse(p[9]),
                endLon = parse(p[10])
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun num(v: Double, decimals: Int): String =
        if (v.isFinite()) String.format(Locale.US, "%.${decimals}f", v) else "n"

    private fun parse(s: String): Double = if (s == "n") Double.NaN else s.toDouble()
}
