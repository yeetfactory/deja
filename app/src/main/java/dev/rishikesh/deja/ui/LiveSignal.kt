package dev.rishikesh.deja.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.rishikesh.deja.DejaApp
import dev.rishikesh.deja.core.LocationAccess
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.core.Permission
import dev.rishikesh.deja.core.SignalReading
import dev.rishikesh.deja.core.SignalState
import dev.rishikesh.deja.core.SignalTracker
import dev.rishikesh.deja.core.SystemState
import dev.rishikesh.deja.core.Trend
import dev.rishikesh.deja.data.Device
import dev.rishikesh.deja.data.Radio
import dev.rishikesh.deja.scan.LiveState
import dev.rishikesh.deja.scan.WatchEvent
import dev.rishikesh.deja.scan.watchSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val WATCH_LIMIT_MS = 10 * MatchEngine.MINUTE

/** Leaves a moment before scanning, so flicking through devices doesn't trip Android's scan limit. */
private const val START_DELAY_MS = 400L

private fun blocker(system: SystemState, scanning: Boolean): String? = when {
    !scanning -> "Scanning is paused, so this device isn't being watched."
    system.bluetoothPermission != Permission.GRANTED -> "Needs the Nearby devices permission."
    system.location != LocationAccess.PRECISE -> "Needs precise location."
    !system.locationOn -> "Needs Location turned on."
    !system.bluetoothOn -> "Needs Bluetooth turned on."
    else -> null
}

private fun trendText(trend: Trend) = when (trend) {
    Trend.STRONGER -> "Getting stronger"
    Trend.WEAKER -> "Getting weaker"
    Trend.STEADY -> "Holding steady"
    Trend.UNKNOWN -> "Reading the trend"
}

private fun trendIcon(trend: Trend): ImageVector = when (trend) {
    Trend.STRONGER -> Icons.Outlined.ArrowUpward
    Trend.WEAKER -> Icons.Outlined.ArrowDownward
    Trend.STEADY -> Icons.Outlined.Remove
    Trend.UNKNOWN -> Icons.Outlined.MoreHoriz
}

/** Watches one device for as long as its page is open and visible. Nothing here is stored. */
@Composable
fun LiveSignalCard(device: Device) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val store = (context.applicationContext as DejaApp).settings
    val settings by store.settings.collectAsStateWithLifecycle()
    val system by LiveState.system.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by rememberNow(1_000)

    val tracker = remember(device.key) { SignalTracker() }
    var revision by remember(device.key) { mutableIntStateOf(0) }
    var failure by remember(device.key) { mutableStateOf<String?>(null) }
    var finished by remember(device.key) { mutableStateOf(false) }
    var session by remember(device.key) { mutableIntStateOf(0) }

    val blocked = if (device.radio == Radio.WIFI) null else blocker(system, settings.scanningEnabled)
    val watching = device.radio == Radio.BLE && blocked == null

    LaunchedEffect(device.key, watching, session) {
        if (!watching) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(START_DELAY_MS)
            failure = null
            val completed = withTimeoutOrNull(WATCH_LIMIT_MS) {
                watchSignal(context, device.key.removePrefix("ble:")).collect { event ->
                    when (event) {
                        is WatchEvent.Heard -> {
                            tracker.add(System.currentTimeMillis(), event.rssi)
                            revision++
                        }
                        is WatchEvent.Failed -> failure = event.reason
                    }
                }
            }
            if (completed == null) finished = true
        }
    }

    Group(Modifier.padding(bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Live signal", style = Type.label, color = palette.muted)
            Spacer(Modifier.height(4.dp))
            when {
                device.radio == Radio.WIFI -> {
                    SignalNumber(device.lastRssi)
                    Note(
                        "Android allows only a few Wi-Fi scans every couple of minutes, so networks can't be watched live. This updates with each normal scan.",
                        Modifier.padding(top = 6.dp),
                    )
                }
                blocked != null -> Text(blocked, style = Type.text, color = palette.muted)
                finished -> {
                    Text("Stopped after 10 minutes to save battery.", style = Type.text, color = palette.muted)
                    Pill("Keep watching") {
                        finished = false
                        session++
                    }
                }
                failure != null -> Text("$failure. Trying again when you reopen this page.", style = Type.text, color = palette.muted)
                else -> {
                    // Reading the revision here is what redraws the card for each new reading.
                    val state = remember(revision, now) { tracker.state(now) }
                    Watching(state, now, device.temporary)
                }
            }
        }
    }
}

@Composable
private fun SignalNumber(rssi: Int) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$rssi", style = Type.display, color = palette.ink)
        Spacer(Modifier.width(6.dp))
        Text("dBm · ${signalWord(rssi).lowercase()}", style = Type.small, color = palette.muted, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun Watching(state: SignalState, now: Long, temporary: Boolean) {
    val palette = LocalPalette.current
    val rssi = state.rssi
    val silentFor = if (rssi == null) 0L else now - state.lastHeard
    when {
        rssi == null -> Text("Listening for this device…", style = Type.text, color = palette.muted)
        silentFor > SignalTracker.SILENT_AFTER_MS -> {
            Text("Not heard for ${silentFor / 1000} s", style = Type.body, color = palette.ink)
            Text(
                if (temporary) {
                    "It may be out of range, or it may have changed its private address. If so, this record won't hear it again."
                } else {
                    "It may be out of range, or not advertising right now."
                },
                style = Type.small,
                color = palette.muted,
            )
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SignalNumber(rssi) }
                Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(trendIcon(state.trend), contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(trendText(state.trend), style = Type.label, color = palette.ink)
                }
            }
            Spacer(Modifier.height(8.dp))
            Sparkline(state.history, now)
        }
    }
    if (rssi != null) {
        Note(
            "Stronger usually means closer, but walls and bodies change it too. Live readings aren't saved.",
            Modifier.padding(top = 8.dp),
        )
    }
}

/** The last minute of signal. Stronger is higher. The trend text says the same thing for TalkBack. */
@Composable
private fun Sparkline(history: List<SignalReading>, now: Long) {
    val palette = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().height(44.dp).clearAndSetSemantics { contentDescription = "Graph of the last minute of signal strength" }) {
        drawLine(palette.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (history.size < 2) return@Canvas
        fun x(time: Long) = size.width * (1f - (now - time).coerceIn(0, SignalTracker.HISTORY_MS) / SignalTracker.HISTORY_MS.toFloat())
        fun y(rssi: Int) = size.height * (1f - ((rssi + 100) / 70f).coerceIn(0f, 1f))
        val path = Path()
        history.forEachIndexed { index, reading ->
            if (index == 0) path.moveTo(x(reading.time), y(reading.rssi)) else path.lineTo(x(reading.time), y(reading.rssi))
        }
        drawPath(path, palette.ink, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
