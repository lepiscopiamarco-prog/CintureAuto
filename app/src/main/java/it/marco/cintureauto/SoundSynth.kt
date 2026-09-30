package it.marco.cintureauto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Genera i suoni al volo (nessun file audio da includere, nessun problema di copyright).
 *  - chime(): il "ding" delle cinture di sicurezza degli aerei
 *  - alarm(): tre bip rapidi e acuti per l'avviso di velocità
 */
object SoundSynth {

    const val SAMPLE_RATE = 44100

    /** Silenzio iniziale: la connessione Bluetooth audio spesso "mangia" i primi istanti. */
    private const val LEAD_IN_SEC = 0.5

    private class Partial(val ratio: Double, val amp: Double, val decay: Double)

    // Armoniche di una campana/chime: la fondamentale decade lenta, le altre più in fretta.
    private val bell = listOf(
        Partial(1.0, 1.00, 3.0),
        Partial(2.0, 0.45, 4.5),
        Partial(3.0, 0.20, 6.0),
        Partial(4.07, 0.10, 8.0)
    )

    private fun newBuffer(seconds: Double): DoubleArray =
        DoubleArray(((LEAD_IN_SEC + seconds) * SAMPLE_RATE).toInt())

    private fun addBell(buf: DoubleArray, startSec: Double, freq: Double, durSec: Double, gain: Double) {
        val start = ((LEAD_IN_SEC + startSec) * SAMPLE_RATE).toInt()
        val n = (durSec * SAMPLE_RATE).toInt()
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val attack = if (t < 0.004) t / 0.004 else 1.0
            var s = 0.0
            for (p in bell) {
                s += p.amp * exp(-p.decay * t) * sin(2.0 * PI * freq * p.ratio * t)
            }
            buf[idx] += gain * attack * s
        }
    }

    private fun addBeep(buf: DoubleArray, startSec: Double, freq: Double, durSec: Double, gain: Double) {
        val start = ((LEAD_IN_SEC + startSec) * SAMPLE_RATE).toInt()
        val n = (durSec * SAMPLE_RATE).toInt()
        val ramp = 0.010
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val remaining = durSec - t
            val env = when {
                t < ramp -> t / ramp
                remaining < ramp -> max(0.0, remaining / ramp)
                else -> 1.0
            }
            // un pizzico di seconda armonica per un bip più "presente" sugli altoparlanti dell'auto
            val s = sin(2.0 * PI * freq * t) + 0.35 * sin(2.0 * PI * freq * 2.0 * t)
            buf[idx] += gain * env * s
        }
    }

    private fun finish(buf: DoubleArray): ShortArray {
        var peak = 0.0
        for (v in buf) peak = max(peak, abs(v))
        val scale = if (peak > 0.0) 0.9 / peak else 1.0
        val fadeSamples = (0.030 * SAMPLE_RATE).toInt()
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            var v = buf[i] * scale
            val fromEnd = buf.size - 1 - i
            if (fromEnd < fadeSamples) v *= fromEnd.toDouble() / fadeSamples
            val s = (v * 32767.0).toInt().coerceIn(-32768, 32767)
            out[i] = s.toShort()
        }
        return out
    }

    /** style: 0 = ding singolo, 1 = ding-dong (due note), 2 = doppio ding */
    fun chime(style: Int): ShortArray {
        return when (style) {
            1 -> {
                val buf = newBuffer(2.4)
                addBell(buf, 0.00, 1318.5, 1.7, 1.0)   // "ding" (Mi)
                addBell(buf, 0.55, 1046.5, 1.7, 1.0)   // "dong" (Do)
                finish(buf)
            }
            2 -> {
                val buf = newBuffer(2.4)
                addBell(buf, 0.00, 1318.5, 1.4, 1.0)
                addBell(buf, 0.75, 1318.5, 1.6, 1.0)
                finish(buf)
            }
            else -> {
                val buf = newBuffer(1.8)
                addBell(buf, 0.00, 1318.5, 1.8, 1.0)
                finish(buf)
            }
        }
    }

    /** Tre bip rapidi e acuti: chiaramente diversi dal "ding" delle cinture. */
    fun alarm(): ShortArray {
        val buf = newBuffer(1.0)
        addBeep(buf, 0.00, 1700.0, 0.18, 1.0)
        addBeep(buf, 0.28, 1700.0, 0.18, 1.0)
        addBeep(buf, 0.56, 1700.0, 0.30, 1.0)
        return finish(buf)
    }
}
