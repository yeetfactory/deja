package top.yeetfactory.deja.scan

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.wifi.WifiManager
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.util.size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import top.yeetfactory.deja.BuildConfig
import top.yeetfactory.deja.core.AddressKind
import top.yeetfactory.deja.core.Advert
import top.yeetfactory.deja.core.BleClassifier
import top.yeetfactory.deja.core.BleMode
import top.yeetfactory.deja.core.GeoFix
import top.yeetfactory.deja.core.Merge
import top.yeetfactory.deja.core.PlaceClusterer
import top.yeetfactory.deja.core.ScanPlan
import top.yeetfactory.deja.data.Observation
import top.yeetfactory.deja.data.Radio
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface RadioResult {
    /** Not attempted, because it is off or not due. Says nothing about whether it works. */
    data object Skipped : RadioResult

    data class Ok(
        val heard: Int,
    ) : RadioResult

    data class Failed(
        val reason: String,
    ) : RadioResult
}

data class BurstRequest(
    val plan: ScanPlan,
    val bluetooth: Boolean,
    val wifi: Boolean,
)

data class Burst(
    val observations: List<Observation>,
    val bluetooth: RadioResult,
    val wifi: RadioResult,
)

/** A source of what is around us. Real radios in production, a fake town on the emulator. */
interface Source {
    val simulated: Boolean

    fun start()

    fun stop()

    suspend fun burst(request: BurstRequest): Burst

    fun fix(): GeoFix?

    fun now(): Long

    /** Location was switched on or off, so any registration may need redoing. */
    fun locationChanged() = Unit
}

fun scanFailureText(code: Int) =
    when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A scan was already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register the scan"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "The Bluetooth stack reported an internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support this kind of scan"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "The Bluetooth radio is out of scan slots"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Android limited scanning because it ran too often"
        else -> "Bluetooth scan failed with code $code"
    }

/**
 * Stops a scan that may already be over. With the permission removed or Bluetooth off, Android
 * has stopped it already, and there is nothing left to stop.
 */
fun BluetoothLeScanner.stopQuietly(callback: ScanCallback) {
    try {
        stopScan(callback)
    } catch (e: SecurityException) {
        // The permission was removed, which ended the scan.
    } catch (e: IllegalStateException) {
        // Bluetooth is off, which ended the scan.
    }
}

