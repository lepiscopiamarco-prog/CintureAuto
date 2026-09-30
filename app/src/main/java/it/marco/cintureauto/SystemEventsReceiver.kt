package it.marco.cintureauto

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Riceve gli eventi di sistema anche quando l'app non è in esecuzione:
 *  - avvio del telefono / aggiornamento dell'app: riavvia il servizio
 *  - connessione all'auto mentre il servizio non è attivo: lo riattiva
 */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.serviceEnabled) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                CarAlertService.start(context)
            }

            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // Se il servizio è già vivo se ne occupa lui: qui serve solo a "risvegliarlo".
                if (CarAlertService.instance != null) return
                if (!CarAlertService.canStart(context)) return
                val device = BtUtils.deviceFrom(intent) ?: return
                val name = BtUtils.nameOf(device)
                if (!BtUtils.matchesCar(prefs, name, device.address)) return

                val i = Intent(context, CarAlertService::class.java)
                    .setAction(CarAlertService.ACTION_BT_EVENT)
                    .putExtra(
                        CarAlertService.EXTRA_CONNECTED,
                        intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    )
                    .putExtra(CarAlertService.EXTRA_NAME, name)
                    .putExtra(CarAlertService.EXTRA_ADDRESS, device.address)
                try {
                    context.startForegroundService(i)
                } catch (e: Exception) {
                    // Android non permette l'avvio da qui in questo momento
                }
            }
        }
    }
}
