package dev.rishikesh.deja.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rishikesh.deja.R
import dev.rishikesh.deja.data.Category
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Two meaningful colours only: blue for "back again", red for trackers. Everything else is ink. */
@Immutable
data class Palette(
    val background: Color,
    val surface: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentSoft: Color,
    val alert: Color,
    val alertSoft: Color,
    val neutralSoft: Color,
)

private const val THEME_FADE_MS = 300
private const val SLIDE_MS = 220

/** Between a screen title and its subtitle. */
val HeadingGap = 6.dp

/** Between any label and the text that explains it. */
val TextGap = 3.dp

private val Light = Palette(
    background = Color(0xFFF6F6F3),
    surface = Color(0xFFFFFFFF),
    line = Color(0xFFE6E5E0),
    ink = Color(0xFF15171A),
    muted = Color(0xFF666B72),
    faint = Color(0xFFA6AAAF),
    accent = Color(0xFF2450E6),
    accentSoft = Color(0xFFE9EEFD),
    alert = Color(0xFFC8283A),
    alertSoft = Color(0xFFFBEBED),
    neutralSoft = Color(0xFFF0EFEB),
)

private val Dark = Palette(
    background = Color(0xFF101113),
    surface = Color(0xFF18191C),
    line = Color(0xFF2A2B2F),
    ink = Color(0xFFF2F2EF),
    muted = Color(0xFF9CA0A7),
    faint = Color(0xFF62666C),
    accent = Color(0xFF6C8CFA),
    accentSoft = Color(0xFF1E2640),
    alert = Color(0xFFF0505C),
    alertSoft = Color(0xFF38191E),
    neutralSoft = Color(0xFF232427),
)

val LocalPalette = compositionLocalOf { Light }

private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600))

private fun style(size: TextUnit, weight: Int, tracking: TextUnit = 0.sp, line: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontFamily = Inter,
        fontSize = size,
        fontWeight = FontWeight(weight),
        letterSpacing = tracking,
        lineHeight = line,
        fontFeatureSettings = "tnum, cv11",
    )

object Type {
    val display = style(32.sp, 600, (-0.7).sp, 38.sp)
    val title = style(19.sp, 600, (-0.25).sp, 25.sp)
    val body = style(15.sp, 500, (-0.1).sp, 20.sp)
    val text = style(15.sp, 400, (-0.1).sp, 22.sp)
    val small = style(13.sp, 400, 0.sp, 18.sp)
    val label = style(13.sp, 500, 0.sp, 18.sp)
    val micro = style(11.sp, 500, 0.1.sp, 14.sp)
    val number = style(22.sp, 600, (-0.4).sp, 28.sp)
}

/** Fades between themes rather than snapping. Android shortens this if animations are turned off. */
@Composable
private fun animatedPalette(target: Palette): Palette {
    @Composable
    fun fade(color: Color) = animateColorAsState(color, tween(THEME_FADE_MS), label = "theme").value
    return Palette(
        background = fade(target.background),
        surface = fade(target.surface),
        line = fade(target.line),
        ink = fade(target.ink),
        muted = fade(target.muted),
        faint = fade(target.faint),
        accent = fade(target.accent),
        accentSoft = fade(target.accentSoft),
        alert = fade(target.alert),
        alertSoft = fade(target.alertSoft),
        neutralSoft = fade(target.neutralSoft),
    )
}

@Composable
fun DejaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = animatedPalette(if (dark) Dark else Light)
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            surface = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            onSurface = palette.ink,
            onSurfaceVariant = palette.muted,
            outline = palette.line,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            surface = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            onSurface = palette.ink,
            onSurfaceVariant = palette.muted,
            outline = palette.line,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val Category.icon: ImageVector
    get() = when (this) {
        Category.TRACKER -> Icons.Outlined.Sell
        Category.AUDIO -> Icons.Outlined.Headphones
        Category.WEARABLE -> Icons.Outlined.Watch
        Category.VEHICLE -> Icons.Outlined.DirectionsCar
        Category.TV -> Icons.Outlined.Tv
        Category.NETWORK -> Icons.Outlined.Wifi
        Category.BEACON -> Icons.Outlined.Sensors
        Category.FIND_MY -> Icons.Outlined.TravelExplore
        Category.COMPUTER -> Icons.Outlined.Laptop
        Category.TABLET -> Icons.Outlined.Tablet
        Category.PHONE -> Icons.Outlined.Smartphone
        Category.INPUT -> Icons.Outlined.Keyboard
        Category.UNKNOWN -> Icons.Outlined.Bluetooth
    }

