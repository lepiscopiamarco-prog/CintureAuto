package it.marco.cintureauto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Riproduce un suono sull'uscita multimediale del telefono.
 * Con il telefono collegato al Bluetooth dell'auto, l'audio esce dagli altoparlanti dell'auto.
 * Abbassa temporaneamente (duck) la musica in corso e poi restituisce l'audio.
 */
class SoundPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var releaseRunnable: Runnable? = null

    fun play(samples: ShortArray, volumePercent: Int) {
        stop()
        try {
            // Tiene sveglia la CPU per i pochi secondi in cui il suono è in riproduzione
            val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CintureAuto:suono")
            wl.acquire(15_000L)
            wakeLock = wl

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SoundSynth.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            t.write(samples, 0, samples.size)
            t.setVolume(volumePercent.coerceIn(0, 100) / 100f)
            t.play()
            track = t

            val durationMs = samples.size * 1000L / SoundSynth.SAMPLE_RATE
            val r = Runnable { stop() }
            releaseRunnable = r
            handler.postDelayed(r, durationMs + 400L)
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        releaseRunnable?.let { handler.removeCallbacks(it) }
        releaseRunnable = null

        track?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // già fermo
            }
            try {
                it.release()
            } catch (e: Exception) {
                // già rilasciato
            }
        }
        track = null

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
