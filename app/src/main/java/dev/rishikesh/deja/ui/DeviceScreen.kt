package dev.rishikesh.deja.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rishikesh.deja.core.DayRow
import dev.rishikesh.deja.core.History
import dev.rishikesh.deja.core.HistoryPoint
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.data.Device
import dev.rishikesh.deja.data.IdentityConfidence
import dev.rishikesh.deja.data.Radio
import dev.rishikesh.deja.data.Repository
import dev.rishikesh.deja.data.TypeConfidence
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.min

private const val PAGE = 20

private data class Rename(
    val title: String,
    val value: String,
    val onSave: (String) -> Unit,
)

fun typeConfidenceText(value: TypeConfidence) =
    when (value) {
        TypeConfidence.HIGH -> "High"
        TypeConfidence.MEDIUM -> "Medium"
        TypeConfidence.LOW -> "Low"
        TypeConfidence.NONE -> "None"
    }

fun identityText(value: IdentityConfidence) =
    when (value) {
        IdentityConfidence.STABLE -> "Stable address: recognised when it returns"
        IdentityConfidence.MAY_CHANGE -> "Address changes over time: recognised only until it does"
        IdentityConfidence.ADDRESS_ONLY -> "This address only: says nothing about the device behind it"
    }

/** The device screen owns its loading, deleted and failed states, so a stale link can't crash it. */
@Composable
fun DeviceRoute(
    repository: Repository,
    deviceId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by remember(deviceId) { repository.dao.watchDevice(deviceId) }.collectAsync()
    when (val current = state) {
        Async.Loading -> {
            Column(modifier.padding(horizontal = Gutter)) {
                BackButton(onBack)
                LoadingState()
            }
        }

        is Async.Failed -> {
            Column(modifier.padding(horizontal = Gutter)) {
                BackButton(onBack)
                ErrorState(current.message)
            }
        }

        is Async.Ready -> {
            val device = current.value
            if (device == null) {
                Column(modifier.padding(horizontal = Gutter)) {
                    BackButton(onBack)
                    Group { GroupText("This device has been deleted, or was forgotten by your retention settings.") }
                }
            } else {
                DeviceScreen(repository, device, onBack, modifier)
            }
        }
    }
}

