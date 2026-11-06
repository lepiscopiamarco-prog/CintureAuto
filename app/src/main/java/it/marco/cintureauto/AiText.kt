package it.marco.cintureauto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Testi per l'intelligenza artificiale di «Ehi Mercedes»: le istruzioni da dare al modello e la
 * pulizia della risposta, perché la sintesi vocale la legga bene. Logica "pura" (senza Android),
 * così si prova sul computer.
 */

/** Che cosa è andato storto nella richiesta all'intelligenza artificiale. */
enum class AiProblem { NO_KEY, BAD_KEY, RATE_LIMIT, NO_NETWORK, TIMEOUT, SERVER, NO_MODEL, EMPTY_ANSWER }

object AiText {

    /** Una risposta detta a voce non deve superare questa lunghezza (circa 35 secondi di voce). */
    const val MAX_SPEECH_CHARS = 520

    private const val PROMPT_BASE =
        "Sei «Mercedes», l'assistente vocale di un'auto. Chi ti parla sta guidando e ti ascolta dagli altoparlanti.\n" +
            "Regole per rispondere:\n" +
            "- Rispondi sempre in italiano, a voce, in modo naturale e cordiale.\n" +
            "- Sii breve: da una a tre frasi, al massimo circa 60 parole. Vai subito alla risposta, " +
            "senza preamboli come «certo» o «ottima domanda».\n" +
            "- Niente elenchi, titoli, tabelle, markdown, emoji, link o simboli: il testo viene letto da una sintesi vocale. " +
            "Scrivi per esteso ciò che va letto, per esempio «20 gradi», «50 chilometri orari», «3 euro».\n" +
            "- Se servirebbe spiegare di più, dai l'essenziale e chiedi se vuole che continui.\n" +
            "- Non hai accesso a internet né a dati in tempo reale (notizie di oggi, traffico, risultati sportivi, prezzi, orari): " +
            "se la domanda li richiede dillo in una frase e non inventare. Se non sai una cosa, ammettilo.\n" +
            "- Non puoi controllare l'auto né il telefono.\n" +
            "- La domanda arriva da un riconoscimento vocale: è senza punteggiatura né accenti e può contenere errori di trascrizione. " +
            "Interpreta l'intenzione più probabile; se è davvero incomprensibile, chiedi di ripeterla con una frase breve.\n" +
            "- Chi guida non deve guardare lo schermo: non chiedergli di leggere, toccare o aprire nulla."

    /** Le istruzioni da dare al modello, con data, ora e (se concesso) la città in cui ci si trova. */
    fun systemPrompt(nowMs: Long, city: String?, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("EEEE d MMMM yyyy 'alle' HH:mm", Locale.ITALY)
        f.timeZone = tz
        val sb = StringBuilder(PROMPT_BASE)
        sb.append("\nOggi è ").append(f.format(Date(nowMs))).append('.')
        val place = city?.replace(Regex("\\s+"), " ")?.trim()?.take(60)
        if (!place.isNullOrEmpty()) sb.append(" L'utente si trova a ").append(place).append('.')
        return sb.toString()
    }

    // -------------------------------------------------------------------------------------------
    // Chiave
    // -------------------------------------------------------------------------------------------

