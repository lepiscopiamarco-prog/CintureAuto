package it.marco.cintureauto

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Notifica meteo preventiva: mentre l'auto è parcheggiata il telefono controlla ogni 2 ore
 * la previsione nel punto di parcheggio e, se sono previste grandine o gelata notturna,
 * manda una notifica urgente. Nessun server: è il telefono stesso a chiedere la previsione.
 */

/** Esegue un controllo: scarica la previsione, valuta il rischio, avvisa e pianifica il prossimo. */
object WeatherCheck {

    const val CHECK_EVERY_MS = 2L * 3_600_000L
    private const val RETRY_MS = 30L * 60_000L
    private const val SAME_ALERT_SILENCE_MS = 12L * 3_600_000L

    /** Fa rete: va chiamato da un thread in background. Restituisce il testo dell'esito. */
    fun run(context: Context, reschedule: Boolean = true): String {
        val prefs = Prefs(context)
        if (!prefs.weatherAlertEnabled) return "Notifica meteo disattivata."
        val spot = prefs.parkingSpot ?: return "Nessuna posizione di parcheggio salvata."
        if (!prefs.carParked) return "L'auto non risulta parcheggiata (è collegata o la posizione è stata cancellata)."

        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(now))
        val data = download(WeatherLogic.buildUrl(spot.lat, spot.lon))?.let { WeatherLogic.parse(it) }

        if (data == null) {
            val status = "Controllo delle $time: previsione non raggiungibile (internet?). Riprovo tra 30 minuti."
            prefs.weatherStatus = status
            if (reschedule) WeatherScheduler.schedule(context, RETRY_MS)
            return status
        }

        val risk = WeatherLogic.evaluate(data, now)
        if (risk.any) {
            val repeated = risk.key == prefs.weatherLastKey && now - prefs.weatherLastAt < SAME_ALERT_SILENCE_MS
            if (!repeated) {
                WeatherNotifier.show(context, risk, spot)
                prefs.weatherLastKey = risk.key
                prefs.weatherLastAt = now
            }
        }
        val status = if (risk.any) {
            "Controllo delle $time: " + WeatherLogic.message(risk).second
        } else {
            "Controllo delle $time: nessuna grandine o gelata prevista nelle prossime 18 ore."
        }
        prefs.weatherStatus = status
        if (reschedule) WeatherScheduler.schedule(context, CHECK_EVERY_MS)
        return status
    }

    /** Scarica un testo da internet (null se non risponde). Da un thread in background. */
    fun download(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MyMercedes/1.3 (app personale Android)")
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** Sveglie di sistema per i controlli periodici (funzionano anche a telefono bloccato). */
object WeatherScheduler {

    private const val REQUEST_CODE = 7301

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WeatherReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun schedule(context: Context, delayMs: Long) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent(context)
            )
        } catch (e: Exception) {
            // ignora: al prossimo parcheggio si riprova
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        } catch (e: Exception) {
            // ignora
        }
    }
}

/** Riceve la sveglia e fa il controllo in background. */
class WeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyMercedes:meteo")
        wl.acquire(40_000L)
        Thread {
            try {
                WeatherCheck.run(appContext)
            } catch (e: Exception) {
                // ignora
            } finally {
                try {
                    if (wl.isHeld) wl.release()
                } catch (e: Exception) {
                    // ignora
                }
                pending.finish()
            }
        }.start()
    }
}

/** La notifica urgente (con suono e vibrazione, compare in alto sullo schermo). */
object WeatherNotifier {

    private const val CHANNEL_ID = "meteo_auto"
    private const val NOTIFICATION_ID = 2001

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Allerta meteo per l'auto",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Grandine o gelata prevista dove è parcheggiata l'auto"
        channel.enableVibration(true)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, risk: WeatherRisk, spot: ParkingSpot) {
        try {
            ensureChannel(context)
            val (title, text) = WeatherLogic.message(risk)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val map = PendingIntent.getActivity(
                context, 1,
                Intent(Intent.ACTION_VIEW, Uri.parse(MapLinks.geoUri(spot.lat, spot.lon, "Auto parcheggiata"))),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat_belt),
                "Mostra l'auto",
                map
            ).build()
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_belt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(action)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // permesso notifiche negato o altro: niente da fare
        }
    }

    fun dismiss(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // ignora
        }
    }
}
