package it.marco.cintureauto

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Piccoli strumenti per leggere gli eventi Bluetooth e riconoscere l'auto. */
object BtUtils {

    @Suppress("DEPRECATION")
    fun deviceFrom(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @Suppress("MissingPermission")
    fun nameOf(device: BluetoothDevice): String? {
        return try {
            device.name
        } catch (e: SecurityException) {
            null
        }
    }

    fun hasBluetoothPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasFineLocation(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Vero se il dispositivo è l'auto configurata (per indirizzo salvato oppure per nome). */
    fun matchesCar(prefs: Prefs, name: String?, address: String?): Boolean {
        val savedAddress = prefs.carAddress
        if (savedAddress.isNotEmpty() && address != null &&
            savedAddress.equals(address, ignoreCase = true)
        ) {
            return true
        }
        val target = prefs.carName.trim()
        return target.isNotEmpty() && name != null && name.trim().equals(target, ignoreCase = true)
    }
}
