package it.marco.cintureauto

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.TimeZone

/*
 * Intelligenza artificiale per «Ehi Mercedes»: domande a risposta libera.
 *
 * Usa Groq (https://groq.com), che offre un piano gratuito con chiave personale e un'interfaccia
 * compatibile con quella di OpenAI. Nessuna libreria in più: solo HttpURLConnection, come il resto
 * dell'app. Logica "pura" (senza Android): si prova sul computer con un finto server.
 *
 * I modelli di Groq cambiano spesso (alcuni vengono spenti con pochi mesi di preavviso), quindi
 * l'app non dipende da un nome fisso: parte dall'ultimo modello che ha funzionato e, se non c'è
 * più o è al limite, chiede a Groq l'elenco di quelli attivi e prova i migliori in ordine.
 */

class AiMessage(val role: String, val content: String)

/** Esito di una richiesta. [text] è presente solo se è andata bene. */
class AiResult(
    val text: String?,
    val model: String?,
    val problem: AiProblem?,
    val detail: String = "",
    /** Il modello si è fermato per lunghezza: il testo può finire a metà frase. */
    val truncated: Boolean = false
) {
    val ok: Boolean get() = text != null
}

class AiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
    /** Tempo massimo complessivo, tra tutti i tentativi con modelli diversi. */
    private val totalBudgetMs: Long = 35_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        const val USER_AGENT = "MyMercedes/1.5 (app personale Android)"

        /** Dal migliore al meno buono per una risposta vocale (veloce, buon italiano). Solo una preferenza: la scelta vera dipende da cosa è attivo. */
        val PREFERRED = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "qwen/qwen3.8-27b"
        )

        /** Modelli che non servono a rispondere a voce (parlato→testo, voce, controllo contenuti, ricerca composta…). */
        private val NOT_FOR_CHAT = listOf(
            "whisper", "tts", "orpheus", "playai", "guard", "safeguard", "embed",
            "moderation", "vision", "speech", "transcri", "compound"
        )

        private const val MAX_ATTEMPTS = 5
        private const val MAX_OTHER_MODELS = 3

        /** Spazio per la risposta; i modelli "che ragionano" ne consumano una parte prima di rispondere. */
        private const val MAX_OUTPUT_TOKENS = 900
        private const val TEMPERATURE = 0.6
        private const val MAX_BODY_CHARS = 262_144

        /** I modelli che ragionano vanno tenuti al minimo, altrimenti la risposta arriva dopo troppo tempo. */
        fun reasoningEffort(model: String): String? = when {
            model.startsWith("openai/gpt-oss") -> "low"
            model.startsWith("qwen/qwen3") -> "none"
            else -> null
        }

        fun isChatModel(id: String): Boolean {
            val s = id.lowercase(Locale.ROOT)
            return s.isNotBlank() && NOT_FOR_CHAT.none { s.contains(it) }
        }

        /** Mette in ordine i modelli attivi: prima quelli noti, nell'ordine di preferenza, poi qualche modello nuovo. */
        fun rank(activeIds: List<String>): List<String> {
            val usable = activeIds.filter { isChatModel(it) }
            val known = PREFERRED.filter { it in usable }
            val others = usable.filter { it !in PREFERRED }.sorted().take(MAX_OTHER_MODELS)
            return known + others
        }

        /** Il messaggio di errore di Groq dice che il problema è il modello (spento, inesistente, da autorizzare)? */
        fun looksLikeModelError(body: String): Boolean {
            val s = body.lowercase(Locale.ROOT)
            if (!s.contains("model")) return false
            return listOf(
                "not found", "not_found", "does not exist", "decommission", "deprecat",
                "no longer", "not supported", "unknown model", "invalid model", "terms_required", "terms acceptance"
            ).any { s.contains(it) }
        }

        /** Legge la risposta di una chat completion. Null se non ha la forma attesa. */
        fun parseChat(text: String): AiResult? {
            try {
                val root = JSONObject(text)
                val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
                val message = choice.optJSONObject("message") ?: return null
                // Attenzione: su Android optString di un null JSON restituisce la parola "null"
                val content = if (message.isNull("content")) "" else message.optString("content", "")
                val finish = if (choice.isNull("finish_reason")) "" else choice.optString("finish_reason", "")
                val model = if (root.isNull("model")) null else root.optString("model", "").ifEmpty { null }
                val clean = AiText.stripThinking(content).trim()
                if (clean.isEmpty()) return AiResult(null, model, AiProblem.EMPTY_ANSWER, "finish=$finish")
                return AiResult(clean, model, null, truncated = finish == "length")
            } catch (e: Exception) {
                return null
            }
        }
    }

    private class HttpOut(val code: Int, val text: String)

    /** Una risposta HTTP e se è un "400" generico (parametro non accettato), da ritentare in forma semplice. */
    private class Attempt(val result: AiResult, val badRequest: Boolean = false)

    // -------------------------------------------------------------------------------------------
    // Richiesta con scelta e ripiego del modello
    // -------------------------------------------------------------------------------------------

    /**
     * Fa la domanda. [rememberedModel] è l'ultimo modello che ha funzionato (vuoto la prima volta).
     * Bloccante (rete): da un thread in background. Non lancia eccezioni.
     */
    fun ask(apiKey: String, rememberedModel: String, messages: List<AiMessage>): AiResult {
        if (apiKey.isBlank()) return AiResult(null, null, AiProblem.NO_KEY)
        try {
            val deadline = clock() + totalBudgetMs
            val queue = ArrayDeque<String>()
            if (rememberedModel.isNotBlank()) queue.add(rememberedModel) else queue.addAll(PREFERRED.take(2))
            val tried = HashSet<String>()
            var discovered = false
            var last = AiResult(null, null, AiProblem.SERVER)
            var attempts = 0

            while (attempts < MAX_ATTEMPTS && clock() < deadline) {
                if (queue.isEmpty()) {
                    if (discovered) break
                    discovered = true
                    listModels(apiKey)?.let { ids -> rank(ids).filter { it !in tried }.forEach { queue.add(it) } }
                    if (queue.isEmpty()) break
                }
                val model = queue.removeFirst()
                if (!tried.add(model)) continue
                attempts += 1

                val remaining = (deadline - clock()).coerceAtLeast(3_000L)
                val r = callModel(apiKey, model, messages, minOf(readTimeoutMs.toLong(), remaining).toInt())
                if (r.ok) return r
                last = r
                when (r.problem) {
                    // inutile provare altri modelli: il problema non dipende dal modello
                    AiProblem.BAD_KEY, AiProblem.NO_NETWORK, AiProblem.TIMEOUT -> return r
                    AiProblem.NO_MODEL -> if (!discovered) {
                        // il modello non esiste più: si riparte dall'elenco di quelli attivi
                        discovered = true
                        val ids = listModels(apiKey)
                        if (ids != null) {
                            queue.clear()
                            rank(ids).filter { it !in tried }.forEach { queue.add(it) }
                        }
                    }
                    else -> {}
                }
            }
            return last
        } catch (e: Exception) {
            return AiResult(null, null, AiProblem.SERVER, e.javaClass.simpleName)
        }
    }

    /** Un tentativo con un modello; se Groq rifiuta un parametro lo ripete una volta in forma semplice. */
    private fun callModel(apiKey: String, model: String, messages: List<AiMessage>, timeoutMs: Int): AiResult {
        val first = chat(apiKey, model, messages, timeoutMs, compat = false)
        if (!first.badRequest) return first.result
        val second = chat(apiKey, model, messages, timeoutMs, compat = true)
        return second.result
    }

    private fun chat(apiKey: String, model: String, messages: List<AiMessage>, timeoutMs: Int, compat: Boolean): Attempt {
        val out: HttpOut
        try {
            out = http("/chat/completions", apiKey, chatBody(model, messages, compat), timeoutMs)
        } catch (e: SocketTimeoutException) {
            return Attempt(AiResult(null, model, AiProblem.TIMEOUT))
        } catch (e: IOException) {
            val msg = e.message ?: ""
            // alcune versioni di Android/Java lanciano un errore quando il 401 non ha l'intestazione standard
            if (msg.contains("authentication", ignoreCase = true) || msg.contains("401")) {
                return Attempt(AiResult(null, model, AiProblem.BAD_KEY, msg.take(80)))
            }
            return Attempt(AiResult(null, model, AiProblem.NO_NETWORK, e.javaClass.simpleName))
        }

        val code = out.code
        if (code in 200..299) {
            val parsed = parseChat(out.text)
                ?: return Attempt(AiResult(null, model, AiProblem.SERVER, "risposta non leggibile"))
            return Attempt(AiResult(parsed.text, parsed.model ?: model, parsed.problem, parsed.detail, parsed.truncated))
        }
        val snippet = "HTTP $code " + errorMessage(out.text)
        return when {
            code == 401 || code == 403 -> Attempt(AiResult(null, model, AiProblem.BAD_KEY, snippet))
            code == 404 -> Attempt(AiResult(null, model, AiProblem.NO_MODEL, snippet))
            code == 429 || code == 413 -> Attempt(AiResult(null, model, AiProblem.RATE_LIMIT, snippet))
            code == 400 && looksLikeModelError(out.text) -> Attempt(AiResult(null, model, AiProblem.NO_MODEL, snippet))
            code == 400 && !compat -> Attempt(AiResult(null, model, AiProblem.SERVER, snippet), badRequest = true)
            else -> Attempt(AiResult(null, model, AiProblem.SERVER, snippet))
        }
    }

    private fun chatBody(model: String, messages: List<AiMessage>, compat: Boolean): String {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            arr.put(o)
        }
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", arr)
        body.put("temperature", TEMPERATURE)
        body.put("stream", false)
        if (compat) {
            body.put("max_tokens", MAX_OUTPUT_TOKENS)
        } else {
            body.put("max_completion_tokens", MAX_OUTPUT_TOKENS)
            reasoningEffort(model)?.let { body.put("reasoning_effort", it) }
        }
        return body.toString()
    }

    /** Il testo dell'errore scritto da Groq ({"error":{"message":"…"}}), accorciato. */
    private fun errorMessage(body: String): String {
        return try {
            val e = JSONObject(body).optJSONObject("error")
            (e?.optString("message", "") ?: "").replace(Regex("\\s+"), " ").take(120)
        } catch (ex: Exception) {
            body.replace(Regex("\\s+"), " ").take(80)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Elenco dei modelli attivi
    // -------------------------------------------------------------------------------------------

    /** Gli identificativi dei modelli attivi su Groq, o null se non si riesce a leggerli. */
    fun listModels(apiKey: String): List<String>? {
        return try {
            val out = http("/models", apiKey, null, 10_000)
            if (out.code !in 200..299) return null
            val data = JSONObject(out.text).optJSONArray("data") ?: return null
            val ids = ArrayList<String>()
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                if (m.has("active") && !m.optBoolean("active", true)) continue
                val id = m.optString("id", "")
                if (id.isNotEmpty()) ids.add(id)
            }
            ids
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------------------------

    /** GET (body nullo) o POST JSON. Lancia IOException se la rete non risponde. */
    private fun http(path: String, apiKey: String, body: String?, timeoutMs: Int): HttpOut {
        var c: HttpURLConnection? = null
        try {
            val conn = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = if (body == null) "GET" else "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpOut(code, text.take(MAX_BODY_CHARS))
        } finally {
            c?.disconnect()
        }
    }
}

/**
 * Le ultime domande e risposte, per i seguiti («e quello del 2010?»). Solo in memoria: spariscono
 * dopo qualche minuto, quando l'auto si spegne o l'app si chiude.
 */
class AiConversation(
    private val maxTurns: Int = 4,
    private val ttlMs: Long = 5 * 60_000L
) {
    private class Turn(val question: String, val answer: String, val atMs: Long)

    private val turns = ArrayList<Turn>()

    @Synchronized
    fun messages(system: String, question: String, nowMs: Long): List<AiMessage> {
        dropOld(nowMs)
        val out = ArrayList<AiMessage>()
        out.add(AiMessage("system", system))
        for (t in turns) {
            out.add(AiMessage("user", t.question))
            out.add(AiMessage("assistant", t.answer))
        }
        out.add(AiMessage("user", question))
        return out
    }

    @Synchronized
    fun remember(question: String, answer: String, nowMs: Long) {
        dropOld(nowMs)
        turns.add(Turn(question.take(400), answer.take(AiText.MAX_SPEECH_CHARS), nowMs))
        while (turns.size > maxTurns) turns.removeAt(0)
    }

    @Synchronized
    fun clear() {
        turns.clear()
    }

    private fun dropOld(nowMs: Long) {
        turns.removeAll { nowMs - it.atMs > ttlMs }
    }
}

/** Una domanda dall'inizio alla fine: istruzioni, cronologia, richiesta, pulizia della risposta. */
class AiSession(
    private val client: AiClient = AiClient(),
    private val chat: AiConversation = AiConversation()
) {

    /** Quanto dire a voce ([text], sempre presente) e com'è andata. */
    class Spoken(
        val text: String,
        val ok: Boolean,
        val model: String?,
        val problem: AiProblem?,
        val detail: String,
        val elapsedMs: Long
    )

    companion object {
        const val MAX_QUESTION_CHARS = 400
    }

    /**
     * Risponde a [question]. Bloccante (rete): da un thread in background. Non lancia eccezioni.
     * [remember] = false per le prove, che non devono entrare nella cronologia.
     */
    fun answer(
        apiKey: String,
        rememberedModel: String,
        question: String,
        nowMs: Long,
        city: String?,
        tz: TimeZone = TimeZone.getDefault(),
        remember: Boolean = true
    ): Spoken {
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started
        try {
            val q = question.trim().take(MAX_QUESTION_CHARS)
            val messages = chat.messages(AiText.systemPrompt(nowMs, city, tz), q, nowMs)
            val r = client.ask(apiKey, rememberedModel, messages)
            if (!r.ok) {
                val p = r.problem ?: AiProblem.SERVER
                return Spoken(AiText.problemSpeech(p), false, r.model, p, r.detail, elapsed())
            }
            val speech = AiText.forSpeech(r.text ?: "", r.truncated)
            if (speech.isBlank()) {
                return Spoken(AiText.problemSpeech(AiProblem.EMPTY_ANSWER), false, r.model, AiProblem.EMPTY_ANSWER, "", elapsed())
            }
            if (remember) chat.remember(q, speech, nowMs)
            return Spoken(speech, true, r.model, null, "", elapsed())
        } catch (e: Exception) {
            return Spoken(AiText.problemSpeech(AiProblem.SERVER), false, null, AiProblem.SERVER, e.javaClass.simpleName, elapsed())
        }
    }

    fun clearHistory() {
        chat.clear()
    }
}
