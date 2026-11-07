package dev.rishikesh.deja.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.scan.Blip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val TILT = 0.52f
private const val SHEET_TURN = -0.33f
private const val TWO_PI = (2 * PI).toFloat()
private const val FRESH_MS = 60_000L
private const val FADE_MS = 120_000L

private class Geometry(size: Size) {
    val center = Offset(size.width / 2f, size.height * 0.56f)
    val radius = min(size.width * 0.34f, size.height * 0.74f)

    fun ground(angle: Float, fraction: Float) = Offset(
        center.x + radius * fraction * cos(angle),
        center.y + radius * fraction * sin(angle) * TILT,
    )

    fun height(rssi: Int) = (1f - distanceOf(rssi)) * radius * 0.26f + radius * 0.05f

    fun tip(blip: Blip): Offset =
        ground(angleOf(blip.key), distanceOf(blip.rssi)) - Offset(0f, height(blip.rssi))

    fun sheet(): Path {
        val reach = radius * 1.5f
        return Path().apply {
            repeat(4) { i ->
                val a = SHEET_TURN + PI.toFloat() / 4 + i * PI.toFloat() / 2
                val x = center.x + reach * cos(a)
                val y = center.y + reach * sin(a) * TILT
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}

/** Direction is meaningless: one phone cannot tell where a signal comes from. It only spreads pins out. */
private fun angleOf(key: String): Float = (key.hashCode().toLong() and 0xFFFF) / 65536f * TWO_PI

/** Maps signal strength onto the rings. Walls, bodies and transmit power move this as much as distance does. */
private fun distanceOf(rssi: Int): Float = ((-rssi - 35) / 62f).coerceIn(0.16f, 1f)

private fun Blip.important() = returning || (category == Category.TRACKER && !owned)

/** Pins fade as their last reading ages, so old information does not look current. */
private fun freshness(blip: Blip, now: Long): Float {
    val age = (now - blip.heardAt).coerceAtLeast(0)
    return (1f - (age - FRESH_MS).coerceAtLeast(0) / FADE_MS.toFloat()).coerceIn(0.3f, 1f)
}

/**
 * A sheet lying on a table, with a pin for each device heard. Rings show signal strength only: a
 * pin's direction means nothing, and its ring is not a distance. The caption under it says so.
 */
@Composable
fun SignalField(
    blips: List<Blip>,
    transient: Int,
    listening: Boolean,
    clock: State<Float>,
    now: Long,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val spoken = "Signal field. ${blips.size} devices heard and $transient private addresses. " +
        "Positions do not show direction or distance. The same devices are listed below."
    val measurer = rememberTextMeasurer()
    val currentBlips by rememberUpdatedState(blips)
    val currentOpen by rememberUpdatedState(onOpen)
    val specks = remember { List(40) { Random.nextFloat() * TWO_PI to 0.2f + Random.nextFloat() * 0.8f } }

    Canvas(
        modifier.clearAndSetSemantics { contentDescription = spoken }.pointerInput(Unit) {
            detectTapGestures { tap ->
                val geometry = Geometry(Size(size.width.toFloat(), size.height.toFloat()))
                currentBlips.minByOrNull { (geometry.tip(it) - tap).getDistance() }
                    ?.takeIf { (geometry.tip(it) - tap).getDistance() < 28.dp.toPx() }
                    ?.let { currentOpen(it.deviceId) }
            }
        },
    ) {
        val g = Geometry(size)
        drawSheet(g, palette)

        specks.take(transient).forEach { (angle, fraction) ->
            drawCircle(palette.faint.copy(alpha = 0.55f), 1.dp.toPx(), g.ground(angle, fraction))
        }

        if (listening) {
            val pulse = (clock.value / 3.6f) % 1f
            ellipse(g, pulse, palette.accent.copy(alpha = (1f - pulse) * 0.35f), 1.dp.toPx())
        }

        drawRingLabels(g, palette, measurer)
        val ordered = blips.sortedBy { g.ground(angleOf(it.key), distanceOf(it.rssi)).y }
        ordered.forEach { drawPin(g, it, palette, freshness(it, now)) }
        drawYou(g, palette, measurer)
        ordered.filter { it.important() }.take(3).forEach { drawChip(g, it, palette, measurer) }
    }
}

private fun DrawScope.ellipse(g: Geometry, fraction: Float, color: Color, width: Float, dashed: Boolean = false) {
    val w = g.radius * fraction
    drawOval(
        color,
        topLeft = Offset(g.center.x - w, g.center.y - w * TILT),
        size = Size(w * 2, w * 2 * TILT),
        style = Stroke(
            width,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())) else null,
        ),
    )
}

private fun DrawScope.drawSheet(g: Geometry, palette: Palette) {
    val sheet = g.sheet()
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.surface.toArgb()
            setShadowLayer(26.dp.toPx(), 0f, 14.dp.toPx(), Color.Black.copy(alpha = 0.10f).toArgb())
        }
        canvas.nativeCanvas.drawPath(sheet.asAndroidPath(), paint)
    }
    drawPath(sheet, palette.line, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
    listOf(0.33f, 0.66f).forEach { ellipse(g, it, palette.line, 1.dp.toPx()) }
    ellipse(g, 1f, palette.faint.copy(alpha = 0.6f), 1.dp.toPx(), dashed = true)
}