val Category.label: String
    get() = when (this) {
        Category.TRACKER -> "Tracker"
        Category.AUDIO -> "Audio"
        Category.WEARABLE -> "Wearable"
        Category.VEHICLE -> "Vehicle"
        Category.TV -> "Display"
        Category.NETWORK -> "Wi-Fi network"
        Category.BEACON -> "Beacon"
        Category.FIND_MY -> "Finding-network broadcast"
        Category.COMPUTER -> "Computer"
        Category.TABLET -> "Tablet"
        Category.PHONE -> "Phone"
        Category.INPUT -> "Keyboard or mouse"
        Category.UNKNOWN -> "Bluetooth device"
    }

/** The wall clock, ticking slowly enough that "20 s ago" stays honest without constant recomposition. */
@Composable
fun rememberNow(periodMs: Long = 5_000): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        delay(periodMs)
        value = System.currentTimeMillis()
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, background: Color, size: Dp = 38.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** The shared list row, used on every screen so the app reads as one system. */
@Composable
fun ListRow(
    icon: ImageVector,
    tint: Color,
    badge: Color,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base.semantics(mergeDescendants = true) {})
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, badge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = LocalPalette.current.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(TextGap))
                Text(subtitle, style = Type.small, color = LocalPalette.current.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 66.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(LocalPalette.current.line))
}

/** A white group of rows on the paper background. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalPalette.current.surface),
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.label,
        color = LocalPalette.current.muted,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp).semantics { heading() },
    )
}

@Composable
fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = Type.number, color = LocalPalette.current.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(TextGap))
        Text(label, style = Type.small, color = LocalPalette.current.muted, maxLines = 2)
    }
}

/**
 * Keeps a 48dp touch target around a smaller visual. Leave [active] null for a plain button: only
 * a real toggle should be announced as selected or not selected.
 */
@Composable
fun Pill(
    text: String,
    icon: ImageVector? = null,
    active: Boolean? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val on = active == true
    val base = modifier
        .minimumInteractiveComponentSize()
        .clip(CircleShape)
        .background(if (on) palette.ink else palette.surface)
    Row(
        (if (active == null) base.clickable(role = Role.Button, onClick = onClick) else base.selectable(selected = on, role = Role.Button, onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (on) palette.background else palette.ink
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.label, color = color)
    }
}

fun signalWord(rssi: Int): String = when {
    rssi >= -55 -> "Very strong"
    rssi >= -67 -> "Strong"
    rssi >= -80 -> "Moderate"
    else -> "Weak"
}

/** Four-step signal strength, so nobody has to read dBm. It measures signal, not distance. */
@Composable
fun SignalBars(rssi: Int) {
    val palette = LocalPalette.current
    val level = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val spoken = "${signalWord(rssi)} signal"
    Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.Bottom) {
        repeat(4) { i ->
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .width(3.dp)
                    .height((5 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < level) palette.ink else palette.line),
            )
        }
    }
}

fun ago(then: Long, now: Long): String {
    val minutes = ((now - then) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1440 -> "${minutes / 60} h ago"
        minutes < 2880 -> "Yesterday"
        else -> "${minutes / 1440} days ago"
    }
}

/** How recently something was heard, to the second, for live lists. */
fun heard(then: Long, now: Long): String {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 10 -> "heard just now"
        seconds < 60 -> "heard $seconds s ago"
        else -> "heard ${seconds / 60} min ago"
    }
}

/** A screen title. Marked as a heading so TalkBack users can jump between sections. */
@Composable
fun Header(title: String, subtitle: String?, trailing: @Composable (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.display, color = palette.ink, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Spacer(Modifier.height(HeadingGap))
                Text(subtitle, style = Type.small, color = palette.muted)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier
            .padding(top = 4.dp, bottom = 4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = LocalPalette.current.ink)
    }
}

