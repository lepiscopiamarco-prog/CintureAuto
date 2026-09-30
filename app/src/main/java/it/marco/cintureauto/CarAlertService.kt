package it.marco.cintureauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Il "cuore" dell'app. Gira sempre in primo piano (con la notifica fissa) e:
 *  1. ascolta la connessione/disconnessione Bluetooth dell'auto e suona il "ding" delle cinture;
 *  2. legge la velocità dal GPS e suona l'avviso quando si supera il limite impostato.
 */
class CarAlertService : Service() {

    companion object {
        const val ACTION_START = "it.marco.cintureauto.START"
        const val ACTION_BT_EVENT = "it.marco.cintureauto.BT_EVENT"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_NAME = "name"
        const val EXTRA_ADDRESS = "address"

        private const val CHANNEL_ID = "servizio_cinture"
        private const val NOTIFICATION_ID = 1001

        /** Tempo massimo di attesa che l'audio Bluetooth dell'auto sia pronto. */
        private const val MAX_WAIT_AUDIO_MS = 8000L

        /** Evita di ripetere il "ding" se il Bluetooth si riconnette più volte di seguito. */
        private const val CHIME_DEBOUNCE_MS = 30_000L

        /** Sotto (limite - questo valore) l'avviso si "riarma". */
        private const val HYSTERESIS_KMH = 3f

        @Volatile
        var instance: CarAlertService? = null

        @Volatile
        var lastSpeedKmh: Float = -1f

        /** Serve almeno il permesso Posizione oppure quello Bluetooth per restare in primo piano. */
        fun canStart(context: Context): Boolean {
            return BtUtils.hasFineLocation(context) || BtUtils.hasBluetoothPermission(context)
        }

        /** Avvia (o riattiva) il servizio. Restituisce false se Android non lo permette. */
        fun start(context: Context): Boolean {
            if (!canStart(context)) return false
            return try {
                val i = Intent(context, CarAlertService::class.java).setAction(ACTION_START)
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarAlertService::class.java))
        }
    }

    // ---- stato visibile dalla schermata principale ----
    @Volatile
    var carConnected: Boolean = false
        private set

    @Volatile
    var gpsActive: Boolean = false
        private set

    private lateinit var prefs: Prefs
    private lateinit var player: SoundPlayer
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var receiverRegistered = false

    /** Vero se Android ha concesso al servizio l'accesso alla posizione in background. */
    @Volatile
    var fgsHasLocation: Boolean = true
        private set
    private var pendingChime: Runnable? = null
    private var lastChimeAt: Long = -1L

    // ---- stato del controllo velocità ----
    private var previousLocation: Location? = null
    private var overCount = 0
    private var overLimit = false
    private var lastAlarmAt = 0L

    // -------------------------------------------------------------------------------------------
    // Ciclo di vita
    // -------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        player = SoundPlayer(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            // Permessi mancanti: senza non si può restare attivi in primo piano.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!initialized) {
            initialized = true
            registerBluetoothReceiver()
            detectCarAlreadyConnected()
        }

        if (intent != null && intent.action == ACTION_BT_EVENT) {
            handleBluetoothEvent(
                intent.getBooleanExtra(EXTRA_CONNECTED, false),
                intent.getStringExtra(EXTRA_NAME),
                intent.getStringExtra(EXTRA_ADDRESS)
            )
        }

        updateGpsState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopGps()
        cancelPendingChime()
        if (receiverRegistered) {
            try {
                unregisterReceiver(btReceiver)
            } catch (e: Exception) {
                // già rimosso
            }
            receiverRegistered = false
        }
        player.stop()
        lastSpeedKmh = -1f
        instance = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------------
    // Notifica in primo piano
    // -------------------------------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servizio Cinture Auto",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Notifica fissa che tiene attiva l'app in background"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun enterForeground(): Boolean {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT < 29) {
            return try {
                startForeground(NOTIFICATION_ID, notification)
                fgsHasLocation = true
                true
            } catch (e: Exception) {
                false
            }
        }

        // 1) Modalità completa: "posizione" (GPS a schermo spento) + Bluetooth.
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            fgsHasLocation = true
            return true
        } catch (e: Exception) {
            // Android non lo consente (tipicamente: avvio in background senza posizione "Sempre")
        }

        // 2) Ripiego: solo Bluetooth. Il "ding" funziona, il GPS riparte quando si apre l'app.
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            fgsHasLocation = false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_belt)
            .setContentTitle("Cinture Auto attiva")
            .setContentText(statusText())
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun statusText(): String {
        val car = prefs.carName
        val limit = prefs.speedLimitKmh
        val base = when {
            carConnected && gpsActive -> "Connesso a «$car» · GPS attivo · limite $limit km/h"
            carConnected -> "Connesso a «$car»"
            gpsActive -> "GPS attivo · limite $limit km/h"
            else -> "In attesa di «$car»"
        }
        return if (prefs.speedEnabled && !fgsHasLocation) {
            "$base · GPS non disponibile: apri l'app e concedi la posizione «Sempre»"
        } else {
            base
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // ignora
        }
    }

    /** Chiamato dalla schermata principale quando cambiano le impostazioni. */
    fun refresh() {
        updateGpsState()
    }

    // -------------------------------------------------------------------------------------------
    // Bluetooth
    // -------------------------------------------------------------------------------------------

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = BtUtils.deviceFrom(intent) ?: return
            val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
            handleBluetoothEvent(connected, BtUtils.nameOf(device), device.address)
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(btReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun handleBluetoothEvent(connected: Boolean, name: String?, address: String?) {
        if (!BtUtils.matchesCar(prefs, name, address)) return
        if (connected) onCarConnected() else onCarDisconnected()
    }

    /**
     * Se il servizio parte mentre il telefono è già collegato all'auto (es. dopo un riavvio
     * del servizio), lo scopre senza far suonare il "ding" una seconda volta.
     */
    private fun detectCarAlreadyConnected() {
        if (!BtUtils.hasBluetoothPermission(this)) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return

        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        for (profileId in profiles) {
            try {
                adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val found = proxy.connectedDevices.any {
                                BtUtils.matchesCar(prefs, BtUtils.nameOf(it), it.address)
                            }
                            if (found && !carConnected) {
                                carConnected = true
                                updateGpsState()
                            }
                        } catch (e: SecurityException) {
                            // permesso Bluetooth mancante
                        } finally {
                            adapter.closeProfileProxy(profile, proxy)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                }, profileId)
            } catch (e: SecurityException) {
                // permesso Bluetooth mancante
            }
        }
    }

    private fun onCarConnected() {
        carConnected = true
        resetSpeedState()
        updateGpsState()

        if (prefs.chimeEnabled) {
            val now = SystemClock.elapsedRealtime()
            if (lastChimeAt < 0L || now - lastChimeAt > CHIME_DEBOUNCE_MS) {
                scheduleChime()
            }
        }
    }

    private fun onCarDisconnected() {
        carConnected = false
        cancelPendingChime()
        resetSpeedState()
        updateGpsState()
    }

    // -------------------------------------------------------------------------------------------
    // Suono "cinture allacciate"
    // -------------------------------------------------------------------------------------------

    private fun isBluetoothAudioReady(): Boolean {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Aspetta che l'audio Bluetooth dell'auto sia davvero pronto (altrimenti il suono uscirebbe
     * dalle casse del telefono), poi attende il ritardo impostato e suona.
     */
    private fun scheduleChime() {
        cancelPendingChime()
        val startedAt = SystemClock.elapsedRealtime()
        lastChimeAt = startedAt
        val extraDelayMs = prefs.chimeDelaySec * 1000L

        val poll = object : Runnable {
            override fun run() {
                val waited = SystemClock.elapsedRealtime() - startedAt
                if (isBluetoothAudioReady() || waited >= MAX_WAIT_AUDIO_MS) {
                    val play = Runnable { playChime() }
                    pendingChime = play
                    handler.postDelayed(play, extraDelayMs)
                } else {
                    handler.postDelayed(this, 400L)
                }
            }
        }
        pendingChime = poll
        handler.post(poll)
    }

    private fun cancelPendingChime() {
        pendingChime?.let { handler.removeCallbacks(it) }
        pendingChime = null
    }

    private fun playChime() {
        pendingChime = null
        player.play(SoundSynth.chime(prefs.chimeStyle), prefs.volumePercent)
    }

    // -------------------------------------------------------------------------------------------
    // GPS e avviso di velocità
    // -------------------------------------------------------------------------------------------

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun updateGpsState() {
        val wantGps = prefs.speedEnabled && (carConnected || !prefs.gpsOnlyWithCar)
        if (wantGps) startGps() else stopGps()
        updateNotification()
    }

    private fun startGps() {
        if (gpsActive) return
        if (!BtUtils.hasFineLocation(this)) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            gpsActive = true
            previousLocation = null
        } catch (e: SecurityException) {
            gpsActive = false
        } catch (e: IllegalArgumentException) {
            // questo telefono non ha il GPS
            gpsActive = false
        }
    }

    private fun stopGps() {
        if (!gpsActive) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            // ignora
        }
        gpsActive = false
        previousLocation = null
        lastSpeedKmh = -1f
    }

    private fun resetSpeedState() {
        overCount = 0
        overLimit = false
        lastAlarmAt = 0L
    }

    private fun handleLocation(location: Location) {
        val kmh = speedFrom(location)
        lastSpeedKmh = kmh
        previousLocation = location
        if (prefs.speedEnabled) evaluateSpeed(kmh)
    }

    private fun speedFrom(location: Location): Float {
        if (location.hasSpeed()) return location.speed * 3.6f
        val prev = previousLocation ?: return 0f
        val seconds = (location.time - prev.time) / 1000f
        if (seconds <= 0f) return 0f
        return prev.distanceTo(location) / seconds * 3.6f
    }

    private fun evaluateSpeed(kmh: Float) {
        val limit = prefs.speedLimitKmh
        if (kmh >= limit) {
            overCount += 1
            // servono due letture consecutive sopra il limite, per ignorare un singolo "salto" del GPS
            if (overCount >= 2) {
                val now = SystemClock.elapsedRealtime()
                val repeatMs = prefs.repeatSec * 1000L
                val due = if (!overLimit) {
                    true
                } else {
                    repeatMs > 0L && now - lastAlarmAt >= repeatMs
                }
                if (due) {
                    overLimit = true
                    lastAlarmAt = now
                    player.play(SoundSynth.alarm(), prefs.volumePercent)
                }
            }
        } else {
            overCount = 0
            if (kmh < limit - HYSTERESIS_KMH) {
                overLimit = false
            }
        }
    }
}
