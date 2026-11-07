package dev.rishikesh.deja.scan

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.rishikesh.deja.DejaApp
import dev.rishikesh.deja.core.GeoFix
import dev.rishikesh.deja.core.Health
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.core.Permission
import dev.rishikesh.deja.core.ScanPolicy
import dev.rishikesh.deja.core.SystemState
import dev.rishikesh.deja.data.AppSettings
import dev.rishikesh.deja.data.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps listening with the screen off, in short bursts to spare the battery. */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as DejaApp
    private val notifier by lazy { Notifier(this) }
    private val probe by lazy { Probe(this, app.settings) }
    private var source: Source? = null
    private var running = false
    private var notificationText = ""
    private var lastBurstEnded = 0L

    /** System changes end the wait at once, so recovery does not have to sit out a full pause. */
    private val systemChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val before = LiveState.system.value
            val after = probe.read()
            LiveState.setSystem(after)
            if (intent.action == LocationManager.MODE_CHANGED_ACTION) source?.locationChanged()

            val settings = app.settings.settings.value
            val recovered = !ready(before, settings) && ready(after, settings)
            val lookingAtIt = intent.action == Intent.ACTION_SCREEN_ON &&
                SystemClock.elapsedRealtime() - lastBurstEnded > MIN_SCREEN_ON_GAP_MS
            if (recovered || lookingAtIt) ScanWake.signal()
            refreshNotification(after, settings)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        notifier.createChannels()

        val system = probe.read()
        LiveState.setSystem(system)
        if (!Health.canRunService(system)) {
            return giveUp("Location permission was removed", promoteFirst = true)
        }
        if (!promote()) return START_NOT_STICKY
        if (!app.settings.settings.value.scanningEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        app.settings.journal { it.copy(serviceError = null) }
        registerReceiver(
            systemChanges,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        val active = if (SimulatedSource.isAvailable) SimulatedSource() else RadioSource(this)
        source = active
        active.start()
        LiveState.setListening(active.simulated)
        scope.launch { listen(active) }
        // A changed setting should apply now, not after the current pause.
        scope.launch { app.settings.settings.drop(1).collect { ScanWake.signal() } }
        // Android drops a foreground notification posted before it's allowed, and never shows it
        // later. On first run that's the normal order, so post it again once it can be seen.
        scope.launch {
            LiveState.system
                .map { it.notificationPermission == Permission.GRANTED && it.notificationsEnabled }
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .collect { notifier.updateService(notificationText.ifEmpty { LISTENING }) }
        }
        return START_STICKY
    }

    /**
     * Android refuses location work from the background, which is exactly the situation after the
     * system restarts a killed service. That must end visibly, never in a crash or a silent stop.
     */
    private fun promote(): Boolean {
        val notification = notifier.service(LISTENING)
        return try {
            startForeground(
                Notifier.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Restart refused", e)
            giveUp(RESTART_REFUSED, promoteFirst = false)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Restart refused", e)
            giveUp(RESTART_REFUSED, promoteFirst = true)
            false
        }
    }

    private fun giveUp(reason: String, promoteFirst: Boolean): Int {
        if (promoteFirst) {
            // startForegroundService() insists on a foreground call, even just to stop again.
            runCatching {
                startForeground(Notifier.ID_SERVICE, notifier.service(LISTENING), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        }
        app.settings.journal { it.copy(serviceError = reason) }
        notifier.scanStopped(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun ready(system: SystemState, settings: AppSettings) =
        Health.readiness(system, settings.bluetoothEnabled, settings.wifiEnabled)

    private fun blockedText(system: SystemState, settings: AppSettings): String? = when {
        ready(system, settings) -> null
        !system.locationOn -> "Paused: Location is off"
        settings.bluetoothEnabled && !system.bluetoothOn -> "Paused: Bluetooth is off"
        else -> "Paused: nothing can scan right now"
    }

    private fun refreshNotification(system: SystemState, settings: AppSettings) {
        val text = blockedText(system, settings) ?: LISTENING
        if (text == notificationText) return
        notificationText = text
        notifier.updateService(text)
    }

    private suspend fun listen(active: Source) {
        val repository = app.repository
        val store = app.settings
        var bursts = 0
        var failures = 0
        var lastPrune = 0L

        while (scope.isActive) {
            val settings = store.settings.value
            val system = probe.read()
            LiveState.setSystem(system)
            refreshNotification(system, settings)
            val plan = ScanPolicy.plan(settings.cadence, probe.conditions())
            LiveState.setPlan(plan)
            var pause = plan.pauseMs

            if (ready(system, settings)) {
                ScanWake.acquire(this, plan.burstMs + WAKE_MARGIN_MS)
                try {
                    val failed = runBurst(active, plan, settings, system, bursts++)
                    failures = if (failed) failures + 1 else 0
                    pause = ScanPolicy.backoff(pause, failures)

                    val now = active.now()
                    if (now - lastPrune > MatchEngine.DAY) {
                        repository.prune(now, settings.retention)
                        lastPrune = now
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Scan burst failed", e)
                    failures++
                    pause = ScanPolicy.backoff(pause, failures)
                    store.journal {
                        it.copy(
                            bluetoothFailure = "Could not save scan results",
                            failedBursts = it.failedBursts + 1,
                            failureAt = System.currentTimeMillis(),
                        )
                    }
                } finally {
                    ScanWake.release()
                }
            } else {
                pause = ScanPolicy.IDLE_PAUSE_MS
            }
            lastBurstEnded = SystemClock.elapsedRealtime()
            LiveState.expire(System.currentTimeMillis())
            ScanWake.sleep(this, pause)
        }
    }

    /** Runs one burst and records what happened. Returns true when no radio managed to scan. */
    private suspend fun runBurst(
        active: Source,
        plan: dev.rishikesh.deja.core.ScanPlan,
        settings: AppSettings,
        system: SystemState,
        index: Int,
    ): Boolean {
        val store = app.settings
        val repository = app.repository
        val burst = active.burst(
            BurstRequest(
                plan = plan,
                bluetooth = settings.bluetoothEnabled && system.bluetoothOn &&
                    system.bluetoothPermission == Permission.GRANTED,
                wifi = settings.wifiEnabled && index % plan.wifiEvery == 0,
            ),
        )
        val now = active.now()
        val wallClock = System.currentTimeMillis()
        val fix: GeoFix? = active.fix()
        val place = fix?.let { repository.resolvePlace(it) }
        val result = repository.ingest(burst.observations, place, fix?.accuracy, now, settings.alerts)

        val rssi = burst.observations.associate { it.key to it.rssi }
        val returning = result.returningIds
        LiveState.publish(
            result.seen.filterNot { it.ignored }.map { device -> blip(device, rssi[device.key], device.id in returning, wallClock) },
            place?.id,
            place?.name,
            wallClock,
        )

        result.alerts.forEach { alert ->
            repository.dao.setDelivery(alert.eventId, notifier.alert(alert, probe))
        }

        val bleFailed = burst.bluetooth as? RadioResult.Failed
        val wifiFailed = burst.wifi as? RadioResult.Failed
        val anyOk = burst.bluetooth is RadioResult.Ok || burst.wifi is RadioResult.Ok
        val failed = !anyOk && (bleFailed != null || wifiFailed != null)
        store.journal { journal ->
            journal.copy(
                lastBurstAt = wallClock,
                lastBluetoothOk = if (burst.bluetooth is RadioResult.Ok) wallClock else journal.lastBluetoothOk,
                lastWifiOk = if (burst.wifi is RadioResult.Ok) wallClock else journal.lastWifiOk,
                lastFixAt = if (fix != null) wallClock else journal.lastFixAt,
                lastFixAccuracy = fix?.accuracy ?: journal.lastFixAccuracy,
                bluetoothFailure = bleFailed?.reason,
                // A skipped Wi-Fi turn proves nothing, so an earlier failure stands until a real scan.
                wifiFailure = if (burst.wifi is RadioResult.Skipped) journal.wifiFailure else wifiFailed?.reason,
                failedBursts = if (failed) journal.failedBursts + 1 else journal.failedBursts,
                failureAt = if (bleFailed != null || wifiFailed != null) wallClock else journal.failureAt,
            )
        }
        return failed
    }

    private fun blip(device: Device, rssi: Int?, returning: Boolean, heardAt: Long) = Blip(
        deviceId = device.id,
        key = device.key,
        title = device.title,
        subtitle = device.descriptor?.takeIf { it != device.title } ?: device.vendor.orEmpty(),
        category = device.category,
        rssi = rssi ?: device.lastRssi,
        starred = device.starred,
        owned = device.owned,
        returning = returning,
        temporary = device.temporary,
        heardAt = heardAt,
    )

    override fun onDestroy() {
        if (running) {
            source?.stop()
            unregisterReceiver(systemChanges)
        }
        scope.cancel()
        ScanWake.release()
        LiveState.setStopped()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DejaService"
        const val LISTENING = "Listening for nearby devices"
        const val RESTART_REFUSED = "Android would not let scanning restart in the background"
        const val WAKE_MARGIN_MS = 20_000L
        const val MIN_SCREEN_ON_GAP_MS = 15_000L
    }
}
