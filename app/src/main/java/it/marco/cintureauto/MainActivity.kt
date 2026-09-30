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
    private lateinit var statusPerms: TextView
    private lateinit var carNameInput: EditText

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
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        testPlayer.stop()
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
                "Il \"ding\" delle cinture appena ti colleghi all'auto, più un avviso quando superi la velocità impostata.",
                14f, Typeface.NORMAL, COLOR_MUTED
            )
        )

        // ---- Stato ----
        val statusCard = card("Stato")
        statusService = addLine(statusCard)
        statusCar = addLine(statusCard)
        statusSpeed = addLine(statusCard)
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

        // ---- Avviso velocità ----
        val speedCard = card("Avviso di velocità")
        switchRow(speedCard, "Avvisami se supero il limite", prefs.speedEnabled) { checked ->
            prefs.speedEnabled = checked
            CarAlertService.instance?.refresh()
        }
        seekRow(speedCard, { "Limite: $it km/h" }, 30, 250, 5, prefs.speedLimitKmh) {
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
            else -> "Velocità: ${CarAlertService.lastSpeedKmh.roundToInt()} km/h  (limite ${prefs.speedLimitKmh})"
        }

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
