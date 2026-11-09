package top.yeetfactory.deja.scan

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A full-speed scan for one address, for as long as it is collected. This is the most expensive
 * scan the app runs, so only an open device page collects it, and nothing here is saved.
 *
 * It matches in code rather than with a hardware address filter, because that filter assumes a
 * public address and would silently miss the random ones most devices use.
 */
fun watchSignal(
    context: Context,
    address: String,
): Flow<WatchEvent> =
    callbackFlow {
        val scanner =
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.takeIf { it.isEnabled }
                ?.bluetoothLeScanner
        if (scanner == null) {
            trySend(WatchEvent.Failed("Bluetooth is not ready"))
            close()
            return@callbackFlow
        }
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    if (result.device.address == address) trySend(WatchEvent.Heard(result.rssi))
                }

                override fun onScanFailed(errorCode: Int) {
                    trySend(WatchEvent.Failed(scanFailureText(errorCode)))
                }
            }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            trySend(WatchEvent.Failed("Bluetooth permission was removed"))
        } catch (e: IllegalStateException) {
            trySend(WatchEvent.Failed("Bluetooth turned off"))
        }
        awaitClose { scanner.stopQuietly(callback) }
    }
