package dev.rishikesh.deja.scan

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rishikesh.deja.DejaApp
import dev.rishikesh.deja.core.Health

/** The only way scanning is started or stopped, so the user's choice is always respected. */
object ScanController {
    private const val TAG = "DejaScan"

    private fun app(context: Context) = context.applicationContext as DejaApp

    /** Starts scanning if the user wants it and Android allows it. Safe to call repeatedly. */
    fun ensureRunning(context: Context) {
        val app = app(context)
        if (!app.settings.settings.value.scanningEnabled) return
        val system = Probe(context, app.settings).read()
        LiveState.setSystem(system)
        if (!Health.canRunService(system)) return
        try {
            context.startForegroundService(Intent(context, ScanService::class.java))
            app.settings.journal { it.copy(serviceError = null) }
            // Whatever said scanning had stopped is no longer true.
            Notifier(context).clearPaused()
        } catch (e: ForegroundServiceStartNotAllowedException) {
            reportStartFailure(context, e)
        } catch (e: SecurityException) {
            reportStartFailure(context, e)
        }
    }

    private fun reportStartFailure(context: Context, e: Exception) {
        Log.w(TAG, "Could not start scanning", e)
        val reason = "Android would not let scanning start in the background"
        app(context).settings.journal { it.copy(serviceError = reason) }
        Notifier(context).scanStopped(reason)
    }

    fun resume(context: Context) {
        app(context).settings.update { it.copy(scanningEnabled = true) }
        Notifier(context).clearPaused()
        ensureRunning(context)
    }

    fun pause(context: Context, showResume: Boolean = true) {
        app(context).settings.update { it.copy(scanningEnabled = false) }
        context.stopService(Intent(context, ScanService::class.java))
        if (showResume) Notifier(context).paused()
    }
}