    /**
     * Ripulisce una chiave incollata: toglie spazi, a capo, virgolette e un eventuale «Bearer» davanti.
     * Le chiavi sono fatte solo di caratteri ASCII visibili: ogni altro carattere (anche invisibile,
     * come quelli che a volte arrivano con il copia-incolla) viene scartato.
     */
    fun cleanKey(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("Bearer ", ignoreCase = true)) s = s.substring(7)
        s = s.trim { it in "\"'`“”‘’«»" || it.isWhitespace() }
        return s.filter { it.code in 33..126 }
    }

    /** gsk_…a1b2: abbastanza per riconoscerla, senza mostrarla tutta. */
    fun maskKey(key: String): String =
        if (key.length <= 10) "••••" else key.take(4) + "…" + key.takeLast(4)

    // -------------------------------------------------------------------------------------------
    // Risposta → testo adatto alla voce
    // -------------------------------------------------------------------------------------------

    private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val THINK_UNCLOSED = Regex("<think>.*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CODE_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val LIST_MARKER = Regex("^(?:[-*•·▪‣–—]|\\d{1,2}[.)])\\s+")
    private val EURO_BEFORE = Regex("€\\s*(\\d[\\d.,]*)")
    private val DOLLAR_BEFORE = Regex("\\$\\s*(\\d[\\d.,]*)")
    private val SPACES = Regex("[ \\t]+")

    /** Toglie il "ragionamento" che alcuni modelli scrivono tra <think> e </think>. */
    fun stripThinking(text: String): String =
        text.replace(THINK_BLOCK, " ").replace(THINK_UNCLOSED, " ")

    /**
     * Prepara la risposta del modello per la sintesi vocale: niente markdown, link, emoji o elenchi,
     * simboli scritti per esteso, lunghezza limitata. [truncated] = il modello si è fermato per
     * lunghezza: si taglia all'ultima frase completa.
     */
    fun forSpeech(raw: String, truncated: Boolean = false, maxChars: Int = MAX_SPEECH_CHARS): String {
        var s = stripThinking(raw)
        s = s.replace(CODE_BLOCK, " ")
        s = s.replace(MD_LINK, "$1")
        s = s.replace(URL, " ")
        s = removePictographs(s)

        // Una riga per volta: via i punti elenco, e ogni riga chiude con la punteggiatura (pausa nella voce)
        val lines = s.replace("\r", "\n").split('\n').map { line ->
            var l = line.trim()
            l = l.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^>+\\s*"), "")
            l = l.replace(LIST_MARKER, "")
            l.trim()
        }.filter { it.isNotEmpty() }
        // (se la risposta è stata interrotta, l'ultima riga è a metà: non va chiusa con un punto finto)
        s = lines.withIndex().joinToString(" ") { (i, line) ->
            if (line.last() in ".!?:;,…" || (truncated && i == lines.lastIndex)) line else "$line."
        }

        s = spellOutSymbols(s)
        s = s.replace(Regex("[*_~`^|\\[\\]{}<>\"“”«»]"), " ")
        s = s.replace('’', '\'').replace('‘', '\'')
        s = s.replace(SPACES, " ")
        s = s.replace(Regex(" +([.,;:!?])"), "$1")
        s = s.trim()

        if (truncated) s = cutAtSentence(s, s.length, requireTerminator = true)
        if (s.length > maxChars) s = cutAtSentence(s, maxChars, requireTerminator = false)
        return s.trim()
    }

    /** Scrive per esteso i simboli che la voce leggerebbe male o salterebbe. */
    private fun spellOutSymbols(text: String): String {
        var s = text
        s = s.replace(EURO_BEFORE, "$1 euro").replace(DOLLAR_BEFORE, "$1 dollari")
        s = s.replace("€", " euro ").replace("$", " dollari ")
        s = s.replace(Regex("km\\s*/\\s*h", RegexOption.IGNORE_CASE), " chilometri orari ")
        s = s.replace(Regex("\\bm\\s*/\\s*s\\b"), " metri al secondo ")
        // «20°C» e «20 °C» → «20 gradi»; «20° Celsius» resta «20 gradi Celsius» (la C non va mangiata)
        s = s.replace(Regex("°\\s*[CcFf]?(?!\\p{L})"), " gradi ")
        s = s.replace("%", " percento ")
        s = s.replace("&", " e ")
        s = s.replace(Regex("\\s\\+\\s"), " più ")
        return s
    }

    /** Emoji, pittogrammi, frecce e simboli decorativi: la voce li salterebbe o li leggerebbe a sproposito. */
    private fun removePictographs(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val drop = cp in 0x1F000..0x1FFFF || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF ||
                cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0xFE00..0xFE0F ||
                cp == 0x200D || cp == 0x20E3 || cp == 0x200B || cp == 0xFEFF
            if (drop) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /**
     * Taglia [text] a non più di [limit] caratteri, preferibilmente alla fine di una frase.
     * Con [requireTerminator] il testo deve finire con un punto: se l'ultima frase è a metà la scarta.
     */
    private fun cutAtSentence(text: String, limit: Int, requireTerminator: Boolean): String {
        if (text.isEmpty()) return text
        if (!requireTerminator && text.length <= limit) return text
        val upto = minOf(text.length, limit)
        var end = -1
        for (i in upto - 1 downTo 0) {
            if (isSentenceEnd(text, i)) {
                end = i
                break
            }
        }
        // Con la risposta interrotta qualsiasi frase completa è meglio di un pezzo a metà; per il solo taglio
        // di lunghezza si evita di ridurre un testo lungo a una frase minuscola.
        val minEnd = if (requireTerminator) 0 else upto / 3
        if (end >= minEnd) return text.substring(0, end + 1)
        // nessuna fine di frase utile: si taglia all'ultimo spazio
        val head = text.substring(0, upto)
        val space = head.lastIndexOf(' ')
        val cut = if (space > 0) head.substring(0, space) else head
        return cut.trimEnd(',', ';', ':', ' ') + "."
    }

    /** Un punto è una fine di frase solo se segue uno spazio (o la fine del testo): «3.5» e «S.p.A.» non lo sono. */
    private fun isSentenceEnd(text: String, i: Int): Boolean {
        val c = text[i]
        if (c != '.' && c != '!' && c != '?') return false
        if (i + 1 >= text.length) return true
        val n = text[i + 1]
        return n.isWhitespace() || n in "\"')»”"
    }

    // -------------------------------------------------------------------------------------------
    // Messaggi parlati e testo di stato
    // -------------------------------------------------------------------------------------------

    /** Cosa dice l'assistente a voce quando l'IA non può rispondere. */
    fun problemSpeech(problem: AiProblem): String = when (problem) {
        AiProblem.NO_KEY ->
            "Per rispondere a qualsiasi domanda mi serve la chiave gratuita dell'intelligenza artificiale. " +
                "Inseriscila nell'app, nella scheda Ehi Mercedes."
        AiProblem.BAD_KEY ->
            "La chiave dell'intelligenza artificiale non è valida. Controllala nell'app, nella scheda Ehi Mercedes."
        AiProblem.RATE_LIMIT ->
            "Ho raggiunto il limite gratuito di domande per adesso. Riprova tra qualche minuto."
        AiProblem.NO_NETWORK ->
            "Non riesco a collegarmi a internet, quindi non posso rispondere a questa domanda."
        AiProblem.TIMEOUT ->
            "L'intelligenza artificiale non ha risposto in tempo. Riprova."
        AiProblem.SERVER ->
            "Il servizio di intelligenza artificiale non funziona in questo momento. Riprova più tardi."
        AiProblem.NO_MODEL ->
            "Non trovo un modello di intelligenza artificiale disponibile. Riprova più tardi."
        AiProblem.EMPTY_ANSWER ->
            "Non sono riuscito a rispondere a questa domanda. Prova a ripeterla in un altro modo."
    }

    /** Descrizione breve del problema, per la schermata. */
    fun problemLabel(problem: AiProblem): String = when (problem) {
        AiProblem.NO_KEY -> "manca la chiave"
        AiProblem.BAD_KEY -> "chiave non valida"
        AiProblem.RATE_LIMIT -> "limite gratuito raggiunto"
        AiProblem.NO_NETWORK -> "nessuna connessione"
        AiProblem.TIMEOUT -> "nessuna risposta in tempo"
        AiProblem.SERVER -> "servizio non disponibile"
        AiProblem.NO_MODEL -> "nessun modello disponibile"
        AiProblem.EMPTY_ANSWER -> "risposta vuota"
    }

    /** La riga «Ultima richiesta» mostrata nella schermata. */
    fun statusLine(nowMs: Long, problem: AiProblem?, model: String?, elapsedMs: Long, detail: String = ""): String {
        val time = TextFormat.shortDateTime(nowMs)
        if (problem == null) {
            val secs = String.format(Locale.ITALY, "%.1f", elapsedMs / 1000.0)
            return "$time · ok" + (if (model.isNullOrBlank()) "" else " · $model") + " · $secs s"
        }
        val extra = detail.trim().take(80)
        return "$time · ${problemLabel(problem)}" + (if (extra.isEmpty()) "" else " ($extra)")
    }

    /**
     * Quanto tempo concedere alla voce per leggere [text]: serve a non riaccendere il microfono
     * mentre l'app sta ancora parlando (si calcola dalla lunghezza e dalla velocità della voce).
     */
    fun speechBudgetMs(text: String, ratePercent: Int): Long {
        val rate = ratePercent.coerceIn(30, 200)
        val ms = text.length * 75L * 100L / rate + 5_000L
        return ms.coerceIn(15_000L, 90_000L)
    }
}
