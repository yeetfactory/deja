package dev.rishikesh.deja.core

enum class Permission { GRANTED, NOT_ASKED, DENIED, PERMANENTLY_DENIED }

enum class LocationAccess { PRECISE, APPROXIMATE, NOT_ASKED, DENIED, PERMANENTLY_DENIED }

enum class Level { OK, WARNING, BLOCKED, OFF }

enum class Recovery(
    val label: String,
) {
    GRANT_SCANNING("Allow"),
    GRANT_NOTIFICATIONS("Allow"),
    APP_SETTINGS("Open app settings"),
    ENABLE_BLUETOOTH("Turn on Bluetooth"),
    WIFI_SETTINGS("Open Wi-Fi settings"),
    LOCATION_SETTINGS("Turn on Location"),
    NOTIFICATION_SETTINGS("Open notification settings"),
    ALERT_CHANNEL_SETTINGS("Open alert settings"),
    DEJA_SETTINGS("Open settings"),
    RESUME("Resume scanning"),
}

/** What Android currently allows. Read fresh, never stored, since it can change in system Settings. */
data class SystemState(
    val bluetoothPermission: Permission = Permission.NOT_ASKED,
    val location: LocationAccess = LocationAccess.NOT_ASKED,
    val notificationPermission: Permission = Permission.NOT_ASKED,
    val bluetoothSupported: Boolean = true,
    val bluetoothOn: Boolean = false,
    val wifiOn: Boolean = false,
    val wifiScanAlways: Boolean = false,
    val locationOn: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val alertChannelEnabled: Boolean = true,
    val powerSave: Boolean = false,
)

/** The record of what scanning has done. Survives restarts, so failures stay visible afterwards. */
data class ScanJournal(
    val lastBluetoothOk: Long = 0,
    val lastWifiOk: Long = 0,
    val lastFixAt: Long = 0,
    val lastFixAccuracy: Float = 0f,
    val lastBurstAt: Long = 0,
    val bluetoothFailure: String? = null,
    val wifiFailure: String? = null,
    val failedBursts: Int = 0,
    val failureAt: Long = 0,
    /** Why the service stopped against the user's wishes, or null when it did not. */
    val serviceError: String? = null,
)

data class Capability(
    val name: String,
    val level: Level,
    val summary: String,
    val detail: String,
    val recovery: Recovery? = null,
) {
    /** "Bluetooth: Working", without repeating itself as "Alerts: Alerts disabled". */
    val headline: String get() = if (summary.startsWith(name)) summary else "$name: $summary"
}

data class HealthReport(
    val bluetooth: Capability,
    val wifi: Capability,
    val location: Capability,
    val notifications: Capability,
    val canScan: Boolean,
    val stale: Boolean,
) {
    val all: List<Capability> get() = listOf(bluetooth, wifi, location, notifications)

    /** The one thing most worth fixing first. Bluetooth is listed before its Location dependency. */
    val worst: Capability? get() =
        listOf(location, bluetooth, wifi, notifications)
            .firstOrNull { it.level == Level.BLOCKED }
            ?: listOf(location, bluetooth, wifi, notifications).firstOrNull { it.level == Level.WARNING }
}

data class HealthInputs(
    val system: SystemState,
    val bluetoothEnabled: Boolean,
    val wifiEnabled: Boolean,
    val anyAlertEnabled: Boolean,
    val listening: Boolean,
    val paused: Boolean,
    val journal: ScanJournal,
    val staleAfterMs: Long,
    val now: Long,
)

/** Turns raw state into what is wrong and what to do about it. Pure, so every state is testable. */
object Health {
    private const val NEEDS_LOCATION =
        "Android only delivers scan results to apps that hold precise location, with Location turned on."

    fun readiness(
        system: SystemState,
        bluetoothEnabled: Boolean,
        wifiEnabled: Boolean,
    ): Boolean {
        val locationUsable = system.location == LocationAccess.PRECISE && system.locationOn
        if (!locationUsable) return false
        val bluetooth =
            bluetoothEnabled && system.bluetoothSupported &&
                system.bluetoothPermission == Permission.GRANTED && system.bluetoothOn
        val wifi = wifiEnabled && (system.wifiOn || system.wifiScanAlways)
        return bluetooth || wifi
    }