class RadioSource(
    private val context: Context,
) : Source {
    override val simulated = false

    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    @Volatile private var latest: Location? = null

    @Volatile private var activeScan: Pair<BluetoothLeScanner, ScanCallback>? = null
    private var lastWifiRequest = 0L
    private val listener = LocationListener { location -> latest = location }

    override fun now() = System.currentTimeMillis()

    override fun start() = registerLocation()

    /** Safe to call from anywhere: it stops any scan still running, however the burst ended. */
    override fun stop() {
        stopActiveScan()
        runCatching { locations.removeUpdates(listener) }
    }

    override fun locationChanged() = registerLocation()

    private fun provider(): String? =
        listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { locations.hasProvider(it) && locations.isProviderEnabled(it) }

    private fun registerLocation() {
        try {
            locations.removeUpdates(listener)
            val provider = provider() ?: return
            locations.getLastKnownLocation(provider)?.let { latest = it }
            val request =
                LocationRequest
                    .Builder(LOCATION_INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateDistanceMeters(LOCATION_MIN_METERS)
                    .build()
            locations.requestLocationUpdates(provider, request, context.mainExecutor, listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location not available", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Location provider went away", e)
        }
    }

    private fun ageMs(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    /** Updates arrive only on movement, so a phone sitting still has to ask. */
    private fun refreshFix() {
        val current = latest
        if (current != null && ageMs(current) < REFRESH_FIX_AFTER_MS) return
        val provider = provider() ?: return
        try {
            locations.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                if (location != null) latest = location
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location not available", e)
        }
    }

    override fun fix(): GeoFix? {
        val location = latest ?: return null
        if (ageMs(location) > MAX_FIX_AGE_MS || !location.hasAccuracy()) return null
        if (location.accuracy > PlaceClusterer.MAX_USABLE_ACCURACY) return null
        return GeoFix(location.latitude, location.longitude, location.accuracy)
    }

    override suspend fun burst(request: BurstRequest): Burst =
        coroutineScope {
            refreshFix()
            val heard = ConcurrentHashMap<String, Observation>()
            val wifiScan = async { if (request.wifi) scanWifi(heard) else RadioResult.Skipped }
            val ble = if (request.bluetooth) scanBluetooth(request.plan, heard) else RadioResult.Skipped
            val wifiResult = wifiScan.await()
            Log.i(TAG, "burst ble=$ble wifi=$wifiResult heard=${heard.size} screenOn=${power.isInteractive}")
            Burst(heard.values.toList(), ble, wifiResult)
        }

    private fun scanMode(mode: BleMode) =
        when (mode) {
            BleMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
            BleMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
            BleMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
        }

    private suspend fun scanBluetooth(
        plan: ScanPlan,
        heard: MutableMap<String, Observation>,
    ): RadioResult {
        val adapter = bluetooth.adapter ?: return RadioResult.Failed("No Bluetooth adapter")
        if (!adapter.isEnabled) return RadioResult.Skipped
        val scanner = adapter.bluetoothLeScanner ?: return RadioResult.Failed("Bluetooth is not ready")

        val failed = CompletableDeferred<Int>()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) = record(result, heard)

                override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { record(it, heard) }

                override fun onScanFailed(errorCode: Int) {
                    failed.complete(errorCode)
                }
            }
        val settings = ScanSettings.Builder().setScanMode(scanMode(plan.mode)).build()
        var filtered = plan.filtered || !power.isInteractive

        fun begin() = scanner.startScan(if (filtered) screenOffFilters() else null, settings, callback)

        try {
            begin()
            activeScan = scanner to callback
            val deadline = SystemClock.elapsedRealtime() + plan.burstMs
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(minOf(left, POLL_MS))
                if (failed.isCompleted) return RadioResult.Failed(scanFailureText(failed.await()))
                if (!adapter.isEnabled) return RadioResult.Failed("Bluetooth turned off during a scan")
                // An unfiltered scan goes silent when the screen turns off, so swap it, once.
                if (!filtered && !power.isInteractive) {
                    filtered = true
                    scanner.stopScan(callback)
                    begin()
                }
            }
            return RadioResult.Ok(heard.count { it.value.radio == Radio.BLE })
        } catch (e: SecurityException) {
            return RadioResult.Failed("Bluetooth permission was removed")
        } catch (e: IllegalStateException) {
            return RadioResult.Failed("Bluetooth turned off during a scan")
        } finally {
            // Runs on cancellation too, so a pause can never leave the radio scanning.
            stopActiveScan()
        }
    }

    private fun stopActiveScan() {
        val (scanner, callback) = activeScan ?: return
        activeScan = null
        scanner.stopQuietly(callback)
    }

    private fun record(
        result: ScanResult,
        heard: MutableMap<String, Observation>,
    ) {
        val record = result.scanRecord
        val manufacturer =
            buildMap {
                val data = record?.manufacturerSpecificData ?: return@buildMap
                for (i in 0 until data.size) put(data.keyAt(i), data.valueAt(i))
            }
        val serviceData =
            record
                ?.serviceData
                .orEmpty()
                .entries
                .mapNotNull { (uuid, data) -> shortUuid(uuid.uuid)?.let { it to data } }
                .toMap()
        val address = result.device.address
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
        val classification =
            BleClassifier.classify(
                Advert(
                    address = address,
                    kind =
                        when (result.device.addressType) {
                            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> AddressKind.PUBLIC
                            BluetoothDevice.ADDRESS_TYPE_RANDOM -> AddressKind.RANDOM
                            else -> AddressKind.UNKNOWN
                        },
                    name = name,
                    manufacturer = manufacturer,
                    services =
                        record
                            ?.serviceUuids
                            .orEmpty()
                            .mapNotNull { shortUuid(it.uuid) }
                            .toSet(),
                    serviceData = serviceData,
                ),
            )
        val key = "ble:$address"
        val observation = Merge.observation(key, Radio.BLE, name, result.rssi, null, classification)
        if (BuildConfig.DEBUG && key !in heard) {
            Log.d(
                TAG,
                "heard ${address.takeLast(5)} ${classification.fingerprint} -> ${classification.category}/${classification.typeConfidence}",
            )
        }
        heard.merge(key, observation, Merge::packets)
    }

    private suspend fun scanWifi(heard: MutableMap<String, Observation>): RadioResult {
        // Deprecated along with startScan below, which Android hasn't replaced.
        @Suppress("DEPRECATION")
        if (!wifi.isWifiEnabled && !wifi.isScanAlwaysAvailable) return RadioResult.Skipped
        val requested = SystemClock.elapsedRealtime()
        // Android allows four scans in two minutes. Asking sooner would only burn one of them.
        if (requested - lastWifiRequest < WIFI_MIN_GAP_MS) return RadioResult.Skipped
        lastWifiRequest = requested

        val finished = CompletableDeferred<Boolean>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    finished.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        try {
            @Suppress("DEPRECATION")
            if (!wifi.startScan()) return RadioResult.Failed("Android limited Wi-Fi scanning")
            val updated =
                withTimeoutOrNull(WIFI_TIMEOUT_MS) { finished.await() }
                    ?: return RadioResult.Failed("The Wi-Fi scan did not finish")
            if (!updated) return RadioResult.Failed("Android reported the Wi-Fi scan failed")

            // Only what this scan heard. The list can still hold older entries from before it.
            val cutoffMicros = requested * 1000
            var count = 0
            for (result in wifi.scanResults) {
                if (result.timestamp < cutoffMicros) continue
                val ssid =
                    result.wifiSsid
                        ?.toString()
                        ?.trim('"')
                        ?.ifBlank { null }
                val key = "wifi:${result.BSSID}"
                val classification = BleClassifier.classifyWifi(result.BSSID, ssid)
                heard[key] = Merge.observation(key, Radio.WIFI, ssid, result.level, null, classification)
                count++
            }
            return RadioResult.Ok(count)
        } catch (e: SecurityException) {
            return RadioResult.Failed("Location permission was removed")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun shortUuid(uuid: UUID): Int? {
        val isBase =
            uuid.leastSignificantBits == BLUETOOTH_BASE_LSB &&
                (uuid.mostSignificantBits and 0xFFFFFFFFL) == 0x00001000L
        return if (isBase) (uuid.mostSignificantBits ushr 32).toInt() else null
    }

    private fun screenOffFilters(): List<ScanFilter> =
        buildList {
            for (company in SCREEN_OFF_COMPANIES) {
                add(ScanFilter.Builder().setManufacturerData(company, ByteArray(0)).build())
            }
            for (service in SCREEN_OFF_SERVICES) {
                val uuid = UUID(service.toLong() shl 32 or 0x1000L, BLUETOOTH_BASE_LSB)
                add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
                add(ScanFilter.Builder().setServiceData(ParcelUuid(uuid), ByteArray(0)).build())
            }
        }

    private companion object {
        const val TAG = "DejaScan"
        const val POLL_MS = 1_000L
        const val WIFI_MIN_GAP_MS = 31_000L
        const val WIFI_TIMEOUT_MS = 15_000L
        const val MAX_FIX_AGE_MS = 5 * 60_000L
        const val REFRESH_FIX_AFTER_MS = 2 * 60_000L
        const val LOCATION_INTERVAL_MS = 60_000L
        const val LOCATION_MIN_METERS = 25f
        const val BLUETOOTH_BASE_LSB = -0x7FFFFF7FA064CB05L

        val SCREEN_OFF_COMPANIES = listOf(0x004C, 0x00E0, 0x0075, 0x0006, 0x0087, 0x009E, 0x012D, 0x0171, 0x038F)
        val SCREEN_OFF_SERVICES = listOf(0xFEED, 0xFEEC, 0xFE33, 0xFA25, 0xFD5A, 0xFEAA, 0x180D, 0x184E)
    }
}
