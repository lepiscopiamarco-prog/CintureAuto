package it.marco.cintureauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.text.TextWatcher
import android.util.TypedValue
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
            if (svc == null || !svc.fgsHasLocation) CarAlertService.start(this)
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

        root.addView(label("Cinture Auto", 28f, Typeface.BOLD, COLOR_TEXT))
        root.addView(
            label(
                "Il \"ding\" delle cinture appena ti colleghi all'auto, un avviso quando superi il limite, la posizione dell'auto parcheggiata e il registro dei tuoi viaggi.",
                14f, Typeface.NORMAL, COLOR_MUTED
            )
        )

        // ---- Stato ----
        val statusCard = card("Stato")
        statusService = addLine(statusCard)
        statusCar = addLine(statusCard)
        statusSpeed = addLine(statusCard)
        statusLimit = addLine(statusCard)
        statusTrip = addLine(statusCard)
        statusPerms = addLine(statusCard)
        switchRow(statusCard, "Servizio sempre attivo", prefs.serviceEnabled) { checked ->
            onServiceSwitch(checked)
        }
        note(
            statusCard,
            "Tienilo acceso: l'app resta attiva in background (vedrai una notifica fissa) e riparte da sola quando accendi il telefono."
        )

        // ---- Auto ----
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

        // ---- Registro dei viaggi ----
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
        val volumeCard = card("Volume")
        seekRow(volumeCard, { "Volume dei suoni dell'app: $it%" }, 20, 100, 5, prefs.volumePercent) {
            prefs.volumePercent = it
        }
        note(
            volumeCard,
            "Il volume finale dipende anche dal volume multimediale del telefono e dell'autoradio: regolalo una volta in auto."
        )

        // ---- Permessi e affidabilità ----
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
        root.addView(c, lp)
        c.addView(label(title, 17f, Typeface.BOLD, COLOR_ACCENT))
        return c
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

        if (tripsCacheVersion != CarAlertService.tripsVersion) reloadTrips()
        renderParking(svc)

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
            toast("Apri le impostazioni del telefono → App → Cinture Auto.")
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