    /** Whether Android allows the service to run at all, whatever the radios are doing right now. */
    fun canRunService(system: SystemState): Boolean = system.location == LocationAccess.PRECISE

    private fun locationBlock(
        system: SystemState,
        name: String,
    ): Capability? =
        when {
            system.location != LocationAccess.PRECISE -> Capability(name, Level.BLOCKED, "Needs precise location", NEEDS_LOCATION)
            !system.locationOn -> Capability(name, Level.BLOCKED, "Needs Location turned on", NEEDS_LOCATION, Recovery.LOCATION_SETTINGS)
            else -> null
        }

    private fun bluetooth(input: HealthInputs): Capability {
        val name = "Bluetooth"
        val system = input.system
        if (!input.bluetoothEnabled) {
            return Capability(
                name,
                Level.OFF,
                "Off in Déjà",
                "You turned Bluetooth scanning off.",
                Recovery.DEJA_SETTINGS,
            )
        }
        if (!system.bluetoothSupported) return Capability(name, Level.BLOCKED, "Not available", "This phone has no usable Bluetooth radio.")
        when (system.bluetoothPermission) {
            Permission.GRANTED -> Unit

            Permission.PERMANENTLY_DENIED -> return Capability(
                name,
                Level.BLOCKED,
                "Permission denied",
                "Nearby devices permission was refused. It can only be changed in system Settings.",
                Recovery.APP_SETTINGS,
            )

            else -> return Capability(
                name,
                Level.BLOCKED,
                "Permission needed",
                "Déjà needs the Nearby devices permission to hear Bluetooth.",
                Recovery.GRANT_SCANNING,
            )
        }
        if (!system.bluetoothOn) {
            return Capability(
                name,
                Level.BLOCKED,
                "Bluetooth is off",
                "Nothing can be heard until Bluetooth is on.",
                Recovery.ENABLE_BLUETOOTH,
            )
        }
        locationBlock(system, name)?.let { return it }
        return radio(name, input, input.journal.lastBluetoothOk, input.journal.bluetoothFailure)
    }

    private fun wifi(input: HealthInputs): Capability {
        val name = "Wi-Fi"
        val system = input.system
        if (!input.wifiEnabled) return Capability(name, Level.OFF, "Off in Déjà", "You turned Wi-Fi scanning off.", Recovery.DEJA_SETTINGS)
        locationBlock(system, name)?.let { return it }
        if (!system.wifiOn && !system.wifiScanAlways) {
            return Capability(
                name,
                Level.BLOCKED,
                "Wi-Fi is off",
                "Turn on Wi-Fi, or Wi-Fi scanning in Location settings.",
                Recovery.WIFI_SETTINGS,
            )
        }
        return radio(name, input, input.journal.lastWifiOk, input.journal.wifiFailure)
    }

    private fun radio(
        name: String,
        input: HealthInputs,
        lastOk: Long,
        failure: String?,
    ): Capability {
        val last = if (lastOk > 0) "Last scan ${ago(lastOk, input.now)}" else "No scan completed yet"
        return when {
            failure != null -> {
                Capability(name, Level.WARNING, "Last scan failed", "$failure. $last. Déjà keeps retrying.")
            }

            input.paused -> {
                Capability(name, Level.OFF, "Paused", last, Recovery.RESUME)
            }

            !input.listening -> {
                Capability(name, Level.WARNING, "Not scanning", "$last. The scanner is not running.", Recovery.RESUME)
            }

            lastOk == 0L -> {
                Capability(name, Level.OK, "Starting", "The first results take about 15 seconds.")
            }

            input.now - lastOk > input.staleAfterMs -> {
                Capability(
                    name,
                    Level.WARNING,
                    "Results are stale",
                    "$last. Android may be limiting background work.",
                )
            }

            else -> {
                Capability(name, Level.OK, "Working", last)
            }
        }
    }