private fun DrawScope.drawRingLabels(g: Geometry, palette: Palette, measurer: TextMeasurer) {
    val strong = measurer.measure("Stronger", Type.micro)
    val weak = measurer.measure("Weaker", Type.micro)
    drawText(strong, palette.faint, g.ground(PI.toFloat() / 2, 0.33f) + Offset(-strong.size.width / 2f, 2.dp.toPx()))
    drawText(weak, palette.faint, g.ground(PI.toFloat() / 2, 1f) + Offset(-weak.size.width / 2f, 2.dp.toPx()))
}

private fun DrawScope.drawPin(g: Geometry, blip: Blip, palette: Palette, fresh: Float) {
    val base = g.ground(angleOf(blip.key), distanceOf(blip.rssi))
    val tip = base - Offset(0f, g.height(blip.rssi))
    val color = when {
        blip.category == Category.TRACKER -> palette.alert
        blip.returning || blip.starred -> palette.accent
        else -> palette.ink
    }.copy(alpha = fresh)
    drawCircle(palette.faint, 1.5.dp.toPx(), base)
    drawLine(color.copy(alpha = 0.4f), base, tip, 1.dp.toPx(), cap = StrokeCap.Round)

    val r = if (blip.important()) 5.dp.toPx() else 3.5.dp.toPx()
    if (blip.category == Category.NETWORK) {
        val diamond = Path().apply {
            moveTo(tip.x, tip.y - r)
            lineTo(tip.x + r, tip.y)
            lineTo(tip.x, tip.y + r)
            lineTo(tip.x - r, tip.y)
            close()
        }
        drawPath(diamond, palette.surface)
        drawPath(diamond, color, style = Stroke(1.25.dp.toPx(), join = StrokeJoin.Round))
    } else {
        drawCircle(palette.surface, r + 1.5.dp.toPx(), tip)
        drawCircle(color, r, tip)
    }
}

private fun DrawScope.drawYou(g: Geometry, palette: Palette, measurer: TextMeasurer) {
    val tip = g.center - Offset(0f, g.radius * 0.2f)
    drawLine(palette.accent.copy(alpha = 0.7f), g.center, tip, 1.5.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(palette.accent.copy(alpha = 0.16f), 13.dp.toPx(), tip)
    drawCircle(palette.surface, 7.5.dp.toPx(), tip)
    drawCircle(palette.accent, 5.5.dp.toPx(), tip)
    val layout = measurer.measure("You", Type.micro)
    drawText(layout, palette.ink, tip + Offset(-layout.size.width / 2f, -13.dp.toPx() - layout.size.height))
}

private fun DrawScope.drawChip(g: Geometry, blip: Blip, palette: Palette, measurer: TextMeasurer) {
    val tip = g.tip(blip)
    val layout = measurer.measure(blip.title, Type.micro, maxLines = 1)
    val padX = 8.dp.toPx()
    val padY = 4.dp.toPx()
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val x = (tip.x - w / 2).coerceIn(4.dp.toPx(), size.width - w - 4.dp.toPx())
    val y = (tip.y - h - 10.dp.toPx()).coerceAtLeast(0f)
    val fill = if (blip.category == Category.TRACKER) palette.alert else palette.accent
    drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(6.dp.toPx()))
    drawText(layout, palette.surface, Offset(x + padX, y + padY))
}
