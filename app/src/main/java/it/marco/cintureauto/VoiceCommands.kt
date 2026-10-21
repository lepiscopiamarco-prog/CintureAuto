package it.marco.cintureauto

import java.text.Normalizer
import java.util.Locale

/*
 * Assistente vocale «Ehi Mercedes»: logica "pura" (senza Android), così si prova sul computer.
 * Riconosce la frase di attivazione nel testo trascritto, capisce la domanda e prepara la risposta.
 */
object VoiceCommands {

    enum class Command { SPEED_LIMIT, WEATHER_TODAY, WEATHER_TOMORROW, UNKNOWN }

    /** Parole di saluto che possono precedere "Mercedes" nella trascrizione. */
    private const val HEY = "(?:ehi|ei|hey|hei|ey|eh|e|ai|hai|ehy|ok|okay|ciao|oi|ahi)"

    /** "Mercedes" come può uscire dalla trascrizione: mercedes, mercede, mersedes, merce des… */
    private const val MERC = "(?:mer[cs]e?d\\w*|merce\\s+des\\w*|mercedesbenz)"

    /** Saluto + Mercedes, in qualsiasi punto della frase. */
    private val WAKE_WITH_HEY = Regex("(?:^|\\s)$HEY\\s+$MERC")

    /** Solo "Mercedes" all'inizio della frase (il saluto a volte si perde). */
    private val WAKE_AT_START = Regex("^$MERC")

    /** "Mercedes" in qualsiasi punto (vale solo se nella stessa frase c'è anche una domanda). */
    private val MERC_ANYWHERE = Regex("(?:^|\\s)$MERC")

    /** Minuscolo, senza accenti né punteggiatura, spazi singoli. */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text.lowercase(Locale.ITALY), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noAccents.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Se nel testo c'è "Ehi Mercedes" restituisce quello che è stato detto dopo (anche vuoto),
     * altrimenti null. Accetta anche "Mercedes" a inizio frase, oppure in mezzo alla frase se
     * c'è anche una domanda riconosciuta (es. "allora mercedes che tempo fa").
     */
    fun afterWakeWord(text: String): String? {
        val t = normalize(text)
        val m = WAKE_WITH_HEY.find(t) ?: WAKE_AT_START.find(t)
        if (m != null) return t.substring(m.range.last + 1).trim()
        val any = MERC_ANYWHERE.find(t) ?: return null
        val rest = t.substring(any.range.last + 1).trim()
        return if (parse(rest) != Command.UNKNOWN) rest else null
    }

    fun parse(text: String): Command {
        val t = " " + normalize(text) + " "
        val asksLimit = t.contains(" limite ") || t.contains(" limiti ") ||
            t.contains(" posso andare ") || t.contains(" quanto posso ") ||
            (t.contains(" velocita ") && (t.contains(" massima ") || t.contains(" consentita ") ||
                t.contains(" prevista ") || t.contains(" permessa ")))
        if (asksLimit) return Command.SPEED_LIMIT

        val asksWeather = t.contains(" tempo fa ") || t.contains(" che tempo ") || t.contains(" meteo ") ||
            t.contains(" previsioni ") || t.contains(" previsione ") || t.contains(" piove ") ||
            t.contains(" pioggia ") || t.contains(" piovera ") || t.contains(" temperatura ") ||
            t.contains(" quanti gradi ") || t.contains(" fa freddo ") || t.contains(" fa caldo ")
        if (asksWeather) {
            return if (t.contains(" domani ")) Command.WEATHER_TOMORROW else Command.WEATHER_TODAY
        }
        return Command.UNKNOWN
    }

    /** Cosa sa l'app del limite in questo momento. */
    data class LimitState(
        val speedCheckEnabled: Boolean,
        val gpsActive: Boolean,
        val autoLimit: Boolean,
        val detectedKmh: Int,
        val fromTag: Boolean,
        val estimateNote: String? = null,
        val roadName: String?,
        val manualKmh: Int,
        val dataError: Boolean,
        val hasRoadData: Boolean
    )

    /** La risposta parlata alla domanda sul limite. */
    fun speedLimitReply(s: LimitState): String {
        if (!s.speedCheckEnabled) {
            return "L'avviso di velocità è spento, quindi non sto leggendo i limiti delle strade. Accendilo nell'app."
        }
        if (!s.gpsActive) {
            return "Non ho la posizione GPS in questo momento, quindi non so in che strada sei."
        }
        if (!s.autoLimit) {
            return "Il limite automatico è spento. Il limite che hai impostato è di ${s.manualKmh} chilometri orari."
        }
        if (s.detectedKmh > 0) {
            val where = if (s.roadName.isNullOrBlank()) "su questa strada" else "su ${s.roadName}"
            return if (s.fromTag) {
                "Il limite $where è di ${s.detectedKmh} chilometri orari."
            } else {
                if (s.estimateNote != null) {
                    "Il limite $where non è scritto nei dati: ${s.estimateNote}, quindi di solito è di " +
                        "${s.detectedKmh} chilometri orari. Controlla sempre i cartelli."
                } else {
                    "Per questa strada non c'è un limite scritto nei dati: per il tipo di strada è di " +
                        "${s.detectedKmh} chilometri orari."
                }
            }
        }
        return when {
            s.dataError && !s.hasRoadData ->
                "Non riesco a scaricare i dati della strada. Intanto uso il tuo limite di ${s.manualKmh} chilometri orari."
            !s.hasRoadData ->
                "Sto ancora cercando i dati di questa strada. Intanto uso il tuo limite di ${s.manualKmh} chilometri orari."
            else ->
                "Non conosco il limite di questa strada. Uso il tuo limite di ${s.manualKmh} chilometri orari."
        }
    }

