package it.marco.cintureauto

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Le strade attorno a un punto, scaricate in un colpo solo. */
class RoadData(
    val centerLat: Double,
    val centerLon: Double,
    val radiusM: Int,
    val ways: List<RoadWay>
)

/**
 * Scarica da OpenStreetMap (tramite il servizio pubblico "Overpass") le strade attorno alla
 * posizione, con i loro limiti di velocità. Gratuito, nessuna chiave.
 *
 * Va chiamato da un thread in background (fa rete). Alla rete viene mandata solo la posizione
 * arrotondata a circa 100 metri.
 */
object OverpassClient {

    // Il primo dichiara di non avere limiti di richieste; il secondo è quello principale della
    // comunità OpenStreetMap, che chiede un uso moderato (usato solo come ripiego).
    private val ENDPOINTS = listOf(
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    private const val HIGHWAY_TYPES =
        "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
            "tertiary|tertiary_link|unclassified|residential|living_street"

    private const val USER_AGENT = "CintureAuto/1.1 (app personale Android)"

    fun buildQuery(lat: Double, lon: Double, radiusM: Int): String {
        return String.format(
            Locale.US,
            "[out:json][timeout:20];way(around:%d,%.3f,%.3f)[\"highway\"~\"^(%s)\$\"];out tags geom;",
            radiusM, lat, lon, HIGHWAY_TYPES
        )
    }

    /** @return le strade attorno a (lat, lon), oppure null se la rete non risponde. */
    fun fetch(lat: Double, lon: Double, radiusM: Int): RoadData? {
        // Posizione arrotondata a 0,001° (~100 m): basta per l'area di ricerca e rivela meno
        val cLat = Math.round(lat * 1000.0) / 1000.0
        val cLon = Math.round(lon * 1000.0) / 1000.0
        val body = "data=" + URLEncoder.encode(buildQuery(cLat, cLon, radiusM), "UTF-8")

        for (endpoint in ENDPOINTS) {
            val text = post(endpoint, body)
            if (text != null) {
                val ways = parse(text)
                if (ways != null) return RoadData(cLat, cLon, radiusM, ways)
            }
        }
        return null
    }

    private fun post(endpoint: String, body: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val c = URI(endpoint).toURL().openConnection() as HttpURLConnection
            connection = c
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode == 200) {
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun JSONObject.tag(key: String): String? {
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }

    /** Legge la risposta di Overpass. Restituisce null se è un errore o è incompleta. */
    fun parse(text: String): List<RoadWay>? {
        try {
            val root = JSONObject(text)
            // Se il server è andato in timeout restituisce un risultato parziale con una nota
            if (root.optString("remark", "").contains("error", ignoreCase = true)) return null
            val elements = root.optJSONArray("elements") ?: return null

            val out = ArrayList<RoadWay>(elements.length())
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i)
                if (el != null && el.optString("type", "") == "way") {
                    val way = readWay(el)
                    if (way != null) out.add(way)
                }
            }
            return out
        } catch (e: Exception) {
            return null
        }
    }

    private fun readWay(el: JSONObject): RoadWay? {
        val geometry = el.optJSONArray("geometry") ?: return null
        val tags = el.optJSONObject("tags") ?: return null
        val highway = tags.optString("highway", "")
        val n = geometry.length()
        if (highway.isEmpty() || n < 2) return null

        val lats = DoubleArray(n)
        val lons = DoubleArray(n)
        for (k in 0 until n) {
            val p = geometry.optJSONObject(k) ?: return null
            lats[k] = p.optDouble("lat")
            lons[k] = p.optDouble("lon")
        }

        return RoadWay(
            id = el.optLong("id"),
            highway = highway,
            name = tags.tag("name"),
            maxspeed = tags.tag("maxspeed"),
            maxspeedForward = tags.tag("maxspeed:forward"),
            maxspeedBackward = tags.tag("maxspeed:backward"),
            lats = lats,
            lons = lons
        )
    }
}
