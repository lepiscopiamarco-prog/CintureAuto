package it.marco.cintureauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Schermata delle impostazioni. Costruita da codice (nessun file di layout) per tenere
 * il progetto il più semplice e robusto possibile.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_BASIC = 1
        private const val REQ_BG = 2
        private const val REQ_EXPORT = 3
        private const val REQ_MIC = 4

        private const val HOME = "home"
        private val COLOR_HEADER = Color.parseColor("#1B1F26")
        private val COLOR_HEADER_TEXT = Color.parseColor("#D9DEE5")

        private val COLOR_BG = Color.parseColor("#F2F4F8")
        private val COLOR_TEXT = Color.parseColor("#1B2430")
        private val COLOR_MUTED = Color.parseColor("#5F6B7A")
        private val COLOR_ACCENT = Color.parseColor("#1E5AA8")
        private val COLOR_OK = Color.parseColor("#1B8A3A")
        private val COLOR_BAD = Color.parseColor("#C62828")
        private val COLOR_WARN = Color.parseColor("#B26A00")
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val testPlayer by lazy { SoundPlayer(this) }

    private lateinit var root: LinearLayout
    private lateinit var scrollView: ScrollView

    // Sezioni: la schermata principale con le icone e una pagina per ogni funzione
    private val sections = LinkedHashMap<String, LinearLayout>()
    private var target: LinearLayout? = null
    private var currentSection = HOME
    private lateinit var statusVoice: TextView
    private lateinit var statusService: TextView
    private lateinit var statusCar: TextView
    private lateinit var statusSpeed: TextView
    private lateinit var statusLimit: TextView
    private lateinit var statusPerms: TextView
    private lateinit var statusTrip: TextView
    private lateinit var carNameInput: EditText

    // parcheggio e registro viaggi
    private lateinit var parkingInfo: TextView
    private lateinit var tripsSummary: TextView
    private lateinit var tripsRecent: TextView
    private lateinit var weatherInfo: TextView
    private lateinit var assistantInfo: TextView
    private lateinit var aiInfo: TextView
    private lateinit var aiKeyInput: EditText

    @Volatile
    private var aiTesting = false
    private val tripStore by lazy { TripStore(this) }
    private var tripsCache: List<Trip> = emptyList()
    private var tripsCacheVersion = -1

    /** Voce usata per le prove dalla schermata (il servizio ne ha una sua). */
    private var announcerInstance: VoiceAnnouncer? = null

    private var askedBackground = false
    private var askedBattery = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Ciclo di vita
    // -------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        announcerInstance = VoiceAnnouncer(this, prefs)

        val firstRun = !prefs.firstRunDone
        if (firstRun) {
            prefs.firstRunDone = true
            prefs.serviceEnabled = true
        }

        buildUi()

        if (firstRun) startSetup()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.serviceEnabled && hasBasicPermissions()) {
            val svc = CarAlertService.instance
            // Riavvia il servizio se non c'è, oppure se era partito in modalità ridotta (senza GPS).
            // (o senza microfono, se l'assistente vocale è acceso: Android lo concede solo ad app aperta).
            val needsMic = prefs.assistantEnabled && hasRecordAudio() && svc != null && !svc.fgsHasMic
            if (svc == null || !svc.fgsHasLocation || needsMic) CarAlertService.start(this)
        }
        reloadTrips()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        testPlayer.stop()
        announcerInstance?.shutdown()
        announcerInstance = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------------
    // Costruzione della schermata
    // -------------------------------------------------------------------------------------------

    private fun buildUi() {
        val scroll = ScrollView(this)
        scrollView = scroll
        scroll.setBackgroundColor(COLOR_BG)

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(20), dp(16), dp(32))
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)

        // ---- Schermata principale ----
        section(HOME, null)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(18), dp(18), dp(18), dp(18))
        header.background = roundedBg(COLOR_HEADER)
        header.addView(label("🚗  My Mercedes", 26f, Typeface.BOLD, Color.WHITE))
        header.addView(
            label(
                "Cinture, limiti di velocità, parcheggio, viaggi, meteo e «Ehi Mercedes».",
                13f, Typeface.NORMAL, COLOR_HEADER_TEXT
            )
        )
        (target ?: root).addView(header)

        val statusCard = card("Stato")
        statusService = addLine(statusCard)
        statusCar = addLine(statusCard)
        statusSpeed = addLine(statusCard)
        statusLimit = addLine(statusCard)
        statusTrip = addLine(statusCard)
        statusVoice = addLine(statusCard)
        statusPerms = addLine(statusCard)
        switchRow(statusCard, "Servizio sempre attivo", prefs.serviceEnabled) { checked ->
            onServiceSwitch(checked)
        }
        note(
            statusCard,
            "Tienilo acceso: l'app resta attiva in background (vedrai una notifica fissa) e riparte da sola quando accendi il telefono."
        )

        val tiles = card("Funzioni")
        tileGrid(
            tiles,
            listOf(
                Triple("parcheggio", "📍", "Parcheggio"),
                Triple("velocita", "🚦", "Limite di velocità"),
                Triple("assistente", "🎙️", "Ehi Mercedes"),
                Triple("meteo", "⛅", "Meteo"),
                Triple("viaggi", "📒", "Viaggi"),
                Triple("suono", "🔔", "Suono e voce"),
                Triple("auto", "🚘", "Auto"),
                Triple("permessi", "🔐", "Permessi")
            )
        )

        // ---- Auto ----
        section("auto", "🚘  Auto")
        val carCard = card("Auto")
        note(carCard, "Nome Bluetooth dell'auto, come appare nell'elenco dei dispositivi del telefono:")
        carNameInput = EditText(this)
        carNameInput.setText(prefs.carName)
        carNameInput.setSingleLine(true)
        carNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.carName = s?.toString() ?: ""
                prefs.carAddress = ""
                CarAlertService.instance?.refresh()
            }
        })
        carCard.addView(carNameInput)
        actionButton(carCard, "Scegli tra i dispositivi già associati") { pickPairedDevice() }

        // ---- Suono cintura ----
        section("suono", "🔔  Suono e voce")
        val chimeCard = card("Suono cintura")
        switchRow(chimeCard, "Suona quando mi collego all'auto", prefs.chimeEnabled) { checked ->
            prefs.chimeEnabled = checked
        }
        note(chimeCard, "Tipo di suono:")
        val styles = arrayOf("Ding singolo (classico da aereo)", "Ding-dong (due note)", "Doppio ding")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, styles)
        spinner.setSelection(prefs.chimeStyle.coerceIn(0, styles.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.chimeStyle = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        chimeCard.addView(spinner)
        seekRow(chimeCard, { "Attesa dopo la connessione: $it s" }, 0, 15, 1, prefs.chimeDelaySec) {
            prefs.chimeDelaySec = it
        }
        note(
            chimeCard,
            "L'app aspetta che l'audio Bluetooth dell'auto sia pronto. Se il suono esce ancora dal telefono, oppure parte tagliato, aumenta l'attesa."
        )
        actionButton(chimeCard, "▶  Prova il suono") {
            testPlayer.play(SoundSynth.chime(prefs.chimeStyle), prefs.volumePercent)
        }

        // ---- Voce ----
        val voiceCard = card("Voce")
        switchRow(voiceCard, "Dì la frase dopo il suono", prefs.voiceEnabled) { checked ->
            prefs.voiceEnabled = checked
        }
        note(voiceCard, "Frase da pronunciare:")
        val phraseInput = EditText(this)
        phraseInput.setText(prefs.voicePhrase)
        phraseInput.setSingleLine(true)
        phraseInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.voicePhrase = s?.toString() ?: ""
            }
        })
        voiceCard.addView(phraseInput)
        actionButton(voiceCard, "Scegli la voce") { pickVoice() }
        seekRow(voiceCard, { "Velocità della voce: $it%" }, 50, 150, 5, prefs.voiceRatePercent) {
            prefs.voiceRatePercent = it
        }
        seekRow(voiceCard, { "Tono della voce: $it%" }, 50, 150, 5, prefs.voicePitchPercent) {
            prefs.voicePitchPercent = it
        }
        seekRow(voiceCard, { "Volume della voce: $it%" }, 20, 100, 5, prefs.voiceVolumePercent) {
            prefs.voiceVolumePercent = it
        }
        note(
            voiceCard,
            "Le voci disponibili dipendono dal telefono (di solito quelle di Google): in «Scegli la voce» toccane una per ascoltarla. Per un tono più dolce prova una velocità tra 85% e 95%."
        )
        actionButton(voiceCard, "▶  Prova suono + voce") { testChimeAndVoice() }
        actionButton(voiceCard, "Impostazioni di sintesi vocale del telefono") { openTtsSettings() }

        // ---- Avviso velocità ----
        section("velocita", "🚦  Limite di velocità")
        val speedCard = card("Avviso di velocità")
        switchRow(speedCard, "Avvisami se supero il limite", prefs.speedEnabled) { checked ->
            prefs.speedEnabled = checked
            CarAlertService.instance?.refresh()
        }
        switchRow(speedCard, "Limite automatico secondo la strada", prefs.autoLimit) { checked ->
            prefs.autoLimit = checked
            CarAlertService.instance?.refresh()
        }
        note(
            speedCard,
            "Il telefono riconosce la strada in cui ti trovi e ne usa il limite (dati OpenStreetMap). Serve la connessione dati e viene inviata solo la posizione approssimata (circa 100 m). Dove il limite non è noto usa quello manuale qui sotto."
        )
        seekRow(speedCard, { "Tolleranza: +$it km/h" }, 0, 20, 1, prefs.toleranceKmh) {
            prefs.toleranceKmh = it
            CarAlertService.instance?.refresh()
        }
        note(speedCard, "L'avviso suona quando superi il limite più la tolleranza: evita falsi allarmi per i piccoli scarti del GPS.")
        seekRow(
            speedCard,
            { "Limite manuale (dove la strada non è nota): $it km/h" },
            30, 250, 5, prefs.speedLimitKmh
        ) {
            prefs.speedLimitKmh = it
            CarAlertService.instance?.refresh()
        }
        seekRow(
            speedCard,
            { if (it == 0) "Ripeti l'avviso: una sola volta" else "Ripeti l'avviso ogni $it s" },
            0, 120, 5, prefs.repeatSec
        ) {
            prefs.repeatSec = it
        }
        switchRow(
            speedCard,
            "GPS attivo solo quando l'auto è connessa",
            prefs.gpsOnlyWithCar
        ) { checked ->
            prefs.gpsOnlyWithCar = checked
            CarAlertService.instance?.refresh()
        }
        note(
            speedCard,
            "Consigliato: risparmia batteria. Se lo spegni il GPS resta sempre acceso e l'avviso può suonare anche fuori dall'auto, dalle casse del telefono."
        )
        actionButton(speedCard, "▶  Prova l'avviso di velocità") {
            testPlayer.play(SoundSynth.alarm(), prefs.volumePercent)
        }
        note(speedCard, "Dati delle strade: © OpenStreetMap contributors.")

        // ---- Parcheggio ----
        section("parcheggio", "📍  Parcheggio")
        val parkingCard = card("Dov'è parcheggiata l'auto")
        switchRow(parkingCard, "Salva la posizione quando scendo dall'auto", prefs.parkingEnabled) { checked ->
            prefs.parkingEnabled = checked
            CarAlertService.instance?.refresh()
        }
        parkingInfo = addLine(parkingCard)
        actionButton(parkingCard, "Mostra l'auto su mappa") { showParkingOnMap() }
        actionButton(parkingCard, "Portami all'auto a piedi") { walkToParking() }
        actionButton(parkingCard, "Condividi la posizione") { shareParking() }
        actionButton(parkingCard, "Cancella la posizione salvata") { clearParking() }
        note(
            parkingCard,
            "La posizione si salva da sola quando il Bluetooth dell'auto si disconnette (di solito quando spegni il motore). " +
                "Se il GPS non vede il cielo (garage, galleria) viene salvato l'ultimo punto noto e te lo segnalo. " +
                "I pulsanti aprono l'app di mappe del telefono (o Google Maps nel browser)."
        )

        // ---- Meteo ----
        section("meteo", "⛅  Meteo")
        val weatherCard = card("Allerta meteo per l'auto parcheggiata")
        switchRow(weatherCard, "Avvisami di grandine o gelata dove ho parcheggiato", prefs.weatherAlertEnabled) { checked ->
            prefs.weatherAlertEnabled = checked
            if (checked && prefs.carParked) WeatherScheduler.schedule(this, 5_000L) else WeatherScheduler.cancel(this)
            refreshStatus()
        }
        weatherInfo = addLine(weatherCard)
        actionButton(weatherCard, "Controlla adesso") { checkWeatherNow() }
        note(
            weatherCard,
            "Mentre l'auto è parcheggiata, ogni 2 ore il telefono chiede la previsione per quel punto. " +
                "Se nelle prossime 18 ore sono previsti grandine o temperature sotto zero di notte, ricevi una notifica urgente " +
                "con suono. Viene inviata solo la posizione approssimata (circa 1 km). Previsioni: Open-Meteo.com (CC BY 4.0). " +
                "La previsione della grandine è indicativa: non intercetta tutti i temporali."
        )

        // ---- Assistente vocale ----
        section("assistente", "🎙️  Ehi Mercedes")
        val assistantCard = card("Assistente vocale «Ehi Mercedes» (sperimentale)")
        switchRow(assistantCard, "Ascolta «Ehi Mercedes» quando sono in auto", prefs.assistantEnabled) { checked ->
            onAssistantSwitch(checked)
        }
        assistantInfo = addLine(assistantCard)
        actionButton(assistantCard, "Scarica il modello vocale italiano (circa 50 MB)") { downloadVoiceModel() }
        actionButton(assistantCard, "▶  Prova la risposta sul limite") { testLimitAnswer() }
        note(
            assistantCard,
            "Con l'auto collegata di' «Ehi Mercedes, qual è il limite di velocità?» oppure «Ehi Mercedes, che tempo fa oggi?» " +
                "(o «…domani?»). Puoi anche dire solo «Ehi Mercedes», aspettare «Dimmi» e poi chiedere. " +
                "Il riconoscimento avviene solo sul telefono: l'audio non viene inviato a nessuno. " +
                "Il modello vocale si scarica una volta (meglio con il Wi-Fi). Il microfono resta acceso finché l'auto è collegata: " +
                "consuma un po' di batteria. Dopo un riavvio del telefono apri l'app una volta per riattivare l'ascolto."
        )

        // ---- Intelligenza artificiale: risposte a qualsiasi domanda ----
        val aiCard = card("Risposte a qualsiasi domanda (intelligenza artificiale)")
        switchRow(aiCard, "Rispondi a qualsiasi domanda dopo «Ehi Mercedes»", prefs.aiEnabled) { checked ->
            prefs.aiEnabled = checked
            refreshStatus()
        }
        aiInfo = addLine(aiCard)
        note(
            aiCard,
            "Serve una chiave gratuita di Groq, senza carta di credito. Si fa una volta sola: " +
                "tocca il pulsante 1, accedi al sito e crea una chiave («Create API Key»), copiala, poi torna qui e tocca il pulsante 2."
        )
        actionButton(aiCard, "1 · Crea la chiave gratuita (si apre il sito di Groq)") { openAiKeyPage() }
        actionButton(aiCard, "2 · Incolla la chiave copiata") { pasteAiKey() }
        aiKeyInput = EditText(this)
        aiKeyInput.setHint("oppure scrivi o incolla qui la chiave (inizia con gsk_)")
        aiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        aiKeyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
        aiKeyInput.setText(prefs.aiKey)
        aiKeyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.aiKey = s?.toString() ?: ""
                renderAi()
            }
        })
        aiCard.addView(aiKeyInput)
        actionButton(aiCard, "3 · ▶  Prova l'intelligenza artificiale") { testAi() }
        switchRow(aiCard, "Dì all'IA in che città sono (solo il nome)", prefs.aiSendCity) { checked ->
            prefs.aiSendCity = checked
        }
        note(
            aiCard,
            "Ora puoi chiedere qualunque cosa: «Ehi Mercedes, quanto è alto il Monte Bianco?», «Ehi Mercedes, raccontami una barzelletta», " +
                "«Ehi Mercedes, cosa c'è da vedere qui?». I comandi di prima (limite di velocità, meteo) funzionano come sempre. " +
                "Privacy: l'audio resta sul telefono. All'intelligenza artificiale (Groq) va soltanto il testo della domanda, " +
                "con data e ora e, se l'opzione sopra è accesa, il nome della città, mai le coordinate. " +
                "Le ultime domande restano in memoria per 5 minuti (per capire «e di quello?») e si cancellano quando l'auto si disconnette. " +
                "La chiave resta solo in questa app. Le risposte non sono sempre esatte e non conoscono il traffico o le notizie di oggi."
        )

        // ---- Registro dei viaggi ----
        section("viaggi", "📒  Viaggi")
        val tripsCard = card("Registro dei viaggi")
        switchRow(tripsCard, "Registra i miei viaggi", prefs.tripLogEnabled) { checked ->
            prefs.tripLogEnabled = checked
            CarAlertService.instance?.refresh()
        }
        tripsSummary = addLine(tripsCard)
        tripsRecent = label("", 13f, Typeface.NORMAL, COLOR_TEXT)
        tripsCard.addView(tripsRecent)
        actionButton(tripsCard, "Esporta in Excel (.xlsx)") { exportTrips() }
        actionButton(tripsCard, "Cancella tutti i viaggi") { confirmClearTrips() }
        note(
            tripsCard,
            "Un viaggio inizia quando ti colleghi all'auto e finisce quando il Bluetooth si disconnette. " +
                "Per ogni viaggio si salvano durata, chilometri, velocità massima e quante volte è suonato l'avviso di limite superato " +
                "(serve l'avviso di velocità acceso). Si tengono solo i viaggi di almeno 300 m e 1 minuto. " +
                "I dati restano sul telefono: il file Excel lo salvi tu, dove preferisci."
        )

        // ---- Volume ----
        section("suono", null)
        val volumeCard = card("Volume")
        seekRow(volumeCard, { "Volume dei suoni dell'app: $it%" }, 20, 100, 5, prefs.volumePercent) {
            prefs.volumePercent = it
        }
        note(
            volumeCard,
            "Il volume finale dipende anche dal volume multimediale del telefono e dell'autoradio: regolalo una volta in auto."
        )

        // ---- Permessi e affidabilità ----
        section("permessi", "🔐  Permessi")
        val permCard = card("Permessi e affidabilità")
        note(
            permCard,
            "Per funzionare a telefono bloccato servono questi passaggi (una volta sola). Se qualcosa non va, ripercorrili in ordine."
        )
        actionButton(permCard, "1 · Concedi i permessi (posizione, Bluetooth, notifiche)") {
            requestPermissions(basicPermissions(), REQ_BASIC)
        }
        actionButton(permCard, "2 · Posizione: «Consenti sempre»") { requestBackgroundLocation() }
        actionButton(permCard, "3 · Batteria: nessuna restrizione") { openBatterySettings() }
        actionButton(permCard, "4 · Impostazioni dell'app (avvio automatico, batteria)") { openAppSettings() }
        note(
            permCard,
            "Su alcuni telefoni (Xiaomi, Huawei, Oppo, Samsung) nel passaggio 4 va attivato anche «Avvio automatico» e la batteria dell'app va messa su «Nessuna restrizione»."
        )

        target = null
        showSection(HOME)
    }

    // -------------------------------------------------------------------------------------------
    // Mattoncini grafici
    // -------------------------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(16).toFloat()
        return d
    }

    private fun label(text: String, sizeSp: Float, style: Int, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTypeface(null, style)
        t.setTextColor(color)
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(12), dp(16), dp(16))
        c.background = roundedBg(Color.WHITE)
        c.elevation = dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(14)
        (target ?: root).addView(c, lp)
        c.addView(label(title, 17f, Typeface.BOLD, COLOR_ACCENT))
        return c
    }

    /**
     * Da qui in poi le schede vanno nella sezione [key] (creata se non esiste).
     * [title] non nullo = nuova pagina con il pulsante «Indietro» in alto.
     */
    private fun section(key: String, title: String?) {
        val existing = sections[key]
        if (existing != null) {
            target = existing
            return
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.visibility = View.GONE
        root.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        sections[key] = box
        target = box
        if (title != null) {
            val back = Button(this)
            back.text = "←  Indietro"
            back.isAllCaps = false
            back.setOnClickListener { showSection(HOME) }
            box.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(label(title, 24f, Typeface.BOLD, COLOR_TEXT))
        }
    }

    private fun showSection(key: String) {
        currentSection = key
        for ((k, v) in sections) v.visibility = if (k == key) View.VISIBLE else View.GONE
        scrollView.scrollTo(0, 0)
        refreshStatus()
    }

    /** Griglia di icone (3 per riga) che aprono le funzioni. */
    private fun tileGrid(parent: LinearLayout, items: List<Triple<String, String, String>>) {
        var row: LinearLayout? = null
        items.forEachIndexed { i, (key, icon, text) ->
            if (i % 3 == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row = r
            }
            val tile = LinearLayout(this)
            tile.orientation = LinearLayout.VERTICAL
            tile.gravity = Gravity.CENTER
            tile.setPadding(dp(6), dp(14), dp(6), dp(14))
            tile.background = roundedBg(COLOR_BG)
            tile.isClickable = true
            tile.setOnClickListener { showSection(key) }
            val iconView = label(icon, 30f, Typeface.NORMAL, COLOR_TEXT)
            iconView.gravity = Gravity.CENTER
            val textView = label(text, 13f, Typeface.BOLD, COLOR_TEXT)
            textView.gravity = Gravity.CENTER
            tile.addView(iconView)
            tile.addView(textView)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row?.addView(tile, lp)
        }
        // completa l'ultima riga con spazi vuoti, così le icone restano allineate
        val rest = (3 - items.size % 3) % 3
        repeat(rest) {
            row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentSection != HOME) showSection(HOME) else super.onBackPressed()
    }

    private fun addLine(parent: LinearLayout): TextView {
        val t = label("", 15f, Typeface.NORMAL, COLOR_TEXT)
        parent.addView(t)
        return t
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(label(text, 12f, Typeface.NORMAL, COLOR_MUTED))
    }

    private fun actionButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        parent.addView(
            b,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun switchRow(
        parent: LinearLayout,
        text: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val s = Switch(this)
        s.text = text
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        s.setTextColor(COLOR_TEXT)
        s.isChecked = initial
        s.setPadding(0, dp(8), 0, dp(8))
        s.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        parent.addView(
            s,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun seekRow(
        parent: LinearLayout,
        labelFor: (Int) -> String,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        onChange: (Int) -> Unit
    ) {
        val value = initial.coerceIn(min, max)
        val title = label(labelFor(value), 14f, Typeface.NORMAL, COLOR_TEXT)
        parent.addView(title)

        val bar = SeekBar(this)
        bar.max = (max - min) / step
        bar.progress = (value - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                title.text = labelFor(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        parent.addView(bar)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------------------------
    // Stato in tempo reale
    // -------------------------------------------------------------------------------------------

    private fun refreshStatus() {
        val svc = CarAlertService.instance

        if (svc != null) {
            statusService.text = "●  Servizio attivo"
            statusService.setTextColor(COLOR_OK)
        } else {
            statusService.text = "○  Servizio non attivo"
            statusService.setTextColor(COLOR_BAD)
        }

        statusCar.text = if (svc != null && svc.carConnected) {
            "Auto: connessa (${prefs.carName})"
        } else {
            "Auto: non connessa"
        }

        statusSpeed.text = when {
            !prefs.speedEnabled -> "Avviso di velocità: disattivato"
            svc == null -> "GPS: —"
            !svc.gpsActive ->
                if (prefs.gpsOnlyWithCar) "GPS: spento (parte quando l'auto si collega)"
                else "GPS: non attivo (controlla i permessi)"
            CarAlertService.lastSpeedKmh < 0f -> "GPS acceso · in attesa del segnale…"
            else -> "Velocità: ${CarAlertService.lastSpeedKmh.roundToInt()} km/h"
        }

        statusLimit.text = when {
            !prefs.speedEnabled -> ""
            svc != null && svc.gpsActive && CarAlertService.limitInfo.isNotEmpty() ->
                CarAlertService.limitInfo
            prefs.autoLimit ->
                "Limite: automatico secondo la strada (manuale ${prefs.speedLimitKmh} km/h dove non è noto)"
            else -> "Limite impostato: ${prefs.speedLimitKmh} km/h"
        }
        statusLimit.visibility = if (statusLimit.text.isEmpty()) View.GONE else View.VISIBLE

        val trip = if (svc != null && svc.carConnected) svc.currentTrip() else null
        if (trip != null) {
            statusTrip.text = "Viaggio in corso: ${TextFormat.duration(trip.durationSec)} · " +
                "${TextFormat.km(trip.distanceKm)} · max ${TextFormat.kmh(trip.maxSpeedKmh)}"
            statusTrip.visibility = View.VISIBLE
        } else {
            statusTrip.visibility = View.GONE
        }

        if (prefs.assistantEnabled) {
            val info = if (svc != null) CarAlertService.assistantInfo else ""
            statusVoice.text = "🎙️ Ehi Mercedes: " + info.ifEmpty { "in preparazione" }
            statusVoice.visibility = View.VISIBLE
        } else {
            statusVoice.visibility = View.GONE
        }

        if (tripsCacheVersion != CarAlertService.tripsVersion) reloadTrips()
        renderParking(svc)
        renderWeather()
        renderAssistant(svc)
        renderAi()

        val loc = hasFineLocation()
        val bg = hasBackgroundLocation()
        val bt = hasBluetoothPermission()
        val bat = isIgnoringBattery()
        statusPerms.text = "Posizione ${mark(loc)}  ·  «Sempre» ${mark(bg)}  ·  Bluetooth ${mark(bt)}  ·  Batteria ${mark(bat)}"
        statusPerms.setTextColor(if (loc && bg && bt && bat) COLOR_OK else COLOR_WARN)
    }

    private fun mark(ok: Boolean): String = if (ok) "✓" else "✗"

    // -------------------------------------------------------------------------------------------
    // Permessi e impostazioni di sistema
    // -------------------------------------------------------------------------------------------

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasBasicPermissions(): Boolean = hasFineLocation() && hasBluetoothPermission()

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun basicPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun onServiceSwitch(checked: Boolean) {
        prefs.serviceEnabled = checked
        if (checked) {
            askedBackground = false
            askedBattery = false
            startSetup()
        } else {
            CarAlertService.stop(this)
        }
        refreshStatus()
    }

    /** Sequenza guidata: permessi base → posizione "sempre" → batteria. */
    private fun startSetup() {
        if (!hasBasicPermissions()) {
            requestPermissions(basicPermissions(), REQ_BASIC)
            return
        }
        afterBasicPermissions()
    }

    private fun afterBasicPermissions() {
        if (!CarAlertService.start(this)) {
            toast("Per attivare il servizio servono i permessi: usa il pulsante «1 · Concedi i permessi».")
            return
        }
        if (hasFineLocation() && !hasBackgroundLocation() && !askedBackground) {
            askedBackground = true
            AlertDialog.Builder(this)
                .setTitle("Posizione: «Consenti sempre»")
                .setMessage(
                    "Per controllare la velocità a telefono bloccato, nella prossima schermata scegli " +
                        "«Consenti sempre» (oppure «Sempre») per la posizione."
                )
                .setPositiveButton("Continua") { _, _ -> requestBackgroundLocation() }
                .setNegativeButton("Più tardi") { _, _ -> askBatteryExemption() }
                .show()
        } else {
            askBatteryExemption()
        }
    }

    private fun askBatteryExemption() {
        if (isIgnoringBattery() || askedBattery) return
        askedBattery = true
        AlertDialog.Builder(this)
            .setTitle("Batteria: nessuna restrizione")
            .setMessage(
                "Perché l'app resti attiva a telefono bloccato, Android deve smettere di limitarla " +
                    "per risparmiare batteria. Nella schermata che si apre scegli «Consenti»."
            )
            .setPositiveButton("Apri impostazione") { _, _ -> openBatterySettings() }
            .setNegativeButton("Più tardi", null)
            .show()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Su questo telefono non serve.")
            return
        }
        if (!hasFineLocation()) {
            requestPermissions(basicPermissions(), REQ_BASIC)
            return
        }
        if (hasBackgroundLocation()) {
            toast("La posizione «Sempre» è già consentita.")
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            toast("Apri le impostazioni del telefono → App → My Mercedes.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_BASIC -> {
                if (prefs.serviceEnabled) afterBasicPermissions()
                refreshStatus()
            }
            REQ_MIC -> {
                if (hasRecordAudio()) {
                    if (prefs.serviceEnabled) CarAlertService.start(this)
                    if (!VoiceModel.isReady(this)) downloadVoiceModel()
                } else {
                    toast("Senza il permesso del microfono l'assistente vocale non può ascoltare.")
                }
                refreshStatus()
            }
            REQ_BG -> {
                if (prefs.serviceEnabled) {
                    // riavvia il servizio: ora può ottenere la posizione anche in background
                    CarAlertService.start(this)
                    askBatteryExemption()
                }
                refreshStatus()
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Meteo
    // -------------------------------------------------------------------------------------------

    private fun renderWeather() {
        val text = when {
            !prefs.weatherAlertEnabled -> "Disattivata."
            !prefs.carParked -> "Si attiva quando parcheggi (alla disconnessione del Bluetooth)."
            prefs.weatherStatus.isNotEmpty() -> prefs.weatherStatus
            else -> "Auto parcheggiata: primo controllo entro pochi minuti."
        }
        if (weatherInfo.text.toString() != text) weatherInfo.text = text
    }

    private fun checkWeatherNow() {
        if (prefs.parkingSpot == null) {
            toast("Nessuna posizione di parcheggio salvata.")
            return
        }
        toast("Controllo la previsione…")
        val app = applicationContext
        Thread {
            // il controllo manuale vale anche con l'auto collegata, senza cambiare la pianificazione
            val wasParked = prefs.carParked
            if (!wasParked) prefs.carParked = true
            val result = WeatherCheck.run(app, reschedule = wasParked)
            if (!wasParked) prefs.carParked = false
            handler.post {
                toast(result)
                refreshStatus()
            }
        }.start()
    }

    // -------------------------------------------------------------------------------------------
    // Assistente vocale
    // -------------------------------------------------------------------------------------------

    private fun hasRecordAudio(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    private fun onAssistantSwitch(checked: Boolean) {
        prefs.assistantEnabled = checked
        if (checked) {
            if (!hasRecordAudio()) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                return
            }
            if (!VoiceModel.isReady(this)) downloadVoiceModel()
        }
        // il servizio riparte con (o senza) il microfono
        if (prefs.serviceEnabled) CarAlertService.start(this)
        CarAlertService.instance?.refresh()
        refreshStatus()
    }

    private fun renderAssistant(svc: CarAlertService?) {
        val text = when {
            VoiceModel.downloading -> VoiceModel.progressText
            !prefs.assistantEnabled -> "Spento."
            !hasRecordAudio() -> "Manca il permesso del microfono: spegni e riaccendi l'interruttore."
            !VoiceModel.isReady(this) ->
                VoiceModel.progressText.ifEmpty { "Manca il modello vocale: tocca «Scarica il modello vocale»." }
            svc == null -> "Il servizio non è attivo."
            else -> CarAlertService.assistantInfo.ifEmpty { "In preparazione…" }
        }
        if (assistantInfo.text.toString() != text) assistantInfo.text = text
    }

    private fun downloadVoiceModel() {
        if (VoiceModel.downloading) {
            toast("Download già in corso.")
            return
        }
        if (VoiceModel.isReady(this)) {
            toast("Il modello vocale è già sul telefono.")
            return
        }
        val app = applicationContext
        Thread {
            val ok = VoiceModel.download(app)
            handler.post {
                toast(if (ok) "Modello vocale pronto." else VoiceModel.progressText)
                if (ok) CarAlertService.instance?.refresh()
                refreshStatus()
            }
        }.start()
        refreshStatus()
    }

    private fun testLimitAnswer() {
        val answer = CarAlertService.instance?.spokenLimitAnswer()
            ?: "Il servizio non è attivo, quindi non conosco la strada."
        toast(answer)
        announcerInstance?.announce(answer, 0L)
    }

    // -------------------------------------------------------------------------------------------
    // Intelligenza artificiale (risposte a qualsiasi domanda)
    // -------------------------------------------------------------------------------------------

    private fun renderAi() {
        val sb = StringBuilder()
        val key = prefs.aiKey
        when {
            !prefs.aiEnabled -> sb.append("Spenta: «Ehi Mercedes» capisce solo i comandi che conosce già.")
            key.isBlank() -> sb.append("Manca la chiave gratuita: segui i passaggi 1 e 2 qui sotto.")
            else -> sb.append("Pronta. Chiave: ").append(AiText.maskKey(key))
        }
        if (prefs.aiEnabled && !prefs.assistantEnabled) {
            sb.append("\nPer usarla in auto accendi anche «Ascolta Ehi Mercedes» qui sopra.")
        }
        val last = prefs.aiStatus
        if (last.isNotEmpty()) sb.append("\nUltima richiesta: ").append(last)
        val text = sb.toString()
        if (aiInfo.text.toString() != text) aiInfo.text = text
    }

    private fun openAiKeyPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        } catch (e: Exception) {
            toast("Non trovo un browser per aprire il sito. Apri console.groq.com/keys dal tuo browser.")
        }
    }

    /** Prende dagli appunti la chiave copiata dal sito (anche se insieme c'è altro testo) e la salva. */
    private fun pasteAiKey() {
        val raw: String = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        } catch (e: Exception) {
            ""
        }
        val found = Regex("gsk_[A-Za-z0-9_]+").find(raw)?.value
        val key = found ?: AiText.cleanKey(raw)
        if (key.isEmpty()) {
            toast("Non c'è nessuna chiave negli appunti. Copia prima la chiave dal sito di Groq, poi tocca di nuovo questo pulsante.")
            return
        }
        aiKeyInput.setText(key)
        if (key.startsWith("gsk_")) {
            toast("Chiave incollata. Ora tocca «3 · Prova».")
        } else {
            toast("Incollato, ma una chiave di Groq di solito inizia con «gsk_»: controlla di aver copiato quella giusta.")
        }
    }

    /** Fa una domanda di prova e la dice a voce: così si vede subito se la chiave funziona. */
    private fun testAi() {
        val key = prefs.aiKey
        if (key.isBlank()) {
            toast("Prima serve la chiave: segui i passaggi 1 e 2.")
            return
        }
        if (aiTesting) {
            toast("Prova già in corso.")
            return
        }
        aiTesting = true
        toast("Provo a interrogare l'intelligenza artificiale…")
        val rememberedModel = prefs.aiModel
        Thread {
            val now = System.currentTimeMillis()
            val spoken = try {
                AiSession().answer(
                    key,
                    rememberedModel,
                    "Presentati in una frase e dimmi che sei pronto a rispondere alle mie domande.",
                    now,
                    null,
                    remember = false
                )
            } catch (e: Exception) {
                null
            }
            if (spoken != null) {
                if (spoken.ok && spoken.model != null) prefs.aiModel = spoken.model
                prefs.aiStatus = AiText.statusLine(now, spoken.problem, spoken.model, spoken.elapsedMs, spoken.detail)
            }
            handler.post {
                aiTesting = false
                val text = spoken?.text ?: AiText.problemSpeech(AiProblem.SERVER)
                toast(text)
                announcerInstance?.announce(text, 0L, AiText.speechBudgetMs(text, prefs.voiceRatePercent))
                refreshStatus()
            }
        }.start()
    }

    // -------------------------------------------------------------------------------------------
    // Dov'è parcheggiata l'auto
    // -------------------------------------------------------------------------------------------

    private fun renderParking(svc: CarAlertService?) {
        val spot = prefs.parkingSpot
        val sb = StringBuilder()
        if (spot == null) {
            sb.append("Nessuna posizione salvata: verrà salvata la prossima volta che il Bluetooth dell'auto si disconnette.")
        } else {
            val now = System.currentTimeMillis()
            sb.append("Salvata: ${TextFormat.dateTime(spot.savedAtMs)} (${TextFormat.ago(now, spot.savedAtMs)})")
            sb.append("\nCoordinate: ${TextFormat.coord(spot.lat, spot.lon)}")
            if (spot.fixAgeAtSaveSec > ParkingSpot.APPROX_AGE_SEC) {
                sb.append(
                    "\n⚠ Il GPS non aveva segnale da ${TextFormat.duration(spot.fixAgeAtSaveSec)}: " +
                        "è l'ultimo punto noto, l'auto potrebbe essere un po' più avanti (garage o galleria?)."
                )
            } else if (spot.accuracyM.isFinite() && spot.accuracyM > ParkingSpot.APPROX_ACCURACY_M) {
                sb.append("\n⚠ Precisione scarsa (circa ${Math.round(spot.accuracyM)} m).")
            }
            if (svc != null && svc.carConnected) {
                sb.append("\nL'auto è collegata ora: la nuova posizione verrà salvata quando si disconnette.")
            }
        }
        if (!prefs.parkingEnabled) sb.append("\n(Salvataggio automatico disattivato)")

        val text = sb.toString()
        if (parkingInfo.text.toString() != text) parkingInfo.text = text
    }

    private fun requireSpot(): ParkingSpot? {
        val spot = prefs.parkingSpot
        if (spot == null) toast("Nessuna posizione salvata ancora.")
        return spot
    }

    private fun showParkingOnMap() {
        val spot = requireSpot() ?: return
        try {
            val uri = Uri.parse(MapLinks.geoUri(spot.lat, spot.lon, "Auto parcheggiata"))
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            // nessuna app di mappe: si apre la pagina web
            openWeb(MapLinks.webPoint(spot.lat, spot.lon))
        }
    }

    private fun walkToParking() {
        val spot = requireSpot() ?: return
        openWeb(MapLinks.webWalkTo(spot.lat, spot.lon))
    }

    private fun openWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Non trovo un'app per aprire la mappa.")
        }
    }

    private fun shareParking() {
        val spot = requireSpot() ?: return
        val text = "La mia auto è parcheggiata qui: ${MapLinks.webPoint(spot.lat, spot.lon)}"
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(send, "Condividi la posizione dell'auto"))
        } catch (e: Exception) {
            toast("Non trovo un'app con cui condividere.")
        }
    }

    private fun clearParking() {
        prefs.parkingSpot = null
        prefs.carParked = false
        WeatherScheduler.cancel(this)
        toast("Posizione cancellata.")
        refreshStatus()
    }

    // -------------------------------------------------------------------------------------------
    // Registro dei viaggi
    // -------------------------------------------------------------------------------------------

    private fun reloadTrips() {
        tripsCacheVersion = CarAlertService.tripsVersion
        tripsCache = tripStore.loadAll()
        renderTrips()
    }

    private fun renderTrips() {
        val trips = tripsCache
        if (trips.isEmpty()) {
            tripsSummary.text = "Nessun viaggio registrato ancora."
            tripsRecent.text = ""
            tripsRecent.visibility = View.GONE
            return
        }

        val totalKm = trips.sumOf { it.distanceKm }
        val totalSec = trips.sumOf { it.durationSec }
        val monitored = trips.filter { it.speedMonitored }
        val summary = StringBuilder()
        summary.append("Viaggi registrati: ${trips.size} · ${TextFormat.km(totalKm)} · ${TextFormat.duration(totalSec)} alla guida")
        if (monitored.isNotEmpty()) {
            summary.append("\nSuperamenti del limite in totale: ${monitored.sumOf { it.overspeedCount }}")
        }
        tripsSummary.text = summary.toString()

        val recent = trips.sortedByDescending { it.startMs }.take(5)
        tripsRecent.text = "Ultimi viaggi:\n\n" + recent.joinToString("\n\n") { t ->
            val over = if (t.speedMonitored) {
                val n = t.overspeedCount
                "$n ${if (n == 1) "superamento" else "superamenti"} del limite"
            } else {
                "velocità non controllata"
            }
            "${TextFormat.shortDateTime(t.startMs)} · ${TextFormat.km(t.distanceKm)} · ${TextFormat.duration(t.durationSec)}" +
                "\n    max ${TextFormat.kmh(t.maxSpeedKmh)} · $over"
        }
        tripsRecent.visibility = View.VISIBLE
    }

    private fun exportTrips() {
        reloadTrips()
        if (tripsCache.isEmpty()) {
            toast("Non ci sono ancora viaggi da esportare.")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(TripExport.MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, TripExport.suggestedFileName(System.currentTimeMillis()))
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: Exception) {
            toast("Questo telefono non permette di salvare file da qui.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        writeExport(uri)
    }

    private fun writeExport(uri: Uri) {
        val count = tripsCache.size
        try {
            val bytes = TripExport.toXlsx(tripsCache)
            val out = contentResolver.openOutputStream(uri, "wt")
            if (out == null) {
                toast("Non sono riuscito a salvare il file.")
                return
            }
            out.use { it.write(bytes) }
        } catch (e: Exception) {
            toast("Non sono riuscito a salvare il file.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("File salvato")
            .setMessage("Il file Excel con $count viaggi è stato salvato dove hai scelto.")
            .setPositiveButton("Apri") { _, _ -> openExported(uri) }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun openExported(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, TripExport.MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            toast("Il file è salvato, ma sul telefono non c'è un'app per aprire i file Excel (prova Fogli Google o Microsoft Excel).")
        }
    }

    private fun confirmClearTrips() {
        if (tripsCache.isEmpty()) {
            toast("Non c'è niente da cancellare.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Cancellare tutti i viaggi?")
            .setMessage("Verranno eliminati ${tripsCache.size} viaggi dal telefono. Se ti servono, esportali prima in Excel.")
            .setPositiveButton("Cancella") { _, _ ->
                tripStore.clearAll()
                reloadTrips()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // -------------------------------------------------------------------------------------------
    // Voce: scelta e prove
    // -------------------------------------------------------------------------------------------

    private fun currentPhrase(): String {
        return prefs.voicePhrase.trim().ifEmpty { Prefs.DEFAULT_PHRASE }
    }

    private fun pickVoice() {
        val a = announcerInstance
        if (a == null || !a.isReady()) {
            toast(a?.problemText() ?: "Voce non disponibile.")
            return
        }
        val voices = a.italianVoices()
        if (voices.isEmpty()) {
            toast("Nessuna voce italiana trovata. Aprila da «Impostazioni di sintesi vocale» e scarica l'italiano.")
            return
        }
        val labels = voices.mapIndexed { index, v ->
            val online = if (v.isNetworkConnectionRequired) " · online" else ""
            "Voce ${index + 1}  (${v.name})$online"
        }.toTypedArray()
        val selected = voices.indexOfFirst { it.name == prefs.voiceName }

        AlertDialog.Builder(this)
            .setTitle("Scegli la voce")
            .setSingleChoiceItems(labels, selected) { _, which ->
                prefs.voiceName = voices[which].name
                a.announce(currentPhrase(), 0L)
            }
            .setPositiveButton("Fatto", null)
            .setNeutralButton("Voce predefinita") { _, _ -> prefs.voiceName = "" }
            .show()
    }

    private fun testChimeAndVoice() {
        val a = announcerInstance
        if (a != null && !a.isReady()) toast(a.problemText())
        val phrase = currentPhrase()
        testPlayer.play(SoundSynth.chime(prefs.chimeStyle), prefs.volumePercent) {
            announcerInstance?.announce(phrase, 250L)
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("Apri Impostazioni → Sistema → Lingua → Sintesi vocale.")
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Scelta dell'auto tra i dispositivi già associati
    // -------------------------------------------------------------------------------------------

    private fun pickPairedDevice() {
        if (!hasBluetoothPermission()) {
            requestPermissions(basicPermissions(), REQ_BASIC)
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            toast("Bluetooth non disponibile su questo telefono.")
            return
        }
        val devices: List<BluetoothDevice> = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            toast("Nessun dispositivo Bluetooth associato. Associa prima l'auto dalle impostazioni Bluetooth del telefono.")
            return
        }
        val names = devices.map { BtUtils.nameOf(it) ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Scegli l'auto")
            .setItems(names) { _, which ->
                val d = devices[which]
                carNameInput.setText(BtUtils.nameOf(d) ?: d.address)
                prefs.carAddress = d.address
                CarAlertService.instance?.refresh()
            }
            .show()
    }
}
