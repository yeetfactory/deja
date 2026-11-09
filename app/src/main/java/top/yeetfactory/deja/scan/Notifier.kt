package top.yeetfactory.deja.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import top.yeetfactory.deja.MainActivity
import top.yeetfactory.deja.R
import top.yeetfactory.deja.core.Permission
import top.yeetfactory.deja.data.Alert
import top.yeetfactory.deja.data.Delivery
import top.yeetfactory.deja.data.EventType

/** Everything Déjà says through the notification shade. */
class Notifier(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                // A permanent status line is not news, and a badge would sit right on the logo.
                NotificationChannel(CHANNEL_SERVICE, "Listening", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_STATUS, "Scanner status", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun open(
        requestCode: Int,
        configure: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply(configure)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun broadcast(
        requestCode: Int,
        action: String,
        configure: Intent.() -> Unit = {},
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DejaReceiver::class.java).setAction(action).apply(configure),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(
        text: String,
        intent: PendingIntent,
    ) = Notification.Action.Builder(null, text, intent).build()

    fun service(text: String): Notification =
        Notification
            .Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_deja)
            .setContentTitle(text)
            .setContentIntent(open(0))
            .addAction(action("Pause", broadcast(1, DejaReceiver.ACTION_PAUSE)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun updateService(text: String) = manager.notify(ID_SERVICE, service(text))

    /** Resuming goes through the activity: only a visible app may start using location. */
    fun paused() {
        createChannels()
        manager.notify(
            ID_STATUS,
            Notification
                .Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_deja)
                .setContentTitle("Scanning is paused")
                .setContentText("Déjà is not listening until you resume.")
                .setContentIntent(open(2))
                .addAction(action("Resume", open(3) { setAction(MainActivity.ACTION_RESUME) }))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearPaused() = manager.cancel(ID_STATUS)

    fun scanStopped(reason: String) {
        createChannels()
        manager.notify(
            ID_STATUS,
            Notification
                .Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_deja)
                .setContentTitle("Scanning has stopped")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText("$reason. Tap to open Déjà and resume."))
                .setContentIntent(open(4) { setAction(MainActivity.ACTION_RESUME) })
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun deliverable(probe: Probe): Delivery? {
        val system = probe.read()
        return when {
            system.notificationPermission != Permission.GRANTED -> Delivery.BLOCKED_PERMISSION
            !system.notificationsEnabled -> Delivery.BLOCKED_PERMISSION
            !system.alertChannelEnabled -> Delivery.BLOCKED_CHANNEL
            else -> null
        }
    }

    /** Posts the alert if it can reach the user, and reports what actually happened. */
    fun alert(
        alert: Alert,
        probe: Probe,
    ): Delivery {
        deliverable(probe)?.let { return it }
        val name = alert.device.title
        val title =
            when (alert.type) {
                EventType.FOLLOWING -> "$name may be following you"
                EventType.NEW_TRACKER, EventType.NEW_FIND_MY -> "New tracker nearby: $name"
                EventType.RETURNED -> "$name is back"
            }
        val route = listOfNotNull(alert.fromPlace, alert.toPlace).distinct().joinToString(" → ")
        val text = alert.summary.ifEmpty { route.ifEmpty { "Seen again nearby" } }
        val id = ID_ALERT_BASE + alert.eventId.toInt()

        fun extras(intent: Intent) {
            intent.putExtra(DejaReceiver.EXTRA_EVENT, alert.eventId)
            intent.putExtra(DejaReceiver.EXTRA_DEVICE, alert.device.id)
            intent.putExtra(DejaReceiver.EXTRA_NOTIFICATION, id)
        }
        val base = id * 4
        manager.notify(
            id,
            Notification
                .Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_deja)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(base) { putExtra(MainActivity.EXTRA_EVENT, alert.eventId) })
                .addAction(action("This is mine", broadcast(base + 1, DejaReceiver.ACTION_MINE, { extras(this) })))
                .addAction(action("Ignore", broadcast(base + 2, DejaReceiver.ACTION_IGNORE, { extras(this) })))
                .addAction(action("Dismiss", broadcast(base + 3, DejaReceiver.ACTION_DISMISS, { extras(this) })))
                .setAutoCancel(true)
                .build(),
        )
        return Delivery.POSTED
    }

    /** A harmless notification, so the whole path can be checked without waiting for a real alert. */
    fun test(probe: Probe): Delivery {
        createChannels()
        deliverable(probe)?.let { return it }
        manager.notify(
            ID_TEST,
            Notification
                .Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_deja)
                .setContentTitle("Test alert")
                .setContentText("Alerts from Déjà will look like this.")
                .setContentIntent(open(5))
                .setAutoCancel(true)
                .build(),
        )
        return Delivery.POSTED
    }

    fun cancel(id: Int) = manager.cancel(id)

    companion object {
        const val CHANNEL_SERVICE = "listening"
        const val CHANNEL_ALERTS = "pings"
        const val CHANNEL_STATUS = "status"
        const val ID_SERVICE = 1
        private const val ID_STATUS = 2
        private const val ID_TEST = 3
        private const val ID_ALERT_BASE = 1000
    }
}
