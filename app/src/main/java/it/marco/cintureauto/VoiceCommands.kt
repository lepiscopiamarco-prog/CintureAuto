package it.marco.cintureauto

import java.text.Normalizer
import java.util.Locale

/*
 * Assistente vocale «Ehi Mercedes»: logica "pura" (senza Android), così si prova sul computer.
 * Riconosce la frase di attivazione nel testo trascritto, capisce la domanda e prepara la risposta.
 */
object VoiceCommands {

    enum class Command { SPEED_LIMIT, UNKNOWN }

    /**
     * "Ehi Mercedes" come può uscire dalla trascrizione: ehi / ei / hey / e ... mercedes / mercede /
     * mersedes. Serve la parola di saluto davanti, per non attivarsi su un semplice "Mercedes".
     */
    private val WAKE = Regex("""(?:^|\s)(?:ehi|ei|hey|hei|ey|eh|e|ai|hai|ehy)\s+mer[cs]e?d\w*""")

    /** Minuscolo, senza accenti né punteggiatura, spazi singoli. */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text.lowercase(Locale.ITALY), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noAccents.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Se nel testo c'è "Ehi Mercedes" restituisce quello che è stato detto dopo (anche vuoto),
     * altrimenti null.
     */
    fun afterWakeWord(text: String): String? {
        val t = normalize(text)
        val m = WAKE.find(t) ?: return null
        return t.substring(m.range.last + 1).trim()
    }

    fun parse(text: String): Command {
        val t = " " + normalize(text) + " "
        val asksLimit = t.contains(" limite ") || t.contains(" limiti ") ||
            t.contains(" posso andare ") ||
            (t.contains(" velocita ") && (t.contains(" massima ") || t.contains(" consentita ") ||
                t.contains(" prevista ") || t.contains(" permessa ")))
        return if (asksLimit) Command.SPEED_LIMIT else Command.UNKNOWN
    }

    /** Cosa sa l'app del limite in questo momento. */
    data class LimitState(
        val speedCheckEnabled: Boolean,
        val gpsActive: Boolean,
        val autoLimit: Boolean,
        val detectedKmh: Int,
        val fromTag: Boolean,
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
                "Per questa strada non c'è un limite scritto nei dati: per il tipo di strada è di " +
                    "${s.detectedKmh} chilometri orari."
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
    const val NOT_UNDERSTOOD = "Non ho capito. Puoi chiedermi: qual è il limite di velocità?"
}