/** A short status message with an optional fix. Red only when something needs the person. */
@Composable
fun Banner(
    title: String,
    body: String?,
    icon: ImageVector,
    alert: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val base = Modifier
        .fillMaxWidth()
        .padding(bottom = 10.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(if (alert) palette.alertSoft else palette.surface)
    Column((if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (alert) palette.alert else palette.ink, if (alert) palette.surface else palette.neutralSoft)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.body, color = palette.ink)
                if (body != null) {
                    Spacer(Modifier.height(TextGap))
                    Text(body, style = Type.small, color = palette.muted)
                }
            }
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(6.dp))
            Pill(action, onClick = onAction)
        }
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.small, color = LocalPalette.current.muted, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
fun GroupText(text: String) {
    Text(text, style = Type.text, color = LocalPalette.current.muted, modifier = Modifier.padding(16.dp))
}

@Composable
fun LoadingState() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalPalette.current.accent, modifier = Modifier.semantics { contentDescription = "Loading" })
    }
}

@Composable
fun ErrorState(message: String) {
    Banner("Something went wrong", message, Icons.Outlined.ErrorOutline, alert = true)
}

/** A row that opens the next page, with the position so the list never looks like the whole story. */
@Composable
fun LoadMore(shown: Int, total: Int, onMore: () -> Unit) {
    if (total <= 0) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Note("Showing %,d of %,d".format(shown, total))
        if (shown < total) Pill("Show more", onClick = onMore)
    }
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = palette.ink)
            if (detail != null) {
                Spacer(Modifier.height(TextGap))
                Text(detail, style = Type.small, color = palette.muted)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One choice from a few, with a highlight that slides to it. The highlight is drawn behind the row
 * rather than laid out, so the segments can grow taller with large text.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    onSelect: (T) -> Unit,
) {
    val palette = LocalPalette.current
    val position by animateFloatAsState(options.indexOf(selected).toFloat(), tween(SLIDE_MS), label = "segment")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.neutralSoft)
            .drawBehind {
                val width = size.width / options.size
                drawRoundRect(
                    palette.ink,
                    topLeft = Offset(position * width + 3.dp.toPx(), 3.dp.toPx()),
                    size = Size(width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                    cornerRadius = CornerRadius(9.dp.toPx()),
                )
            }
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val chosen = option == selected
            val color by animateColorAsState(if (chosen) palette.background else palette.ink, tween(SLIDE_MS), label = "segmentText")
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = chosen, role = Role.RadioButton) { onSelect(option) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon(option), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), style = Type.label, color = color, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/** A setting with a few choices. */
@Composable
fun <T> ChoiceRow(
    title: String,
    detail: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    icon: ((T) -> ImageVector)? = null,
    onSelect: (T) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = Type.body, color = palette.ink)
        if (detail != null) {
            Spacer(Modifier.height(TextGap))
            Text(detail, style = Type.small, color = palette.muted)
        }
        Spacer(Modifier.height(12.dp))
        Segmented(options, selected, label, icon = icon, onSelect = onSelect)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, style = Type.title) },
        text = { Text(body, style = Type.text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = Type.label) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", style = Type.label) } },
    )
}

@Composable
fun TextEntryDialog(title: String, initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, style = Type.title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(32) },
                singleLine = true,
                textStyle = Type.text,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Save", style = Type.label) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", style = Type.label) } },
    )
}

/** The three states of anything loaded from the database. */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>
    data class Ready<T>(val value: T) : Async<T>
    data class Failed(val message: String) : Async<Nothing>
}

fun <T> Flow<T>.asAsync(): Flow<Async<T>> =
    map<T, Async<T>> { Async.Ready(it) }.catch { emit(Async.Failed("Your saved data could not be read. ${it.message.orEmpty()}")) }

@Composable
fun <T> Flow<T>.collectAsync(): State<Async<T>> =
    remember(this) { asAsync() }.collectAsStateWithLifecycle(Async.Loading)