@Composable
private fun DeviceScreen(
    repository: Repository,
    device: Device,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val dao = repository.dao
    var rename by remember { mutableStateOf<Rename?>(null) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var table by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable(device.id) { mutableIntStateOf(PAGE) }

    val points by remember(device.id) { dao.watchHistoryPoints(device.id) }.collectAsStateWithLifecycle(emptyList())
    val recent by remember(device.id, limit) { dao.watchSightings(device.id, limit) }.collectAsStateWithLifecycle(emptyList())
    val places by remember { dao.watchPlaces() }.collectAsStateWithLifecycle(emptyList())
    val format = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()

    val names = remember(places) { places.associate { it.id to it.name } }
    val visits = remember(points) { History.visits(points) }
    val byPlace =
        remember(points) {
            points
                .mapNotNull { it.placeId }
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
        }
    val week = remember(points) { History.week(points.map(HistoryPoint::time), now, ZoneId.systemDefault()) }
    val tracker = device.category == Category.TRACKER && !device.owned

    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item {
            BackButton(onBack)
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    device.category.icon,
                    if (tracker) palette.alert else palette.ink,
                    if (tracker) palette.alertSoft else palette.surface,
                    size = 52.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(device.title, style = Type.title, color = palette.ink, maxLines = 3)
                    Spacer(Modifier.height(TextGap))
                    val type = if (device.title == device.descriptor) device.category.label else device.descriptor ?: device.category.label
                    Text(
                        "$type · ${if (device.radio == Radio.WIFI) "Wi-Fi" else "Bluetooth"}",
                        style = Type.small,
                        color = palette.muted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LiveSignalCard(device)
            if (device.temporary) {
                Banner(
                    "Private-address encounter",
                    "This record covers one address. If the device changes address it will look like a new encounter, and Déjà will not link the two.",
                    Icons.Outlined.Shuffle,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    if (device.starred) "Watching" else "Watch",
                    icon = if (device.starred) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    active = device.starred,
                    onClick = { scope.launch { repository.updateFlags(device.copy(starred = !device.starred)) } },
                )
                Pill(
                    "This is mine",
                    icon = Icons.Outlined.Person,
                    active = device.owned,
                    onClick = { scope.launch { repository.updateFlags(device.copy(owned = !device.owned, ignored = false)) } },
                )
                Pill(
                    if (device.ignored) "Ignored" else "Ignore",
                    icon = Icons.Outlined.VisibilityOff,
                    active = device.ignored,
                    onClick = { scope.launch { repository.updateFlags(device.copy(ignored = !device.ignored)) } },
                )
                Pill(
                    "Rename",
                    icon = Icons.Outlined.Edit,
                    onClick = {
                        rename =
                            Rename("Name this device", device.label.orEmpty()) {
                                scope.launch { repository.updateFlags(device.copy(label = it.ifBlank { null })) }
                            }
                    },
                )
                Pill("What do these do?", icon = Icons.AutoMirrored.Outlined.HelpOutline, active = explaining, onClick = {
                    explaining =
                        !explaining
                })
            }
            if (explaining) {
                Group(Modifier.padding(top = 6.dp)) {
                    Explain(
                        "Watch",
                        "Notifies you when this device comes back after being away. It also keeps the device from being forgotten.",
                    )
                    Hairline(inset = 16.dp)
                    Explain("This is mine", "Stops following and tracker alerts for it, and keeps it. It stays visible in Nearby.")
                    Hairline(inset = 16.dp)
                    Explain(
                        "Ignore",
                        "Hides it from Nearby and stops all events and alerts. Its existing history stays until you delete it.",
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Group {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Metric("%,d".format(points.size), "Sightings", Modifier.weight(1f))
                    Metric(visits.size.toString(), "Separate visits", Modifier.weight(1f))
                    Metric(byPlace.size.toString(), "Places", Modifier.weight(0.8f))
                    Metric(ago(device.lastSeen, now), "Last seen", Modifier.weight(1.3f))
                }
            }
            Note(
                "A sighting is one saved reading, at most one every 5 minutes per place. A visit is a run of sightings with no gap over 30 minutes.",
                Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            SectionLabel("What Déjà thinks this is")
            Group {
                DetailRow("Type", device.descriptor ?: device.category.label)
                Hairline(inset = 16.dp)
                DetailRow("Type confidence", typeConfidenceText(device.typeConfidence))
                Hairline(inset = 16.dp)
                DetailRow("Identity confidence", identityText(device.identity))
                Hairline(inset = 16.dp)
                DetailRow(
                    "Category",
                    if (device.categoryManual) {
                        "${device.category.label}, set by you. Automatic: ${device.autoCategory.label}"
                    } else {
                        "${device.category.label}, automatic"
                    },
                )
                Hairline(inset = 16.dp)
                FlowRow(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Change category", active = picking, onClick = { picking = !picking })
                    if (device.categoryManual) {
                        Pill("Use automatic", onClick = { scope.launch { dao.resetCategory(device.id) } })
                    }
                }
                if (picking) {
                    FlowRow(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Category.entries.forEach { option ->
                            Pill(
                                option.label,
                                active = device.categoryManual && option == device.category,
                                onClick = {
                                    picking = false
                                    scope.launch { dao.setCategory(device.id, option) }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Where it's been")
            Group {
                if (byPlace.isEmpty()) GroupText("No location recorded.")
                val most = byPlace.firstOrNull()?.second ?: 1
                byPlace.forEachIndexed { index, (id, count) ->
                    if (index > 0) Hairline(inset = 16.dp)
                    val name = names[id] ?: "Unnamed place"
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                rename =
                                    Rename("Name this place", name) {
                                        if (it.isNotBlank()) scope.launch { dao.renamePlace(id, it) }
                                    }
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row {
                            Text(name, style = Type.body, color = palette.ink, modifier = Modifier.weight(1f), maxLines = 2)
                            Text("$count ${if (count == 1) "sighting" else "sightings"}", style = Type.small, color = palette.muted)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(palette.neutralSoft),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(count / most.toFloat())
                                    .clip(CircleShape)
                                    .background(palette.accent),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("When it's been around, last 7 days")
            Group {
                Box(Modifier.padding(16.dp)) { Hours(week) }
                Pill(
                    if (table) "Hide day-by-day list" else "Show as a list",
                    active = table,
                    modifier = Modifier.padding(start = 10.dp),
                    onClick = {
                        table =
                            !table
                    },
                )
                if (table) {
                    week.forEach { row ->
                        Hairline(inset = 16.dp)
                        DetailRow(dayName(row), daySummary(row))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Recent sightings")
            Group {
                if (recent.isEmpty()) GroupText("No sightings kept.")
                recent.forEachIndexed { index, sighting ->
                    if (index > 0) Hairline(inset = 16.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp).clearAndSetSemantics {
                            contentDescription =
                                "${sighting.placeName ?: "No location"}, ${format.format(
                                    Date(sighting.time),
                                )}, ${signalWord(sighting.rssi)} signal"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sighting.placeName ?: "No location", style = Type.body, color = palette.ink, maxLines = 2)
                            Text(
                                "${signalWord(sighting.rssi)} signal, ${sighting.rssi} dBm" +
                                    (sighting.accuracy?.let { " · location ±${it.toInt()} m" } ?: ""),
                                style = Type.small,
                                color = palette.muted,
                            )
                        }
                        Text(format.format(Date(sighting.time)), style = Type.small, color = palette.muted)
                    }
                }
            }
            LoadMore(recent.size, points.size, onMore = { limit += PAGE })
            Spacer(Modifier.height(20.dp))

            SectionLabel("Diagnostics")
            Group {
                Pill(
                    if (diagnostics) "Hide details" else "Show details",
                    active = diagnostics,
                    modifier = Modifier.padding(start = 10.dp),
                    onClick = {
                        diagnostics =
                            !diagnostics
                    },
                )
                if (diagnostics) {
                    DetailRow("Record", "${device.code} · ${device.key}")
                    Hairline(inset = 16.dp)
                    DetailRow("Address type", device.addressType.ifEmpty { "Not recorded yet" })
                    Hairline(inset = 16.dp)
                    DetailRow("Company", device.vendor?.removeSuffix(" device") ?: "None advertised")
                    Hairline(inset = 16.dp)
                    DetailRow("Advertised name", device.broadcastName ?: "None")
                    Hairline(inset = 16.dp)
                    DetailRow("First seen", format.format(Date(device.firstSeen)))
                    Hairline(inset = 16.dp)
                    DetailRow("Last seen", format.format(Date(device.lastSeen)))
                    Hairline(inset = 16.dp)
                    DetailRow("Signal range", "${device.minRssi} to ${device.maxRssi} dBm, last ${device.lastRssi} dBm")
                    Hairline(inset = 16.dp)
                    DetailRow("Readings heard", "%,d".format(device.observations))
                    Hairline(inset = 16.dp)
                    DetailRow("Evidence", device.evidence.ifEmpty { "None recorded yet" })
                    Hairline(inset = 16.dp)
                    DetailRow("Fingerprint", device.fingerprint.ifEmpty { "None recorded yet" })
                    Note(
                        "The fingerprint describes this advertisement's shape. Déjà never uses it to link addresses.",
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Your data")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Delete history", icon = Icons.Outlined.DeleteSweep, onClick = { confirm = "history" })
                Pill("Delete device", icon = Icons.Outlined.Delete, onClick = { confirm = "device" })
            }
            Note("Delete history forgets when and where it was seen, and keeps its name and settings.", Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    when (confirm) {
        "history" -> {
            ConfirmDialog(
                "Delete this device's history?",
                "Its sightings, places and events are removed. Its name and settings stay.",
                "Delete history",
                onConfirm = {
                    confirm = null
                    scope.launch { repository.deleteDeviceHistory(device.id) }
                },
                onCancel = { confirm = null },
            )
        }

        "device" -> {
            ConfirmDialog(
                "Delete this device?",
                "Its name, settings and all of its history are removed. If it is heard again it starts as a new device.",
                "Delete device",
                onConfirm = {
                    confirm = null
                    scope.launch { repository.deleteDevice(device.id) }
                },
                onCancel = { confirm = null },
            )
        }
    }

    rename?.let { current ->
        TextEntryDialog(
            current.title,
            current.value,
            onSave = {
                current.onSave(it)
                rename = null
            },
            onCancel = { rename = null },
        )
    }
}

@Composable
private fun Explain(
    title: String,
    body: String,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(title, style = Type.body, color = palette.ink)
        Spacer(Modifier.height(TextGap))
        Text(body, style = Type.small, color = palette.muted)
    }
}

@Composable
fun DetailRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = Type.small, color = palette.muted)
        Spacer(Modifier.height(TextGap))
        Text(value, style = Type.text, color = palette.ink)
    }
}

private fun dayName(row: DayRow) = row.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun daySummary(row: DayRow): String {
    if (row.total == 0) return "No sightings"
    val busiest = row.hours.indices.maxBy { row.hours[it] }
    return "${row.total} ${if (row.total == 1) "sighting" else "sightings"}, most around %02d:00".format(busiest)
}

/** Single-hue matrix: rows are the last 7 days, columns are hours, darker means more sightings. */
@Composable
private fun Hours(rows: List<DayRow>) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val days = rows.size
    val peak = rows.maxOf { row -> row.hours.max() }.coerceAtLeast(1)
    val locale = LocalConfiguration.current.locales[0]
    val labels = rows.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
    val spoken =
        "Chart of sightings by hour for the last 7 days. " +
            "${rows.sumOf { it.total }} sightings in total. The same data is available as a list below."

    Canvas(Modifier.fillMaxWidth().aspectRatio(2.35f).clearAndSetSemantics { contentDescription = spoken }) {
        val gap = 2.dp.toPx()
        val left = 34.dp.toPx()
        val bottom = 18.dp.toPx()
        val cell = min((size.width - left) / 24f, (size.height - bottom) / days)
        rows.forEachIndexed { index, row ->
            val label = measurer.measure(labels[index], Type.micro)
            drawText(label, palette.muted, Offset(0f, index * cell + (cell - gap - label.size.height) / 2))
            for (hour in 0 until 24) {
                val count = row.hours[hour]
                drawRoundRect(
                    if (count == 0) palette.neutralSoft else palette.accent.copy(alpha = 0.3f + 0.7f * count / peak),
                    topLeft = Offset(left + hour * cell, index * cell),
                    size = Size(cell - gap, cell - gap),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
        listOf(0, 6, 12, 18).forEach { hour ->
            val label = measurer.measure("%02d:00".format(hour), Type.micro)
            drawText(label, palette.muted, Offset(left + hour * cell, days * cell + 4.dp.toPx()))
        }
    }
}
