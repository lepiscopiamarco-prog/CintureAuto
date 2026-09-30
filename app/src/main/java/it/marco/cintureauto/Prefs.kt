package it.marco.cintureauto

import android.content.Context
import android.content.SharedPreferences

/**
 * Tutte le impostazioni dell'app, salvate sul telefono.
 * Il servizio legge i valori "dal vivo", quindi ogni modifica ha effetto subito.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("cinture_auto_prefs", Context.MODE_PRIVATE)

    /** Interruttore principale: se attivo, il servizio parte anche all'accensione del telefono. */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("serviceEnabled", false)
        set(v) { sp.edit().putBoolean("serviceEnabled", v).apply() }

    var firstRunDone: Boolean
        get() = sp.getBoolean("firstRunDone", false)
        set(v) { sp.edit().putBoolean("firstRunDone", v).apply() }

    /** Nome Bluetooth dell'auto. */
    var carName: String
        get() = sp.getString("carName", DEFAULT_CAR_NAME) ?: DEFAULT_CAR_NAME
        set(v) { sp.edit().putString("carName", v.trim()).apply() }

    /** Indirizzo (MAC) dell'auto, se scelta dall'elenco dei dispositivi associati. */
    var carAddress: String
        get() = sp.getString("carAddress", "") ?: ""
        set(v) { sp.edit().putString("carAddress", v).apply() }

    var chimeEnabled: Boolean
        get() = sp.getBoolean("chimeEnabled", true)
        set(v) { sp.edit().putBoolean("chimeEnabled", v).apply() }

    /** 0 = ding singolo, 1 = ding-dong, 2 = doppio ding */
    var chimeStyle: Int
        get() = sp.getInt("chimeStyle", 0)
        set(v) { sp.edit().putInt("chimeStyle", v).apply() }

    /** Secondi di attesa (dopo che l'audio Bluetooth dell'auto è pronto) prima del suono. */
    var chimeDelaySec: Int
        get() = sp.getInt("chimeDelaySec", 2)
        set(v) { sp.edit().putInt("chimeDelaySec", v).apply() }

    var speedEnabled: Boolean
        get() = sp.getBoolean("speedEnabled", true)
        set(v) { sp.edit().putBoolean("speedEnabled", v).apply() }

    var speedLimitKmh: Int
        get() = sp.getInt("speedLimitKmh", 130)
        set(v) { sp.edit().putInt("speedLimitKmh", v).apply() }

    /** Ogni quanti secondi ripetere l'avviso mentre si resta sopra il limite (0 = una sola volta). */
    var repeatSec: Int
        get() = sp.getInt("repeatSec", 30)
        set(v) { sp.edit().putInt("repeatSec", v).apply() }

    /** Se vero il GPS lavora solo quando l'auto è connessa (consuma meno batteria). */
    var gpsOnlyWithCar: Boolean
        get() = sp.getBoolean("gpsOnlyWithCar", true)
        set(v) { sp.edit().putBoolean("gpsOnlyWithCar", v).apply() }

    var volumePercent: Int
        get() = sp.getInt("volumePercent", 100)
        set(v) { sp.edit().putInt("volumePercent", v).apply() }

    companion object {
        const val DEFAULT_CAR_NAME = "MB Bluetooth"
    }
}