    const val ACK = "Dimmi."
    const val NOT_UNDERSTOOD = "Non ho capito. Puoi chiedermi il limite di velocità, oppure che tempo fa oggi o domani."
}

/**
 * Decide quando l'assistente deve rispondere, leggendo la trascrizione mentre parli.
 *
 * Il motore vocale dà un testo "provvisorio" mentre parli e uno "definitivo" quando smetti.
 * In auto, con rumore e musica, la fine della frase a volte non arriva mai: per questo si
 * guarda anche il testo provvisorio e si risponde quando smette di cambiare.
 * Non usa Android: il tempo viene passato da fuori, così si prova sul computer.
 */
class WakeDetector(
    /** Il testo provvisorio deve restare uguale per questo tempo prima di rispondere. */
    private val stableMs: Long = 900L,
    /** Dopo "Ehi Mercedes" senza domanda, si aspetta così prima di dire "Dimmi". */
    private val wakeOnlyWaitMs: Long = 2_200L,
    /** Dopo "Dimmi" si aspetta la domanda per questo tempo. */
    private val awaitCommandMs: Long = 8_000L
) {
    enum class Kind { NONE, COMMAND, WAKE_ONLY }

    data class Action(val kind: Kind, val command: VoiceCommands.Command = VoiceCommands.Command.UNKNOWN) {
        val isNone: Boolean get() = kind == Kind.NONE
    }

    private val none = Action(Kind.NONE)

    private var lastPartial = ""
    private var lastChangeAt = 0L
    private var wakeSeenAt = -1L
    private var awaitingUntil = 0L

    val isAwaiting: Boolean get() = awaitingUntil > 0L

    /** Dopo ogni risposta (e quando il riconoscimento riparte) si ricomincia da zero. */
    fun reset() {
        lastPartial = ""
        lastChangeAt = 0L
        wakeSeenAt = -1L
    }

    private fun decide(text: String, now: Long, final: Boolean): Action {
        val after = VoiceCommands.afterWakeWord(text)
        if (after != null) {
            val c = VoiceCommands.parse(after)
            if (c != VoiceCommands.Command.UNKNOWN) {
                awaitingUntil = 0L
                reset()
                return Action(Kind.COMMAND, c)
            }
            if (wakeSeenAt < 0L) wakeSeenAt = now
            if (final) return wakeOnly(now)
            return none
        }
        if (awaitingUntil > 0L) {
            if (now > awaitingUntil) {
                awaitingUntil = 0L
                return none
            }
            val c = VoiceCommands.parse(text)
            if (c != VoiceCommands.Command.UNKNOWN || final) {
                awaitingUntil = 0L
                reset()
                return Action(Kind.COMMAND, c)
            }
        }
        return none
    }

    private fun wakeOnly(now: Long): Action {
        reset()
        awaitingUntil = now + awaitCommandMs
        return Action(Kind.WAKE_ONLY)
    }

    /** Testo definitivo di una frase. */
    fun onFinal(text: String, now: Long): Action {
        lastPartial = ""
        if (text.isBlank()) return tick(now)
        val a = decide(text, now, true)
        if (a.isNone) wakeSeenAt = -1L
        return a
    }

    /** Testo provvisorio (arriva più volte al secondo mentre parli). */
    fun onPartial(text: String, now: Long): Action {
        if (text != lastPartial) {
            lastPartial = text
            lastChangeAt = now
            if (wakeSeenAt < 0L && text.isNotBlank() && VoiceCommands.afterWakeWord(text) != null) wakeSeenAt = now
        }
        return tick(now)
    }

    /** Da chiamare spesso anche nel silenzio: gestisce le attese. */
    fun tick(now: Long): Action {
        if (awaitingUntil > 0L && now > awaitingUntil) awaitingUntil = 0L
        val partial = lastPartial
        if (partial.isNotBlank() && now - lastChangeAt >= stableMs) {
            val a = decide(partial, now, false)
            if (!a.isNone) return a
        }
        if (wakeSeenAt >= 0L && now - wakeSeenAt >= wakeOnlyWaitMs) {
            // ha detto "Ehi Mercedes" ma la domanda non arriva: rispondo "Dimmi"
            val after = VoiceCommands.afterWakeWord(partial)
            if (after == null || VoiceCommands.parse(after) == VoiceCommands.Command.UNKNOWN) return wakeOnly(now)
        }
        return none
    }
}
