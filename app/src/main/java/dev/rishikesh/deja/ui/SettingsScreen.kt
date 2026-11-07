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
import androidx.compose.ui.graphics.vector.ImageVector
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

private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

/** A section title with one line saying what the whole group is for. */
@Composable
private fun Section(title: String, about: String) {
    SectionLabel(title, Modifier.padding(top = 24.dp))
    Note(about, Modifier.padding(bottom = 10.dp))
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    ListRow(icon = icon, tint = palette.ink, badge = palette.neutralSoft, title = title, subtitle = detail, onClick = onClick)
}

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
                ActionRow(Icons.Outlined.MonitorHeart, "Scanner health", "See what's working, and fix what isn't", onHealth)
            }

            Section("Appearance", "How Déjà looks.")
            Group {
                ChoiceRow(
                    "Theme",
                    null,
                    ThemeMode.entries,
                    settings.theme,
                    ThemeMode::label,
                    icon = ::themeIcon,
                ) { choice ->
                    store.update { it.copy(theme = choice) }
                    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(choice.nightMode)
                }
            }

            Section("What Déjà listens for", "Turning something off means Déjà can't notice it.")
            Group {
                SwitchRow("Bluetooth devices", "Phones, earbuds, watches and trackers. Needed to spot anything following you.", settings.bluetoothEnabled) { on ->
                    store.update { it.copy(bluetoothEnabled = on) }
                }
                Hairline(inset = 16.dp)
                SwitchRow("Wi-Fi networks", "Remembers networks near you, including hotspots that move.", settings.wifiEnabled) { on ->
                    store.update { it.copy(wifiEnabled = on) }
                }
                Hairline(inset = 16.dp)
                ChoiceRow(
                    "How often to listen",
                    settings.cadence.detail,
                    Cadence.entries,
                    settings.cadence,
                    Cadence::label,
                ) { choice -> store.update { it.copy(cadence = choice) } }
            }
            Note(
                "Déjà slows down on its own in Battery Saver or below 15% battery. With the screen off, Android only lets it hear known brands and trackers.",
                Modifier.padding(top = 10.dp),
            )

            Section("When to notify you", "Everything is still saved in Activity. These only decide what reaches your notifications.")
            Group {
                SwitchRow("Something may be following you", "A device heard at several separate places in a row. Best left on.", alerts.following) { on ->
                    store.update { it.copy(alerts = it.alerts.copy(following = on)) }
                }
                Hairline(inset = 16.dp)
                SwitchRow("A watched device comes back", "Only devices you've tapped Watch on.", alerts.watched) { on ->
                    store.update { it.copy(alerts = it.alerts.copy(watched = on)) }
                }
                Hairline(inset = 16.dp)
                SwitchRow("A new tracker is nearby", "Every time one is first heard. Can be noisy in busy places.", alerts.newTracker) { on ->
                    store.update { it.copy(alerts = it.alerts.copy(newTracker = on)) }
                }
                Hairline(inset = 16.dp)
                ActionRow(Icons.Outlined.NotificationsNone, "Send a test alert", "Check that alerts can reach you") {
                    val result = Notifier(context).test(Probe(context, (context.applicationContext as DejaApp).settings))
                    message = if (result == Delivery.POSTED) "Test alert sent." else deliveryText(result)
                }
            }

            Section("What Déjà remembers", "Old data is forgotten on its own. Devices you've named, watched, ignored or marked as yours are never forgotten.")
            Group {
                ChoiceRow(
                    "Keep where and when devices were seen",
                    "Applies to sightings and alerts.",
                    HISTORY_DAYS,
                    retention.historyDays,
                    ::days,
                ) { value -> store.update { it.copy(retention = it.retention.copy(historyDays = value)) } }
                Hairline(inset = 16.dp)
                SwitchRow(
                    "Keep full history for those saved devices",
                    "Turn this off to keep only their name, and forget where they've been after the limit above.",
                    retention.keepSavedHistory,
                ) { on -> store.update { it.copy(retention = it.retention.copy(keepSavedHistory = on)) } }
                Hairline(inset = 16.dp)
                ChoiceRow(
                    "Forget unnamed devices",
                    "After this long without being heard.",
                    UNNAMED_DAYS,
                    retention.unnamedDays,
                    ::days,
                ) { value -> store.update { it.copy(retention = it.retention.copy(unnamedDays = value)) } }
                Hairline(inset = 16.dp)
                ChoiceRow(
                    "Forget private addresses",
                    "These change by themselves, so old ones aren't much use.",
                    ENCOUNTER_DAYS,
                    retention.encounterDays,
                    ::days,
                ) { value -> store.update { it.copy(retention = it.retention.copy(encounterDays = value)) } }
            }

            Section("Your data", "What's stored on this phone right now.")
            Group {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Metric("%,d".format(totals.devices), "Remembered", Modifier.weight(1f))
                    Metric("%,d".format(totals.encounters), "Private addresses", Modifier.weight(1.4f))
                    Metric("%,d".format(totals.places), "Places", Modifier.weight(0.7f))
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    Metric("%,d".format(totals.sightings), "Sightings", Modifier.weight(1f))
                    Metric("%,d".format(totals.events), "Events", Modifier.weight(2.1f))
                }
                Hairline(inset = 16.dp)
                ActionRow(Icons.Outlined.CleaningServices, "Clean up now", "Apply the limits above straight away, and remove unused places") {
                    scope.launch {
                        repository.prune(System.currentTimeMillis(), retention)
                        message = "Old history and unused places were cleaned up."
                    }
                }
                Hairline(inset = 16.dp)
                ActionRow(Icons.Outlined.DeleteForever, "Clear all data", "Delete every device, place and alert. Your settings stay.") {
                    confirmClear = true
                }
            }
            message?.let { Note(it, Modifier.padding(top = 10.dp)) }
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
