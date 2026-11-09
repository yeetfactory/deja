package dev.rishikesh.deja.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.core.EvidenceCodec
import dev.rishikesh.deja.core.EvidenceStep
import dev.rishikesh.deja.core.FollowEngine
import dev.rishikesh.deja.data.Delivery
import dev.rishikesh.deja.data.EventRow
import dev.rishikesh.deja.data.EventType
import dev.rishikesh.deja.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun explanation(type: EventType) =
    when (type) {
        EventType.FOLLOWING -> {
            "Déjà warns about this only after repeated readings at places that are clearly apart, in sequence, over at least 30 minutes. It can still be innocent, such as a companion's device or one on the same bus."
        }

        EventType.NEW_TRACKER -> {
            "This device advertised something that identifies it as a tracker. On its own this does not mean anyone is tracking you."
        }

        EventType.NEW_FIND_MY -> {
            "An earlier version called this a tracker. All it actually showed was a Find My broadcast, which many Apple products send, so the model is unknown."
        }

        EventType.RETURNED -> {
            "This device was away and has now been heard again."
        }
    }

@Composable
fun AlertRoute(
    repository: Repository,
    eventId: Long,
    onBack: () -> Unit,
    onOpenDevice: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by remember(eventId) { repository.dao.watchEvent(eventId) }.collectAsync()
    Column(modifier.fillMaxSize().padding(horizontal = Gutter)) {
        BackButton(onBack)
        when (val current = state) {
            Async.Loading -> {
                LoadingState()
            }

            is Async.Failed -> {
                ErrorState(current.message)
            }

            is Async.Ready -> {
                val event = current.value
                if (event == null) {
                    Group { GroupText("This alert has been deleted, or expired under your retention settings.") }
                } else {
                    AlertScreen(repository, event, onBack, onOpenDevice)
                }
            }
        }
    }
}

@Composable
private fun AlertScreen(
    repository: Repository,
    event: EventRow,
    onBack: () -> Unit,
    onOpenDevice: (Long) -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exportFailed by remember { mutableStateOf(false) }
    val steps = remember(event.evidence) { EvidenceCodec.decode(event.evidence) }
    val format = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                scope.launch {
                    exportFailed =
                        runCatching {
                            val text = repository.exportEvent(event.id).orEmpty()
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                            }
                        }.isFailure
                }
            }
        }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(eventTitle(event), style = Type.title, color = palette.ink)
            Spacer(Modifier.height(HeadingGap))
            Text(format.format(Date(event.time)), style = Type.small, color = palette.muted)
            Spacer(Modifier.height(12.dp))
            if (event.summary.isNotEmpty()) {
                Text(event.summary, style = Type.text, color = palette.ink)
                Spacer(Modifier.height(8.dp))
            }
            Note(explanation(event.type), Modifier.padding(bottom = 12.dp))

            if (event.dismissed) Banner("Dismissed", null, Icons.Outlined.Check)

            SectionLabel("Evidence")
            Group {
                if (steps.isEmpty()) {
                    GroupText(route(event).ifEmpty { "No timeline was recorded for this event." })
                }
                steps.forEachIndexed { index, step ->
                    if (index > 0) Hairline(inset = 16.dp)
                    Step(step, format)
                }
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel("Notification")
            Group {
                DetailRow(
                    "Status",
                    if (event.eligible) deliveryText(event.delivery).orEmpty() else "This event did not call for a notification",
                )
                if (event.delivery == Delivery.BLOCKED_PERMISSION || event.delivery == Delivery.BLOCKED_CHANNEL) {
                    Note("Fix this under Scanner health on the Nearby tab.", Modifier.padding(start = 12.dp, bottom = 12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel("What would you like to do?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    "This is mine",
                    icon = Icons.Outlined.Person,
                    active = event.owned,
                    onClick = {
                        scope.launch { repository.markMine(event.deviceId) }
                    },
                )
                Pill(
                    "Ignore",
                    icon = Icons.Outlined.VisibilityOff,
                    active = event.ignored,
                    onClick = {
                        scope.launch { repository.markIgnored(event.deviceId) }
                    },
                )
                Pill(
                    "Dismiss",
                    icon = Icons.Outlined.Close,
                    active = event.dismissed,
                    onClick = {
                        scope.launch {
                            repository.dao.dismissEvent(event.id)
                            onBack()
                        }
                    },
                )
                Pill("Open device", icon = Icons.Outlined.Devices, onClick = { onOpenDevice(event.deviceId) })
                Pill("Export observations", icon = Icons.Outlined.Download, onClick = { export.launch("deja-event-${event.id}.csv") })
            }
            if (exportFailed) Note("The export could not be saved. Try another location.", Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(
    step: EvidenceStep,
    format: SimpleDateFormat,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(step.placeName, style = Type.body, color = palette.ink)
        Spacer(Modifier.height(TextGap))
        val span = if (step.end > step.start) " for ${FollowEngine.duration(step.end - step.start)}" else ""
        Text("${format.format(Date(step.start))}$span", style = Type.small, color = palette.muted)
        Text(
            "${step.readings} ${if (step.readings == 1) "reading" else "readings"}, strongest ${signalWord(
                step.strongestRssi,
            ).lowercase()}" +
                (step.accuracy?.let { " · location ±${it.toInt()} m" } ?: ""),
            style = Type.small,
            color = palette.muted,
        )
    }
}
