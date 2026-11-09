package dev.rishikesh.deja.scan

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.rishikesh.deja.core.Conditions
import dev.rishikesh.deja.core.LocationAccess
import dev.rishikesh.deja.core.Permission
import dev.rishikesh.deja.core.SystemState
import dev.rishikesh.deja.data.SettingsStore

/** Reads what Android currently allows. Called often, because any of it can change at any moment. */
class Probe(
    private val context: Context,
    private val settings: SettingsStore,
) {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun permission(name: String): Permission =
        when {
            granted(name) -> Permission.GRANTED
            settings.isDeniedForever(name) -> Permission.PERMANENTLY_DENIED
            settings.wasAsked(name) -> Permission.DENIED
            else -> Permission.NOT_ASKED
        }

    private fun location(): LocationAccess =
        when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                LocationAccess.PRECISE
            }

            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> {
                LocationAccess.APPROXIMATE
            }

            else -> {
                when (permission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Permission.PERMANENTLY_DENIED -> LocationAccess.PERMANENTLY_DENIED
                    Permission.DENIED -> LocationAccess.DENIED
                    else -> LocationAccess.NOT_ASKED
                }
            }
        }

    fun read(): SystemState {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val wifi = context.getSystemService(WifiManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val channel = notifications.getNotificationChannel(Notifier.CHANNEL_ALERTS)

        // Deprecated along with app-triggered Wi-Fi scans, which Android hasn't replaced.
        @Suppress("DEPRECATION")
        val wifiScanAlways = wifi.isScanAlwaysAvailable
        return SystemState(
            bluetoothPermission = permission(Manifest.permission.BLUETOOTH_SCAN),
            location = location(),
            notificationPermission = permission(Manifest.permission.POST_NOTIFICATIONS),
            bluetoothSupported = adapter != null,
            bluetoothOn = adapter?.isEnabled == true,
            wifiOn = wifi.isWifiEnabled,
            wifiScanAlways = wifiScanAlways,
            locationOn = context.getSystemService(LocationManager::class.java).isLocationEnabled,
            notificationsEnabled = notifications.areNotificationsEnabled(),
            alertChannelEnabled = channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE,
            powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode,
        )
    }

    fun conditions(): Conditions {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return Conditions(
            screenOn = power.isInteractive,
            charging = battery.isCharging,
            powerSave = power.isPowerSaveMode,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        )
    }
}
