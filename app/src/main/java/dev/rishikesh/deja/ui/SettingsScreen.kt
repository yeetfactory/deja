package dev.rishikesh.deja.ui

import android.app.UiModeManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.DejaApp
import dev.rishikesh.deja.core.Cadence
import dev.rishikesh.deja.data.AppSettings
import dev.rishikesh.deja.data.Delivery
import dev.rishikesh.deja.data.Repository
import dev.rishikesh.deja.data.SettingsStore
import dev.rishikesh.deja.data.ThemeMode
import dev.rishikesh.deja.data.Totals
import dev.rishikesh.deja.scan.Notifier
import dev.rishikesh.deja.scan.Probe
import kotlinx.coroutines.launch

private val HISTORY_DAYS = listOf(7, 30, 90, 365)
private val UNNAMED_DAYS = listOf(7, 30, 90)
private val ENCOUNTER_DAYS = listOf(1, 7, 30)

private fun days(value: Int) = if (value == 1) "1 day" else "$value days"

@Composable
fun SettingsScreen(repository: Repository, store: SettingsStore, settings: AppSettings, totals: Totals, onHealth: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val retention = settings.retention
    val alerts = settings.alerts

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item {
            Header("Settings", "Everything stays on this phone.")
            Group {
                ListRow(
                    icon = Icons.Outlined.MonitorHeart,
                    tint = LocalPalette.current.ink,
                    badge = LocalPalette.current.neutralSoft,
                    title = "Scanner health",
                    subtitle = "Permissions, radios, recent scans and failures",
                    onClick = onHealth,
                )
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Appearance")
            Group {
                ChoiceRow(
                    "Theme",
                    "System follows your phone's setting.",
                    ThemeMode.entries,
                    settings.theme,
                    ThemeMode::label,
                ) { choice ->
                    store.update { it.copy(theme = choice) }
                    // Android applies this to the whole window, so the bars and launch colour match too.
                    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(choice.nightMode)
                }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Scanning")
            Group {
                SwitchRow("Bluetooth scanning", "Hears nearby Bluetooth devices", settings.bluetoothEnabled) { on ->
                    store.update { it.copy(bluetoothEnabled = on) }
                }
                Hairline(inset = 16.dp)
                SwitchRow("Wi-Fi scanning", "Hears nearby Wi-Fi networks", settings.wifiEnabled) { on ->
                    store.update { it.copy(wifiEnabled = on) }
                }
                Hairline(inset = 16.dp)
                ChoiceRow(
                    "Scan frequency",
                    settings.cadence.detail,
                    Cadence.entries,
                    settings.cadence,
                    Cadence::label,
                ) { choice -> store.update { it.copy(cadence = choice) } }
            }
            Note(
                "With the screen off Android only lets Déjà hear known brands and trackers. In Battery Saver, or below 15% battery, scans slow down automatically.",
                Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            SectionLabel("Alerts")
            Group {
                SwitchRow("May be following you", "A device seen at separate places in sequence", alerts.following) { on ->
                    store.update { it.copy(alerts = it.alerts.copy(following = on)) }
                }
                Hairline(inset = 16.dp)
                SwitchRow("Watched devices", "A device you watch comes back", alerts.watched) { on ->
                    store.update { it.copy(alerts = it.alerts.copy(watched = on)) }
                }
                Hairline(inset = 16.dp)
                SwitchRow("New trackers", "Each time a new tracker is heard. Can be noisy in busy places.", alerts.newTracker) { on ->
                    store.update { it.copy(alerts = it.alerts.copy(newTracker = on)) }
                }
            }
            Pill("Send a test alert", Icons.Outlined.NotificationsNone) {
                val result = Notifier(context).test(Probe(context, (context.applicationContext as DejaApp).settings))
                message = if (result == Delivery.POSTED) "Test alert sent." else deliveryText(result)
            }
            Spacer(Modifier.height(10.dp))

            SectionLabel("History")
            Group {
                ChoiceRow(
                    "Keep sightings and events for",
                    "This covers where and when devices were seen.",
                    HISTORY_DAYS,
                    retention.historyDays,
                    ::days,
                ) { value -> store.update { it.copy(retention = it.retention.copy(historyDays = value)) } }
                Hairline(inset = 16.dp)
                SwitchRow(
                    "Keep history of saved devices",
                    "Named, watched, ignored and owned devices keep their history past that limit. Turned off, they keep only their name and settings.",
                    retention.keepSavedHistory,
                ) { on -> store.update { it.copy(retention = it.retention.copy(keepSavedHistory = on)) } }
                Hairline(inset = 16.dp)
                ChoiceRow(
                    "Forget unnamed devices after",
                    "Counted from when each was last heard.",
                    UNNAMED_DAYS,
                    retention.unnamedDays,
                    ::days,
                ) { value -> store.update { it.copy(retention = it.retention.copy(unnamedDays = value)) } }
                Hairline(inset = 16.dp)
                ChoiceRow(
                    "Forget private-address encounters after",
                    null,
                    ENCOUNTER_DAYS,
                    retention.encounterDays,
                    ::days,
                ) { value -> store.update { it.copy(retention = it.retention.copy(encounterDays = value)) } }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Your data")
            Group {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Metric("%,d".format(totals.devices), "Remembered", Modifier.weight(1f))
                    Metric("%,d".format(totals.encounters), "Encounters", Modifier.weight(1f))
                    Metric("%,d".format(totals.sightings), "Sightings", Modifier.weight(1f))
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    Metric("%,d".format(totals.events), "Events", Modifier.weight(1f))
                    Metric("%,d".format(totals.places), "Places", Modifier.weight(2f))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Apply retention now", Icons.Outlined.CleaningServices) {
                    scope.launch {
                        repository.prune(System.currentTimeMillis(), retention)
                        message = "Old history and unused places were cleaned up."
                    }
                }
                Pill("Clear all data", Icons.Outlined.DeleteForever) { confirmClear = true }
            }
            message?.let { Note(it, Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            "Clear all data?",
            "Every device, label, place, sighting and event is permanently removed. Your settings stay. This cannot be undone.",
            "Clear everything",
            onConfirm = {
                confirmClear = false
                scope.launch {
                    repository.clearAll()
                    message = "All data was cleared."
                }
            },
            onCancel = { confirmClear = false },
        )
    }
}
