package it.marco.cintureauto

import java.util.Locale

/**
 * Dove hai lasciato l'auto. Logica "pura" (senza Android).
 *
 * @param fixTimeMs istante in cui il GPS ha rilevato quel punto
 * @param savedAtMs istante in cui è stato salvato (cioè quando il Bluetooth si è disconnesso)
 */
data class ParkingSpot(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val fixTimeMs: Long,
    val savedAtMs: Long
) {
    /** Da quanti secondi il GPS non aggiornava la posizione quando il Bluetooth si è disconnesso. */
    val fixAgeAtSaveSec: Long get() = ((savedAtMs - fixTimeMs) / 1000L).coerceAtLeast(0L)

    /** Posizione da prendere con cautela: GPS vecchio (garage, galleria) o poco preciso. */
    val isApproximate: Boolean
        get() = fixAgeAtSaveSec > APPROX_AGE_SEC || (accuracyM.isFinite() && accuracyM > APPROX_ACCURACY_M)

    companion object {
        const val APPROX_AGE_SEC = 120L
        const val APPROX_ACCURACY_M = 100.0
    }
}

object ParkingCodec {

    private const val TAG = "P1"

    fun encode(s: ParkingSpot): String = listOf(
        TAG,
        String.format(Locale.US, "%.6f", s.lat),
        String.format(Locale.US, "%.6f", s.lon),
        if (s.accuracyM.isFinite()) String.format(Locale.US, "%.1f", s.accuracyM) else "n",
        s.fixTimeMs.toString(),
        s.savedAtMs.toString()
    ).joinToString(";")

    /** Restituisce null se non c'è nessuna posizione salvata o il testo è rovinato. */
    fun decode(text: String?): ParkingSpot? {
        if (text.isNullOrBlank()) return null
        val p = text.trim().split(';')
        if (p.size < 6 || p[0] != TAG) return null
        return try {
            val lat = p[1].toDouble()
            val lon = p[2].toDouble()
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            ParkingSpot(
                lat = lat,
                lon = lon,
                accuracyM = if (p[3] == "n") Double.NaN else p[3].toDouble(),
                fixTimeMs = p[4].toLong(),
                savedAtMs = p[5].toLong()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/** Indirizzi per aprire un punto nelle app di mappe. */
object MapLinks {

    private fun c(v: Double): String = String.format(Locale.US, "%.6f", v)

    /** Apre l'app di mappe predefinita del telefono sul punto indicato. */
    fun geoUri(lat: Double, lon: Double, label: String): String =
        "geo:${c(lat)},${c(lon)}?q=${c(lat)},${c(lon)}(${label.replace(" ", "%20")})"

    /** Pagina web di Google Maps sul punto indicato (ripiego se non c'è un'app di mappe). */
    fun webPoint(lat: Double, lon: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${c(lat)},${c(lon)}"

    /** Indicazioni a piedi fino al punto indicato. */
    fun webWalkTo(lat: Double, lon: Double): String =
        "https://www.google.com/maps/dir/?api=1&destination=${c(lat)},${c(lon)}&travelmode=walking"
}
