package top.yeetfactory.deja.scan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the scan loop alive through deep sleep. A plain delay stops counting while the CPU is
 * asleep, so with the screen off scans would quietly stop. An alarm wakes the CPU, a short wake
 * lock covers one burst, and nothing holds the CPU awake in between. In Doze Android may still
 * stretch the gap to several minutes, which is the honest limit of what an app can do.
 */
object ScanWake {
    private const val WAKE_TAG = "deja:scan"

    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var lock: PowerManager.WakeLock? = null

    /** Ends the current wait early. Used by the alarm and by system state changes. */
    fun signal() {
        signals.trySend(Unit)
    }

    @Synchronized
    fun acquire(
        context: Context,
        timeoutMs: Long,
    ) {
        val current =
            lock ?: context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                .apply { setReferenceCounted(false) }
                .also { lock = it }
        current.acquire(timeoutMs)
    }

    @Synchronized
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DejaReceiver::class.java).setAction(DejaReceiver.ACTION_WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    suspend fun sleep(
        context: Context,
        ms: Long,
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent(context)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, intent)
        try {
            withTimeoutOrNull(ms) { signals.receive() }
        } finally {
            alarms.cancel(intent)
        }
    }
}
