package dev.rishikesh.deja.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.scan.Blip

private const val WEAKEST = -100f
private const val STRONGEST = -35f
private const val LANES = 3
private const val FRESH_MS = 60_000L
private const val FADE_MS = 120_000L

/** Signal strength, and nothing else, so nothing pretends to show direction. */
private fun position(rssi: Int) = ((rssi - WEAKEST) / (STRONGEST - WEAKEST)).coerceIn(0f, 1f)

/** Pulls dots with the same signal apart. Which lane a dot sits in means nothing. */
private fun lane(deviceId: Long) = deviceId.mod(LANES.toLong()).toInt()

private fun freshness(
    blip: Blip,
    now: Long,
): Float {
    val age = (now - blip.heardAt).coerceAtLeast(0)
    return (1f - (age - FRESH_MS).coerceAtLeast(0) / FADE_MS.toFloat()).coerceIn(0.3f, 1f)
}

private fun center(
    blip: Blip,
    size: Size,
) = Offset(
    x = position(blip.rssi) * size.width,
    y = size.height * (lane(blip.deviceId) + 1f) / (LANES + 1f),
)

/**
 * One line from weaker to stronger, with a dot for each device. It only redraws when new readings
 * arrive, not every frame. Dots fade as their last reading ages, so old information looks old.
 */
@Composable
fun SignalStrip(
    blips: List<Blip>,
    now: Long,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val currentBlips by rememberUpdatedState(blips)
    val currentOpen by rememberUpdatedState(onOpen)
    val strongest = blips.maxByOrNull { it.rssi }
    val spoken =
        if (strongest == null) {
            "Signal strip. Nothing heard yet."
        } else {
            "Signal strip. ${blips.size} heard. The strongest is ${strongest.title}. The same devices are listed below."
        }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clearAndSetSemantics { contentDescription = spoken }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val area = Size(size.width.toFloat(), size.height.toFloat())
                        currentBlips
                            .minByOrNull { (center(it, area) - tap).getDistance() }
                            ?.takeIf { (center(it, area) - tap).getDistance() < 24.dp.toPx() }
                            ?.let { currentOpen(it.deviceId) }
                    }
                },
        ) {
            val middle = size.height / 2
            drawLine(palette.line, Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
            // Quiet ones first, so what matters is drawn on top.
            blips.sortedBy { !it.temporary }.forEach { blip ->
                val tracker = blip.category == Category.TRACKER && !blip.owned
                val color =
                    when {
                        tracker -> palette.alert
                        blip.temporary -> palette.faint
                        else -> palette.ink
                    }.copy(alpha = freshness(blip, now))
                val radius = if (blip.temporary) 3.dp.toPx() else 5.dp.toPx()
                drawCircle(palette.surface, radius + 1.5.dp.toPx(), center(blip, size))
                drawCircle(color, radius, center(blip, size))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Weaker", style = Type.micro, color = palette.faint)
            Text("Signal, not distance", style = Type.micro, color = palette.faint)
            Text("Stronger", style = Type.micro, color = palette.faint)
        }
    }
}
