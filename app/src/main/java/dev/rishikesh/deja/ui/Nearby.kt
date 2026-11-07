package dev.rishikesh.deja.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.core.HealthReport
import dev.rishikesh.deja.core.Level
import dev.rishikesh.deja.core.Recovery
import dev.rishikesh.deja.core.ScanJournal
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.data.EventRow
import dev.rishikesh.deja.data.Totals
import dev.rishikesh.deja.scan.Blip
import dev.rishikesh.deja.scan.Live

val Gutter = 16.dp

/** Shared by every list of live devices, so tint always means the same thing. */
@Composable
private fun BlipRow(blip: Blip, now: Long, onOpen: (Long) -> Unit) {
    val palette = LocalPalette.current
    val (tint, badge) = when {
        blip.category == Category.TRACKER && !blip.owned -> palette.alert to palette.alertSoft
        blip.returning || blip.starred -> palette.accent to palette.accentSoft
        else -> palette.ink to palette.neutralSoft
    }
    val what = when {
        blip.returning -> "Back again"
        blip.subtitle.isNotEmpty() -> blip.subtitle
        else -> blip.category.label
    }
    ListRow(
        icon = blip.category.icon,
        tint = tint,
        badge = badge,
        title = blip.title,
        subtitle = "$what · ${signalWord(blip.rssi).lowercase()} signal, ${heard(blip.heardAt, now)}",
        onClick = { onOpen(blip.deviceId) },
        trailing = { SignalBars(blip.rssi) },
    )
}

@Composable
private fun StatusStrip(report: HealthReport, onHealth: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        report.all.forEach { capability ->
            val icon = when (capability.level) {
                Level.OK -> Icons.Outlined.CheckCircle
                Level.WARNING -> Icons.Outlined.WarningAmber
                Level.BLOCKED -> Icons.Outlined.ErrorOutline
                Level.OFF -> Icons.Outlined.PauseCircle
            }
            Pill(capability.headline, icon, onClick = onHealth)
        }
    }
}

@Composable
fun NearbyScreen(
    live: Live,
    totals: Totals,
    warning: EventRow?,
    report: HealthReport,
    journal: ScanJournal,
    paused: Boolean,
    clock: State<Float>,
    now: Long,
    onToggle: () -> Unit,
    onRecover: (Recovery) -> Unit,
    onHealth: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenEvent: (Long) -> Unit,
) {
    val palette = LocalPalette.current
    var showPrivate by rememberSaveable { mutableStateOf(false) }
    val status = when {
        paused -> "Paused by you"
        !live.listening -> "Not scanning"
        !report.canScan -> "Waiting for a radio"
        live.placeName != null -> "Listening at ${live.placeName}"
        else -> "Listening, place not known yet"
    }
    val ranked = live.inRange.sortedWith(
        compareByDescending<Blip> { it.category == Category.TRACKER && !it.owned }
            .thenByDescending { it.returning }
            .thenByDescending { it.rssi },
    )
    val worst = report.worst?.takeIf { it.level == Level.BLOCKED }

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item {
            Header("Nearby", status) {
                Pill(
                    text = if (paused) "Resume" else "Pause",
                    icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    onClick = onToggle,
                )
            }
            if (live.simulated) Note("Simulated data, not real devices", Modifier.padding(bottom = 8.dp))

            journal.serviceError?.let { error ->
                Banner("Scanning stopped", "$error.", Icons.Outlined.ErrorOutline, alert = true, action = "Restart scanning", onAction = { onRecover(Recovery.RESUME) })
            }
            if (worst != null) {
                Banner(
                    worst.headline,
                    worst.detail,
                    Icons.Outlined.ErrorOutline,
                    alert = true,
                    action = worst.recovery?.label,
                    onAction = worst.recovery?.let { { onRecover(it) } },
                )
            } else if (report.stale && !paused) {
                Banner(
                    "Results may be out of date",
                    "The last scan finished ${dev.rishikesh.deja.core.Health.ago(journal.lastBurstAt, now)}.",
                    Icons.Outlined.History,
                    onClick = onHealth,
                )
            }
            warning?.let { event ->
                Banner(
                    "${event.deviceName} may be following you",
                    event.summary.ifEmpty { "Seen at several separate places." },
                    Icons.Outlined.WarningAmber,
                    alert = true,
                    onClick = { onOpenEvent(event.id) },
                )
            }
            StatusStrip(report, onHealth)
            SignalField(
                blips = live.inRange,
                transient = live.privateAddresses.size,
                listening = live.listening,
                clock = clock,
                now = now,
                onOpen = onOpen,
                modifier = Modifier.fillMaxWidth().height(270.dp),
            )
            Note(
                "A pin's position does not show direction, and its ring is signal strength, not distance. Faded pins were heard a while ago.",
                Modifier.padding(bottom = 10.dp),
            )
            Group {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Metric(live.inRange.size.toString(), "In range", Modifier.weight(1f))
                    Metric(live.privateAddresses.size.toString(), "Private addresses", Modifier.weight(1.2f))
                    Metric("%,d".format(totals.devices), "Remembered", Modifier.weight(1.1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("In range", active = !showPrivate) { showPrivate = false }
                Pill("Private addresses", active = showPrivate) { showPrivate = true }
            }
            Spacer(Modifier.height(6.dp))
            if (showPrivate) {
                Note(
                    "Each entry is one private Bluetooth address, not one device. Addresses change over time, so one device can appear several times, and Déjà never assumes two addresses are the same device.",
                    Modifier.padding(bottom = 10.dp),
                )
                Group {
                    val encounters = live.privateAddresses.sortedByDescending { it.rssi }
                    if (encounters.isEmpty()) GroupText("No private addresses heard recently.")
                    encounters.forEachIndexed { index, blip ->
                        if (index > 0) Hairline()
                        BlipRow(blip, now, onOpen)
                    }
                }
            } else {
                Group {
                    if (ranked.isEmpty()) {
                        GroupText(
                            when {
                                paused -> "Scanning is paused. Your saved history is still in Activity and Devices."
                                !report.canScan -> "Nothing can be heard until scanning is fixed above."
                                else -> "Listening. The first results take about 15 seconds."
                            },
                        )
                    }
                    ranked.forEachIndexed { index, blip ->
                        if (index > 0) Hairline()
                        BlipRow(blip, now, onOpen)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