    private fun location(input: HealthInputs): Capability {
        val name = "Location"
        val system = input.system
        val journal = input.journal
        return when (system.location) {
            LocationAccess.NOT_ASKED, LocationAccess.DENIED -> {
                Capability(
                    name,
                    Level.BLOCKED,
                    "Permission needed",
                    "$NEEDS_LOCATION Your saved history stays available without it.",
                    Recovery.GRANT_SCANNING,
                )
            }

            LocationAccess.PERMANENTLY_DENIED -> {
                Capability(
                    name,
                    Level.BLOCKED,
                    "Permission denied",
                    "Location was refused. It can only be changed in system Settings. Your saved history stays available.",
                    Recovery.APP_SETTINGS,
                )
            }

            LocationAccess.APPROXIMATE -> {
                Capability(
                    name,
                    Level.BLOCKED,
                    "Approximate only",
                    "Scanning needs precise location. Turn on \"Use precise location\" for Déjà.",
                    Recovery.GRANT_SCANNING,
                )
            }

            LocationAccess.PRECISE -> {
                when {
                    !system.locationOn -> {
                        Capability(name, Level.BLOCKED, "Location is off", NEEDS_LOCATION, Recovery.LOCATION_SETTINGS)
                    }

                    journal.lastFixAt == 0L -> {
                        Capability(name, Level.WARNING, "Waiting for a fix", "Sightings are saved without a place until one arrives.")
                    }

                    input.now - journal.lastFixAt > FIX_STALE_MS -> {
                        Capability(
                            name,
                            Level.WARNING,
                            "No recent fix",
                            "Last fix ${ago(journal.lastFixAt, input.now)}. New sightings are saved without a place.",
                        )
                    }

                    else -> {
                        Capability(
                            name,
                            Level.OK,
                            "Working",
                            "Last fix ${ago(journal.lastFixAt, input.now)}, accurate to about ${journal.lastFixAccuracy.toInt()} m.",
                        )
                    }
                }
            }
        }
    }

    private fun notifications(input: HealthInputs): Capability {
        val name = "Alerts"
        val system = input.system
        val stillScans = "Scanning continues and events are still saved in Activity."
        return when {
            system.notificationPermission == Permission.PERMANENTLY_DENIED -> {
                Capability(
                    name,
                    Level.WARNING,
                    "Alerts disabled",
                    "Notifications were refused. $stillScans",
                    Recovery.APP_SETTINGS,
                )
            }

            system.notificationPermission != Permission.GRANTED -> {
                Capability(
                    name,
                    Level.WARNING,
                    "Alerts disabled",
                    "Notifications are not allowed. $stillScans",
                    Recovery.GRANT_NOTIFICATIONS,
                )
            }

            !system.notificationsEnabled -> {
                Capability(
                    name,
                    Level.WARNING,
                    "Alerts disabled",
                    "Déjà's notifications are off in system Settings. $stillScans",
                    Recovery.NOTIFICATION_SETTINGS,
                )
            }

            !system.alertChannelEnabled -> {
                Capability(
                    name,
                    Level.WARNING,
                    "Alerts disabled",
                    "The Alerts notification category is off. $stillScans",
                    Recovery.ALERT_CHANNEL_SETTINGS,
                )
            }

            !input.anyAlertEnabled -> {
                Capability(name, Level.OFF, "All alerts off", "You turned every alert off in Déjà.", Recovery.DEJA_SETTINGS)
            }

            else -> {
                Capability(name, Level.OK, "On", "Alerts can reach you.")
            }
        }
    }

    fun report(input: HealthInputs): HealthReport {
        val lastBurst = input.journal.lastBurstAt
        return HealthReport(
            bluetooth = bluetooth(input),
            wifi = wifi(input),
            location = location(input),
            notifications = notifications(input),
            canScan = readiness(input.system, input.bluetoothEnabled, input.wifiEnabled),
            stale = lastBurst > 0 && input.now - lastBurst > input.staleAfterMs,
        )
    }

    private const val FIX_STALE_MS = 10 * MatchEngine.MINUTE

    fun ago(
        then: Long,
        now: Long,
    ): String {
        val seconds = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            seconds < 10 -> "just now"
            seconds < 60 -> "$seconds s ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} days ago"
        }
    }
}
