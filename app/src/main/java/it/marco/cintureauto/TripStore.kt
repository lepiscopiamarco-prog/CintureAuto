package it.marco.cintureauto

import android.content.Context
import java.io.File

/**
 * Archivio dei viaggi, salvato come semplice file di testo nella memoria privata dell'app
 * (nessun altro programma può leggerlo). Una riga per viaggio.
 *
 *  - trips.txt         i viaggi conclusi
 *  - trip_current.txt  il viaggio in corso, salvato ogni tanto: se Android chiude l'app a metà
 *                      strada, al riavvio il viaggio non va perso
 */
class TripStore(context: Context) {

    private val dir: File = context.applicationContext.filesDir

    private fun tripsFile() = File(dir, "trips.txt")
    private fun currentFile() = File(dir, "trip_current.txt")
    private fun currentTmpFile() = File(dir, "trip_current.tmp")

    companion object {
        /** Il servizio e la schermata usano lo stesso archivio: un solo accesso alla volta. */
        private val lock = Any()

        /** Si tengono al massimo gli ultimi viaggi, per non far crescere il file all'infinito. */
        private const val MAX_TRIPS = 5000
        private const val TRIM_CHECK_BYTES = 500_000L
    }

    /** Tutti i viaggi, dal più vecchio al più recente. */
    fun loadAll(): List<Trip> {
        synchronized(lock) {
            val f = tripsFile()
            if (!f.exists()) return emptyList()
            return try {
                f.readLines(Charsets.UTF_8).mapNotNull { TripCodec.decode(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun append(trip: Trip) {
        synchronized(lock) {
            try {
                val f = tripsFile()
                f.appendText(TripCodec.encode(trip) + "\n", Charsets.UTF_8)
                if (f.length() > TRIM_CHECK_BYTES) {
                    val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                    if (lines.size > MAX_TRIPS) {
                        f.writeText(lines.takeLast(MAX_TRIPS).joinToString("\n") + "\n", Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) {
                // memoria piena o file non scrivibile: il viaggio va perso, l'app continua
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            try {
                tripsFile().delete()
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    // ---- viaggio in corso ----

    fun saveCurrent(trip: Trip) {
        synchronized(lock) {
            try {
                // si scrive in un file temporaneo e poi lo si rinomina: niente file a metà
                val tmp = currentTmpFile()
                tmp.writeText(TripCodec.encode(trip) + "\n", Charsets.UTF_8)
                val cur = currentFile()
                if (!tmp.renameTo(cur)) {
                    cur.delete()
                    tmp.renameTo(cur)
                }
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    fun loadCurrent(): Trip? {
        synchronized(lock) {
            val f = currentFile()
            if (!f.exists()) return null
            return try {
                TripCodec.decode(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun clearCurrent() {
        synchronized(lock) {
            try {
                currentFile().delete()
                currentTmpFile().delete()
            } catch (e: Exception) {
                // ignora
            }
        }
    }
}
