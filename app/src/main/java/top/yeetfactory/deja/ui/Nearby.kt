package top.yeetfactory.deja.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yeetfactory.deja.core.Glance
import top.yeetfactory.deja.core.GlanceEngine
import top.yeetfactory.deja.core.HealthReport
import top.yeetfactory.deja.core.Level
import top.yeetfactory.deja.core.MatchEngine
import top.yeetfactory.deja.core.Recovery
import top.yeetfactory.deja.core.ScanJournal
import top.yeetfactory.deja.data.Category
import top.yeetfactory.deja.data.Device
import top.yeetfactory.deja.data.EventRow
import top.yeetfactory.deja.data.Repository
import top.yeetfactory.deja.scan.Blip
import top.yeetfactory.deja.scan.Live
import java.time.LocalDate
import java.time.ZoneId

val Gutter = 16.dp

/** Shared by every list of live devices, so tint always means the same thing. */
@Composable
private fun BlipRow(
    blip: Blip,
    now: Long,
    onOpen: (Long) -> Unit,
) {
    val palette = LocalPalette.current
    val (tint, badge) =
        when {
            blip.category == Category.TRACKER && !blip.owned -> palette.alert to palette.alertSoft
            blip.returning || blip.starred -> palette.accent to palette.accentSoft
            else -> palette.ink to palette.neutralSoft
        }
    val what =
        when {
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
private fun StatusStrip(
    report: HealthReport,
    onHealth: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        report.all.forEach { capability ->
            val icon =
                when (capability.level) {
                    Level.OK -> Icons.Outlined.CheckCircle
                    Level.WARNING -> Icons.Outlined.WarningAmber
                    Level.BLOCKED -> Icons.Outlined.ErrorOutline
                    Level.OFF -> Icons.Outlined.PauseCircle
                }
            Pill(capability.headline, icon = icon, onClick = onHealth)
        }
    }
}

/** What the list below the summary is showing. */
private enum class View { IN_RANGE, PRIVATE, WITH_YOU, NEW_HERE, REGULARS }

private fun startOfToday(): Long =
    LocalDate
        .now()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

/** One count that also filters the list, so a number is never a dead end. */
@Composable
private fun GlanceCount(
    value: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.neutralSoft else palette.surface)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(value, style = Type.number, color = palette.ink, maxLines = 1)
        Spacer(Modifier.height(TextGap))
        Text(label, style = Type.small, color = palette.muted, maxLines = 2)
    }
}

