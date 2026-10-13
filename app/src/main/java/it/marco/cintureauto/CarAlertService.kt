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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Il "cuore" dell'app. Gira sempre in primo piano (con la notifica fissa) e:
 *  1. ascolta la connessione/disconnessione Bluetooth dell'auto, suona il "ding" delle cinture
 *     e poi dice la frase a voce;
 *  2. legge la velocità dal GPS, ricava il limite della strada in cui ti trovi e suona
 *     l'avviso quando lo superi.
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

        /** Pausa tra la fine del suono e l'inizio della voce. */
        private const val VOICE_GAP_MS = 250L

        /** Sotto (soglia - questo valore) l'avviso si "riarma". */
        private const val HYSTERESIS_KMH = 3f

        // ---- limite automatico ----

        /** Oltre questa velocità si scarica un'area più grande (meno richieste in autostrada). */
        private const val FAST_KMH = 80f
        private const val RADIUS_SLOW_M = 450
        private const val RADIUS_FAST_M = 900

        /** Si scaricano nuovi dati quando ci si allontana dal centro di più di questa frazione del raggio. */
        private const val REFETCH_FRACTION = 0.6

        /** Pausa minima tra due richieste riuscite, e attesa dopo una richiesta fallita. */
        private const val MIN_FETCH_GAP_MS = 8_000L
        private const val RETRY_MS = 60_000L

        /** La direzione del GPS è affidabile solo se ci si muove abbastanza in fretta. */
        private const val MIN_HEADING_KMH = 8f

        // ---- GPS per parcheggio e registro viaggi ----

        /** Con il controllo velocità serve una lettura al secondo; senza, ogni 2 secondi bastano. */
        private const val GPS_FAST_MS = 1000L
        private const val GPS_SLOW_MS = 2000L

        /** Il viaggio in corso viene salvato su disco ogni tanto, per non perderlo se Android chiude l'app. */
        private const val SNAPSHOT_MS = 30_000L

        // ---- parcheggio ----

        /** Una lettura GPS più recente di così vale come "posizione dell'auto". */
        private const val PARKING_FRESH_MS = 15 * 60_000L

        /** Altrimenti si ripiega sull'ultima posizione nota del telefono, se non troppo vecchia o imprecisa. */
        private const val PARKING_LASTKNOWN_MS = 30 * 60_000L
        private const val PARKING_LASTKNOWN_MAX_ACCURACY_M = 200f

        /** Il Bluetooth può mandare due volte lo stesso evento: si salva una sola volta. */
        private const val PARKING_DEDUPE_MS = 15_000L

        private const val DATA_NONE = 0
        private const val DATA_OK = 1
        private const val DATA_ERROR = 2

        @Volatile
        var instance: CarAlertService? = null

        @Volatile
        var lastSpeedKmh: Float = -1f

        /** Descrizione del limite in uso, mostrata nella schermata principale. */
        @Volatile
        var limitInfo: String = ""

        /** Aumenta ogni volta che l'elenco dei viaggi cambia: la schermata lo usa per aggiornarsi. */
        @Volatile
        var tripsVersion: Int = 0

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
    private lateinit var announcer: VoiceAnnouncer
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var destroyed = false
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

    // ---- stato di parcheggio e registro dei viaggi ----
    private lateinit var store: TripStore
    private var lastFix: Location? = null
    private var recorder: TripRecorder? = null
    private var lastSnapshotAt = 0L
    private var lastParkingSaveAt = 0L
    private var gpsIntervalMs = 0L

    // ---- stato del limite automatico ----
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tracker = LimitTracker()
    private var roadData: RoadData? = null
    private var fetchInFlight = false
    private var nextFetchAt = 0L
    private var dataState = DATA_NONE

    // -------------------------------------------------------------------------------------------
    // Ciclo di vita
    // -------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        player = SoundPlayer(this)
        announcer = VoiceAnnouncer(this, prefs)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        store = TripStore(this)
        createChannel()
        recoverInterruptedTrip()
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
        destroyed = true
        finishTrip()
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
        announcer.shutdown()
        executor.shutdownNow()
        lastSpeedKmh = -1f
        limitInfo = ""
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
        val limit = baseLimit()
        val auto = prefs.autoLimit && tracker.limitKmh > 0
        val limitText = if (auto) "limite $limit km/h (auto)" else "limite $limit km/h"
        val gpsPart = if (prefs.speedEnabled) "GPS attivo · $limitText" else "GPS attivo"
        val base = when {
            carConnected && gpsActive -> "Connesso a «$car» · $gpsPart"
            carConnected -> "Connesso a «$car»"
            gpsActive -> gpsPart
            else -> "In attesa di «$car»"
        }
        val needsGps = prefs.speedEnabled || prefs.parkingEnabled || prefs.tripLogEnabled
        return if (needsGps && !fgsHasLocation) {
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
        limitInfo = describeLimit()
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

        if (prefs.chimeEnabled || prefs.voiceEnabled) {
            val now = SystemClock.elapsedRealtime()
            if (lastChimeAt < 0L || now - lastChimeAt > CHIME_DEBOUNCE_MS) {
                scheduleChime()
            }
        }
    }

    private fun onCarDisconnected() {
        carConnected = false
        cancelPendingChime()
        player.stop()
        resetSpeedState()
        // Prima di spegnere il GPS: la posizione dell'auto e la chiusura del viaggio
        saveParking()
        finishTrip()
        updateGpsState()
    }

    // -------------------------------------------------------------------------------------------
    // Suono "cinture allacciate" e voce
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
        announcer.cancel()
    }

    /** Suono, poi (se attiva) la voce. Se il suono è spento, solo la voce. */
    private fun playChime() {
        pendingChime = null
        val phrase = prefs.voicePhrase.trim().ifEmpty { Prefs.DEFAULT_PHRASE }
        val speak: () -> Unit = { announcer.announce(phrase, VOICE_GAP_MS) }

        if (prefs.chimeEnabled) {
            player.play(
                SoundSynth.chime(prefs.chimeStyle),
                prefs.volumePercent,
                if (prefs.voiceEnabled) speak else null
            )
        } else if (prefs.voiceEnabled) {
            announcer.announce(phrase, 0L)
        }
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
        // Con l'auto collegata il GPS serve per la velocità, per la posizione di parcheggio
        // e per il registro viaggi. Fuori dall'auto serve solo se l'utente ha scelto "GPS sempre".
        val wantForCar = carConnected &&
            (prefs.speedEnabled || prefs.parkingEnabled || prefs.tripLogEnabled)
        val wantAlways = prefs.speedEnabled && !prefs.gpsOnlyWithCar
        val interval = if (prefs.speedEnabled) GPS_FAST_MS else GPS_SLOW_MS

        if (wantForCar || wantAlways) {
            if (gpsActive && interval != gpsIntervalMs) stopGps()
            startGps(interval)
        } else {
            stopGps()
        }
        syncTrip()
        updateNotification()
    }

    private fun startGps(intervalMs: Long) {
        if (gpsActive) return
        if (!BtUtils.hasFineLocation(this)) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            gpsActive = true
            gpsIntervalMs = intervalMs
            previousLocation = null
            lastFix = null
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
        tracker.clear()
        limitInfo = ""
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
        lastFix = location
        feedTrip(location, kmh)
        if (!prefs.speedEnabled) return

        if (prefs.autoLimit) updateAutoLimit(location, kmh)
        limitInfo = describeLimit()
        evaluateSpeed(kmh)
    }

    private fun speedFrom(location: Location): Float {
        if (location.hasSpeed()) return location.speed * 3.6f
        val prev = previousLocation ?: return 0f
        val seconds = (location.time - prev.time) / 1000f
        if (seconds <= 0f) return 0f
        return prev.distanceTo(location) / seconds * 3.6f
    }

    // -------------------------------------------------------------------------------------------
    // Registro dei viaggi
    // -------------------------------------------------------------------------------------------

    /** Fotografia del viaggio in corso (null se non ce n'è uno). Usata dalla schermata principale. */
    fun currentTrip(): Trip? = recorder?.snapshot(System.currentTimeMillis())

    /** Un viaggio dura finché l'auto resta collegata: si apre alla connessione e si chiude alla disconnessione. */
    private fun syncTrip() {
        if (carConnected && prefs.tripLogEnabled) {
            if (recorder == null) {
                recorder = TripRecorder(System.currentTimeMillis())
                lastSnapshotAt = SystemClock.elapsedRealtime()
            }
        } else {
            finishTrip()
        }
    }

    private fun feedTrip(location: Location, kmh: Float) {
        val r = recorder ?: return
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
        r.onFix(
            location.elapsedRealtimeNanos / 1_000_000L,
            location.latitude,
            location.longitude,
            kmh.toDouble(),
            accuracy
        )
        if (prefs.speedEnabled) r.markMonitored()

        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAt >= SNAPSHOT_MS) {
            lastSnapshotAt = now
            store.saveCurrent(r.snapshot(System.currentTimeMillis()))
        }
    }

    /** Chiude il viaggio: lo archivia se è un viaggio vero, lo scarta se è troppo breve. */
    private fun finishTrip() {
        val r = recorder ?: return
        recorder = null
        val trip = r.finish(System.currentTimeMillis())
        store.clearCurrent()
        if (trip.worthKeeping()) {
            store.append(trip)
            tripsVersion += 1
        }
    }

    /** Se l'app è stata chiusa da Android a metà viaggio, recupera quanto era stato salvato. */
    private fun recoverInterruptedTrip() {
        val left = store.loadCurrent() ?: return
        store.clearCurrent()
        if (left.worthKeeping()) {
            store.append(left)
            tripsVersion += 1
        }
    }

    // -------------------------------------------------------------------------------------------
    // Dov'è parcheggiata l'auto
    // -------------------------------------------------------------------------------------------

    /** Salva l'ultima posizione nota del telefono: è quella in cui hai lasciato l'auto. */
    private fun saveParking() {
        if (!prefs.parkingEnabled) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastParkingSaveAt != 0L && nowElapsed - lastParkingSaveAt < PARKING_DEDUPE_MS) return

        val fix = bestParkingFix() ?: return
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L)
            .coerceAtLeast(0L)
        val wall = System.currentTimeMillis()
        prefs.parkingSpot = ParkingSpot(
            lat = fix.latitude,
            lon = fix.longitude,
            accuracyM = if (fix.hasAccuracy()) fix.accuracy.toDouble() else Double.NaN,
            fixTimeMs = wall - ageMs,
            savedAtMs = wall
        )
        lastParkingSaveAt = nowElapsed
    }

    private fun bestParkingFix(): Location? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        fun ageMs(l: Location): Long = (nowNanos - l.elapsedRealtimeNanos) / 1_000_000L

        // 1) l'ultima lettura del nostro GPS, se recente
        lastFix?.let { if (ageMs(it) <= PARKING_FRESH_MS) return it }

        // 2) altrimenti la più recente tra le ultime posizioni note del telefono
        if (!BtUtils.hasFineLocation(this)) return null
        var best: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            val l = lastKnown(provider) ?: continue
            if (ageMs(l) > PARKING_LASTKNOWN_MS) continue
            if (l.hasAccuracy() && l.accuracy > PARKING_LASTKNOWN_MAX_ACCURACY_M) continue
            val current = best
            if (current == null || ageMs(l) < ageMs(current)) best = l
        }
        return best
    }

    private fun lastKnown(provider: String): Location? {
        return try {
            locationManager.getLastKnownLocation(provider)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------------------------
    // Limite automatico (dati OpenStreetMap)
    // -------------------------------------------------------------------------------------------

    /** Limite su cui si basa l'avviso: quello della strada se noto, altrimenti quello manuale. */
    private fun baseLimit(): Int {
        return if (prefs.autoLimit && tracker.limitKmh > 0) tracker.limitKmh else prefs.speedLimitKmh
    }

    /** Velocità oltre la quale suona l'avviso: limite + tolleranza. */
    private fun alertThreshold(): Int = baseLimit() + prefs.toleranceKmh

    private fun updateAutoLimit(location: Location, kmh: Float) {
        maybeFetchRoadData(location, kmh)
        val data = roadData ?: return

        val heading: Double? =
            if (location.hasBearing() && kmh >= MIN_HEADING_KMH) location.bearing.toDouble() else null
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0

        val match = SpeedLimitLogic.match(
            data.ways,
            location.latitude,
            location.longitude,
            heading,
            accuracy
        )
        if (tracker.update(match, SystemClock.elapsedRealtime())) onLimitChanged()
    }

    /** Se il limite cambia, un eventuale avviso già dato non vale più: si ricomincia da capo. */
    private fun onLimitChanged() {
        overCount = 0
        overLimit = false
        updateNotification()
    }

    /**
     * Scarica le strade attorno alla posizione solo quando serve: la prima volta e poi quando ci
     * si è allontanati abbastanza dal centro dell'area già scaricata. In mezzo il limite si
     * ricava dai dati in memoria, senza usare la rete.
     */
    private fun maybeFetchRoadData(location: Location, kmh: Float) {
        if (fetchInFlight || destroyed) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextFetchAt) return

        val data = roadData
        val needed = data == null || SpeedLimitLogic.distanceMeters(
            location.latitude,
            location.longitude,
            data.centerLat,
            data.centerLon
        ) > data.radiusM * REFETCH_FRACTION
        if (!needed) return

        val lat = location.latitude
        val lon = location.longitude
        val radius = if (kmh >= FAST_KMH) RADIUS_FAST_M else RADIUS_SLOW_M

        fetchInFlight = true
        try {
            executor.execute {
                val result = try {
                    OverpassClient.fetch(lat, lon, radius)
                } catch (e: Exception) {
                    null
                }
                handler.post { onRoadDataResult(result) }
            }
        } catch (e: Exception) {
            fetchInFlight = false
        }
    }

    private fun onRoadDataResult(result: RoadData?) {
        fetchInFlight = false
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        if (result != null) {
            roadData = result
            dataState = DATA_OK
            nextFetchAt = now + MIN_FETCH_GAP_MS
        } else {
            dataState = DATA_ERROR
            nextFetchAt = now + RETRY_MS
        }
        limitInfo = describeLimit()
    }

    private fun describeLimit(): String {
        if (!prefs.speedEnabled) return ""
        val tolerance = prefs.toleranceKmh
        val manual = prefs.speedLimitKmh

        if (!prefs.autoLimit) {
            return "Limite impostato: $manual km/h · avviso oltre ${manual + tolerance} km/h"
        }

        val detected = tracker.limitKmh
        if (detected > 0) {
            val road = tracker.roadName?.let { " · $it" } ?: ""
            val kind = if (tracker.fromTag) "" else " (stimato dal tipo di strada)"
            return "Limite rilevato: $detected km/h$road$kind · avviso oltre ${detected + tolerance} km/h"
        }

        val fallback = "uso il limite manuale di $manual km/h (avviso oltre ${manual + tolerance})"
        return when {
            roadData == null && dataState == DATA_ERROR ->
                "Dati della strada non raggiungibili (internet?): $fallback"
            roadData == null -> "Cerco i dati della strada…"
            else -> "Limite di questa strada non noto: $fallback"
        }
    }

    private fun evaluateSpeed(kmh: Float) {
        val threshold = alertThreshold()
        if (kmh >= threshold) {
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
                    // un nuovo superamento conta una volta sola, non a ogni ripetizione dell'avviso
                    if (!overLimit) recorder?.noteOverspeed()
                    overLimit = true
                    lastAlarmAt = now
                    player.play(SoundSynth.alarm(), prefs.volumePercent)
                }
            }
        } else {
            overCount = 0
            if (kmh < threshold - HYSTERESIS_KMH) {
                overLimit = false
            }
        }
    }
}
