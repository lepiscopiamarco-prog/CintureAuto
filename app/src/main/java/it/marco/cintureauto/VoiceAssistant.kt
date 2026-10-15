package it.marco.cintureauto

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Il modello vocale italiano (Vosk, licenza Apache 2.0): circa 48 MB, si scarica una volta
 * sola e poi il riconoscimento funziona senza internet. L'audio non lascia mai il telefono.
 */
object VoiceModel {

    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
    private const val DIR_NAME = "vosk-it"
    private const val READY_MARKER = "pronto.txt"

    /** Stato del download, letto dalla schermata. */
    @Volatile
    var progressText: String = ""

    @Volatile
    var downloading: Boolean = false

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME)

    fun isReady(context: Context): Boolean = File(dir(context), READY_MARKER).exists()

    /** Scarica ed estrae il modello. Fa rete: va chiamato da un thread in background. */
    fun download(context: Context): Boolean {
        if (downloading) return false
        downloading = true
        val target = dir(context)
        val tmp = File(context.applicationContext.filesDir, "$DIR_NAME-tmp")
        var conn: HttpURLConnection? = null
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            progressText = "Download del modello vocale in corso…"
            conn = URI(URL).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) {
                progressText = "Download non riuscito (risposta ${conn.responseCode}). Riprova più tardi."
                return false
            }
            val total = conn.contentLengthLong
            var read = 0L
            val base = tmp.canonicalPath + File.separator
            ZipInputStream(CountingStream(conn.inputStream) { n ->
                read = n
                if (total > 0) progressText = "Download del modello vocale: ${read * 100 / total}%"
            }).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // il file zip contiene una cartella principale: la si toglie
                    val name = entry.name.substringAfter('/', "")
                    if (name.isEmpty()) continue
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(base)) continue   // percorso non sicuro
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            if (!File(tmp, "am").exists() && !File(tmp, "conf").exists()) {
                progressText = "Il file scaricato non sembra un modello vocale valido."
                return false
            }
            File(tmp, READY_MARKER).writeText("ok")
            target.deleteRecursively()
            if (!tmp.renameTo(target)) {
                progressText = "Impossibile salvare il modello (memoria piena?)."
                return false
            }
            progressText = "Modello vocale pronto."
            return true
        } catch (e: Exception) {
            progressText = "Download non riuscito: controlla la connessione (meglio il Wi-Fi) e riprova."
            return false
        } finally {
            conn?.disconnect()
            tmp.deleteRecursively()
            downloading = false
        }
    }

    fun delete(context: Context) {
        dir(context).deleteRecursively()
        progressText = ""
    }

    /** Conta i byte letti, per mostrare la percentuale. */
    private class CountingStream(
        private val inner: java.io.InputStream,
        private val onCount: (Long) -> Unit
    ) : java.io.InputStream() {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        private fun advance(n: Long) {
            count += n
            if (count - lastReport > 512_000L) {
                lastReport = count
                onCount(count)
            }
        }

        override fun close() = inner.close()
    }
}

/**
 * Ascolta il microfono senza sosta (solo con l'auto collegata) e riconosce «Ehi Mercedes».
 * Tutto il riconoscimento avviene sul telefono. I risultati arrivano sul thread principale.
 */
class VoiceAssistant(context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** È stato detto "Ehi Mercedes" seguito da una domanda (o la domanda dopo "Dimmi"). */
        fun onCommand(command: VoiceCommands.Command)

        /** È stato detto solo "Ehi Mercedes": rispondere "Dimmi" e aspettare la domanda. */
        fun onWakeOnly()

        fun onStatus(text: String)
    }

    companion object {
        private const val SAMPLE_RATE = 16000

        /** Dopo "Dimmi" si aspetta la domanda per questo tempo. */
        private const val AWAIT_COMMAND_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var resetRequested = false

    private var thread: Thread? = null
    private var awaitingUntil = 0L

    private val resume = Runnable {
        paused = false
        resetRequested = true
    }

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        paused = false
        val t = Thread({ loop() }, "ascolto-ehi-mercedes")
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(resume)
        thread?.interrupt()
        thread = null
    }

    /** Smette di ascoltare per un po' (mentre l'app stessa parla o suona). */
    fun pauseFor(ms: Long) {
        paused = true
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, ms)
    }

    /** Riprende ad ascoltare tra poco (chiamato quando l'app ha finito di parlare). */
    fun resumeSoon() {
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, 400L)
    }

    private fun post(block: () -> Unit) {
        handler.post { if (running) block() }
    }

    private fun loop() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var recorder: AudioRecord? = null
        try {
            post { callbacks.onStatus("Carico il modello vocale…") }
            model = Model(VoiceModel.dir(appContext).absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE)
            )
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                post { callbacks.onStatus("Microfono non disponibile.") }
                return
            }
            rec.startRecording()
            post { callbacks.onStatus("In ascolto: di' «Ehi Mercedes»") }

            val buffer = ShortArray(SAMPLE_RATE / 5)   // 0,2 secondi
            var errors = 0
            while (running && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n < 0) {
                    errors += 1
                    if (errors > 50) {
                        post { callbacks.onStatus("Il microfono ha smesso di funzionare: riprovo più tardi.") }
                        break
                    }
                    Thread.sleep(200L)
                    continue
                }
                errors = 0
                if (paused || n == 0) continue
                if (resetRequested) {
                    recognizer.reset()
                    resetRequested = false
                }
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val text = try {
                        JSONObject(recognizer.getResult()).optString("text", "")
                    } catch (e: Exception) {
                        ""
                    }
                    if (text.isNotBlank()) post { handleText(text) }
                }
            }
        } catch (e: InterruptedException) {
            // fermato
        } catch (e: SecurityException) {
            post { callbacks.onStatus("Manca il permesso del microfono.") }
        } catch (e: Throwable) {
            post { callbacks.onStatus("Riconoscimento vocale non disponibile su questo telefono.") }
        } finally {
            try {
                recorder?.stop()
            } catch (e: Exception) {
                // ignora
            }
            try {
                recorder?.release()
            } catch (e: Exception) {
                // ignora
            }
            try {
                recognizer?.close()
            } catch (e: Throwable) {
                // ignora
            }
            try {
                model?.close()
            } catch (e: Throwable) {
                // ignora
            }
        }
    }

    private fun handleText(text: String) {
        val now = SystemClock.elapsedRealtime()
        val afterWake = VoiceCommands.afterWakeWord(text)
        if (afterWake != null) {
            val command = VoiceCommands.parse(afterWake)
            if (command != VoiceCommands.Command.UNKNOWN) {
                awaitingUntil = 0L
                callbacks.onCommand(command)
            } else {
                awaitingUntil = now + AWAIT_COMMAND_MS
                callbacks.onWakeOnly()
            }
            return
        }
        if (now < awaitingUntil) {
            awaitingUntil = 0L
            callbacks.onCommand(VoiceCommands.parse(text))
        }
    }
}