@Composable
private fun SummaryCard(
    glance: Glance,
    live: Live,
    paused: Boolean,
    canScan: Boolean,
    warning: EventRow?,
    view: View,
    now: Long,
    onView: (View) -> Unit,
    onOpen: (Long) -> Unit,
    onOpenEvent: (Long) -> Unit,
) {
    val palette = LocalPalette.current
    val nearby = live.blips.size
    val listening = !paused && live.listening && canScan
    val (title, detail) =
        when {
            warning != null -> {
                "${warning.deviceName} may be following you" to
                    warning.summary.ifEmpty { "Seen at several separate places." }
            }

            paused -> {
                "Paused" to "Déjà isn't listening until you resume."
            }

            !listening -> {
                "Not listening" to "Nothing new can be heard right now."
            }

            else -> {
                GlanceEngine.headline(glance) to GlanceEngine.detail(glance, nearby)
            }
        }

    fun toggle(target: View) = onView(if (view == target) View.IN_RANGE else target)
    val alert = warning != null
    val base =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (alert) palette.alertSoft else palette.surface)

    Column(base.padding(top = 14.dp, bottom = 8.dp)) {
        val head = if (warning != null) Modifier.clickable { onOpenEvent(warning.id) } else Modifier
        Column(head.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = Type.title, color = palette.ink)
            Spacer(Modifier.height(HeadingGap))
            Text(detail, style = Type.small, color = palette.muted)
        }
        Spacer(Modifier.height(10.dp))
        SignalStrip(live.blips, now, onOpen, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val unknown = glance.learning || !glance.placeKnown
            GlanceCount(
                glance.withYou.size.toString(),
                "With you today",
                view == View.WITH_YOU,
                onClick = { toggle(View.WITH_YOU) },
                modifier = Modifier.weight(1f),
            )
            GlanceCount(if (unknown) "–" else glance.newHere.size.toString(), "New here", view == View.NEW_HERE, onClick = {
                toggle(View.NEW_HERE)
            }, modifier = Modifier.weight(1f))
            GlanceCount(if (unknown) "–" else glance.regulars.size.toString(), "Regulars", view == View.REGULARS, onClick = {
                toggle(View.REGULARS)
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun NearbyScreen(
    repository: Repository,
    live: Live,
    warning: EventRow?,
    report: HealthReport,
    journal: ScanJournal,
    paused: Boolean,
    now: Long,
    onToggle: () -> Unit,
    onRecover: (Recovery) -> Unit,
    onHealth: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var view by rememberSaveable { mutableStateOf(View.IN_RANGE) }
    val status =
        when {
            paused -> "Paused by you"
            !live.listening -> "Not scanning"
            !report.canScan -> "Waiting for a radio"
            live.placeName != null -> "Listening at ${live.placeName}"
            else -> "Listening, place not known yet"
        }
    val ranked =
        live.inRange.sortedWith(
            compareByDescending<Blip> { it.category == Category.TRACKER && !it.owned }
                .thenByDescending { it.returning }
                .thenByDescending { it.rssi },
        )
    val worst = report.worst?.takeIf { it.level == Level.BLOCKED }

    // A summary this coarse only needs refreshing when the devices, the place or the minute changes.
    val inRangeIds = ranked.map { it.deviceId }.sorted()
    val minute = now / MatchEngine.MINUTE
    val glance by produceState(Glance(), inRangeIds, live.placeId, minute) {
        runCatching {
            repository.glance(inRangeIds, live.placeId, startOfToday(), System.currentTimeMillis())
        }.onSuccess { value = it }
    }
    val withYouDevices by produceState(emptyList<Device>(), glance.withYou) {
        value = runCatching { repository.dao.devicesByIds(glance.withYou.toList()) }.getOrDefault(emptyList())
    }

    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
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
                Banner("Scanning stopped", "$error.", Icons.Outlined.ErrorOutline, alert = true, action = "Restart scanning", onAction = {
                    onRecover(Recovery.RESUME)
                })
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
                    "The last scan finished ${top.yeetfactory.deja.core.Health.ago(journal.lastBurstAt, now)}.",
                    Icons.Outlined.History,
                    onClick = onHealth,
                )
            }
            SummaryCard(glance, live, paused, report.canScan, warning, view, now, { view = it }, onOpen, onOpenEvent)
            Spacer(Modifier.height(10.dp))
            StatusStrip(report, onHealth)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("In range ${live.inRange.size}", active = view == View.IN_RANGE, onClick = { view = View.IN_RANGE })
                Pill("Private addresses ${live.privateAddresses.size}", active = view == View.PRIVATE, onClick = { view = View.PRIVATE })
            }
            Spacer(Modifier.height(6.dp))
            when (view) {
                View.PRIVATE -> {
                    Note(
                        "Each entry is one private Bluetooth address, not one device. Addresses change over time, so one device can appear several times, and Déjà never assumes two addresses are the same device.",
                        Modifier.padding(bottom = 10.dp),
                    )
                    BlipGroup(live.privateAddresses.sortedByDescending { it.rssi }, "No private addresses heard recently.", now, onOpen)
                }

                View.IN_RANGE -> {
                    BlipGroup(
                        ranked,
                        when {
                            paused -> "Scanning is paused. Your saved history is still in Activity and Devices."
                            !report.canScan -> "Nothing can be heard until scanning is fixed above."
                            else -> "Listening. The first results take about 15 seconds."
                        },
                        now,
                        onOpen,
                    )
                }

                View.NEW_HERE -> {
                    Note("First heard at this place in the last day.", Modifier.padding(bottom = 10.dp))
                    BlipGroup(
                        ranked.filter { it.deviceId in glance.newHere },
                        emptyGlanceText(glance, "Nothing new here right now."),
                        now,
                        onOpen,
                    )
                }

                View.REGULARS -> {
                    Note("Heard at this place before today. Usually neighbours and your own gear.", Modifier.padding(bottom = 10.dp))
                    BlipGroup(
                        ranked.filter { it.deviceId in glance.regulars },
                        emptyGlanceText(glance, "No regulars in range right now."),
                        now,
                        onOpen,
                    )
                }

                View.WITH_YOU -> {
                    Note(
                        "Heard today at places that are clearly apart. This is an early hint, not an alert: your own gear and people you're with will show up here too.",
                        Modifier.padding(bottom = 10.dp),
                    )
                    Group {
                        if (withYouDevices.isEmpty()) GroupText("Nothing has been with you at more than one place today.")
                        withYouDevices.forEachIndexed { index, device ->
                            if (index > 0) Hairline()
                            ListRow(
                                icon = device.category.icon,
                                tint = palette.ink,
                                badge = palette.neutralSoft,
                                title = device.title,
                                subtitle = "Last heard ${ago(device.lastSeen, now).lowercase()}",
                                onClick = { onOpen(device.id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun emptyGlanceText(
    glance: Glance,
    otherwise: String,
) = when {
    !glance.placeKnown -> "This needs to know your place first."
    glance.learning -> "Déjà is still learning this place. Give it a day."
    else -> otherwise
}

@Composable
private fun BlipGroup(
    blips: List<Blip>,
    empty: String,
    now: Long,
    onOpen: (Long) -> Unit,
) {
    Group {
        if (blips.isEmpty()) GroupText(empty)
        blips.forEachIndexed { index, blip ->
            if (index > 0) Hairline()
            BlipRow(blip, now, onOpen)
        }
    }
}
