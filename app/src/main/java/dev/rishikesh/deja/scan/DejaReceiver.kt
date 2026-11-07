package dev.rishikesh.deja.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rishikesh.deja.DejaApp
import dev.rishikesh.deja.core.Health
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles notification buttons, the wake alarm, and the phone restarting. */
class DejaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DejaApp
        when (intent.action) {
            ACTION_WAKE -> {
                // Held from here so the CPU cannot sleep again before the loop picks this up.
                ScanWake.acquire(context, WAKE_HANDOFF_MS)
                ScanWake.signal()
            }
            ACTION_PAUSE -> ScanController.pause(context)
            ACTION_MINE, ACTION_IGNORE, ACTION_DISMISS -> settle(context, app, intent)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Android forbids starting location work from here, so all we can do is say so.
                val settings = app.settings.settings.value
                val system = Probe(context, app.settings).read()
                if (settings.scanningEnabled && settings.onboarded && Health.canRunService(system)) {
                    val reason = "The phone restarted or Déjà was updated"
                    app.settings.journal { it.copy(serviceError = reason) }
                    Notifier(context).scanStopped(reason)
                }
            }
        }
    }

    private fun settle(context: Context, app: DejaApp, intent: Intent) {
        val event = intent.getLongExtra(EXTRA_EVENT, 0)
        val device = intent.getLongExtra(EXTRA_DEVICE, 0)
        val notification = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_MINE -> app.repository.markMine(device)
                    ACTION_IGNORE -> app.repository.markIgnored(device)
                    else -> app.repository.dao.dismissEvent(event)
                }
                Notifier(context).cancel(notification)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE = "dev.rishikesh.deja.WAKE"
        const val ACTION_PAUSE = "dev.rishikesh.deja.PAUSE"
        const val ACTION_MINE = "dev.rishikesh.deja.MINE"
        const val ACTION_IGNORE = "dev.rishikesh.deja.IGNORE"
        const val ACTION_DISMISS = "dev.rishikesh.deja.DISMISS"
        const val EXTRA_EVENT = "event"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_NOTIFICATION = "notification"
        private const val WAKE_HANDOFF_MS = 10_000L
    }
}
