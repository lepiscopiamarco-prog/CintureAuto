package it.marco.cintureauto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Pronuncia una frase con la sintesi vocale italiana del telefono.
 * La voce, la velocità, il tono e il volume si regolano dalle impostazioni dell'app.
 * L'audio esce dallo stesso canale del suono, quindi dalle casse dell'auto.
 */
class VoiceAnnouncer(context: Context, private val prefs: Prefs) {

    companion object {
        private const val INIT_PENDING = 0
        private const val INIT_OK = 1
        private const val INIT_FAILED = -1

        /** Tempo massimo di una frase: dopo questo si rilasciano comunque audio e CPU. */
        private const val MAX_SESSION_MS = 15_000L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    private var initState = INIT_PENDING
    private var languageOk = false

    // stato della frase in corso
    private var active = false
    private var currentId: String? = null
    private var pendingText: String? = null
    private var speakRunnable: Runnable? = null
    private var failsafe: Runnable? = null
    private var onFinished: (() -> Unit)? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            finishIfCurrent(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            finishIfCurrent(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            finishIfCurrent(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            finishIfCurrent(utteranceId)
        }
    }

    init {
        try {
            tts = TextToSpeech(appContext) { status -> handler.post { onEngineInit(status) } }
        } catch (e: Exception) {
            tts = null
            initState = INIT_FAILED
        }
    }

    // -------------------------------------------------------------------------------------------
    // Motore vocale
    // -------------------------------------------------------------------------------------------

    private fun onEngineInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            initState = INIT_FAILED
            endSession()
            return
        }
        try {
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(listener)
            val r = engine.setLanguage(Locale.ITALY)
            languageOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            initState = INIT_OK
        } catch (e: Exception) {
            initState = INIT_FAILED
            endSession()
            return
        }

        // Se la frase è arrivata mentre il motore si stava avviando, ora la dico
        val waiting = pendingText
        pendingText = null
        if (waiting != null && active) speakNow(waiting)
    }

    /** True se il motore è pronto e la voce italiana è disponibile. */
    fun isReady(): Boolean = initState == INIT_OK && languageOk

    /** Spiega in italiano semplice perché la voce non è disponibile. */
    fun problemText(): String {
        return when {
            initState == INIT_PENDING ->
                "Il motore vocale si sta avviando: riprova tra un istante."
            initState == INIT_FAILED ->
                "Sul telefono manca il motore di sintesi vocale. Installa «Sintesi vocale di Google» dal Play Store."
            !languageOk ->
                "La voce italiana non è installata. Apri le impostazioni di sintesi vocale e scarica l'italiano."
            else ->
                "Nessuna voce italiana trovata."
        }
    }

    /** Le voci italiane installate, prima quelle che funzionano senza internet. */
    fun italianVoices(): List<Voice> {
        val engine = tts
        if (engine == null || initState != INIT_OK) return emptyList()
        return try {
            val all: Set<Voice>? = engine.getVoices()
            if (all == null) {
                emptyList()
            } else {
                all.filter { it.locale.language == "it" }
                    .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Pronuncia
    // -------------------------------------------------------------------------------------------

    /**
     * Pronuncia [text] dopo [delayMs]. Da subito tiene sveglia la CPU e si prenota l'audio,
     * così il passaggio dal suono alla voce è continuo anche a telefono bloccato.
     */
    fun announce(text: String, delayMs: Long, onFinished: (() -> Unit)? = null) {
        cancel()
        active = true
        this.onFinished = onFinished
        beginHold()

        val speak = Runnable {
            speakRunnable = null
            speakNow(text)
        }
        speakRunnable = speak
        handler.postDelayed(speak, delayMs)

        val end = Runnable { endSession() }
        failsafe = end
        handler.postDelayed(end, MAX_SESSION_MS)
    }

    /** Interrompe la frase in corso (o in attesa). */
    fun cancel() {
        onFinished = null
        pendingText = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            // ignora
        }
        if (active) endSession() else releaseHold()
    }

    fun shutdown() {
        cancel()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // ignora
        }
        tts = null
        initState = INIT_FAILED
    }

    private fun speakNow(text: String) {
        val engine = tts
        if (engine == null || initState == INIT_FAILED) {
            endSession()
            return
        }
        if (initState == INIT_PENDING) {
            pendingText = text
            return
        }
        if (!languageOk) {
            endSession()
            return
        }

        try {
            // Impostare la lingua riporta alla voce predefinita: poi si applica quella scelta
            engine.setLanguage(Locale.ITALY)
            val wanted = prefs.voiceName
            if (wanted.isNotEmpty()) {
                val chosen = engine.getVoices()?.firstOrNull { it.name == wanted }
                if (chosen != null) engine.setVoice(chosen)
            }
            engine.setSpeechRate(prefs.voiceRatePercent.coerceIn(30, 200) / 100f)
            engine.setPitch(prefs.voicePitchPercent.coerceIn(30, 200) / 100f)

            val id = "cinture-" + System.nanoTime()
            currentId = id
            val params = Bundle()
            params.putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                prefs.voiceVolumePercent.coerceIn(0, 100) / 100f
            )
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
            if (result != TextToSpeech.SUCCESS) endSession()
        } catch (e: Exception) {
            endSession()
        }
    }

    private fun finishIfCurrent(utteranceId: String?) {
        handler.post {
            if (utteranceId != null && utteranceId == currentId) endSession()
        }
    }

    private fun endSession() {
        active = false
        currentId = null
        pendingText = null
        speakRunnable?.let { handler.removeCallbacks(it) }
        speakRunnable = null
        failsafe?.let { handler.removeCallbacks(it) }
        failsafe = null
        releaseHold()
        val callback = onFinished
        onFinished = null
        callback?.invoke()
    }

    // -------------------------------------------------------------------------------------------
    // Audio e CPU
    // -------------------------------------------------------------------------------------------

    private fun beginHold() {
        if (wakeLock == null) {
            try {
                val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CintureAuto:voce")
                wl.acquire(MAX_SESSION_MS + 2_000L)
                wakeLock = wl
            } catch (e: Exception) {
                // senza, la voce può ritardare a telefono bloccato, ma funziona
            }
        }
        if (focusRequest == null) {
            try {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(request)
                focusRequest = request
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    private fun releaseHold() {
        focusRequest?.let {
            try {
                audioManager.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                // ignora
            }
        }
        focusRequest = null

        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (e: Exception) {
                // ignora
            }
        }
        wakeLock = null
    }
}
