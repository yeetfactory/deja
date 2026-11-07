package dev.rishikesh.deja.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.data.Delivery
import dev.rishikesh.deja.data.EventRow
import dev.rishikesh.deja.data.EventType
import dev.rishikesh.deja.data.Repository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PAGE = 50

private enum class Range(val label: String) {
    ALL("All time"),
    TODAY("Today"),
    WEEK("7 days"),
    MONTH("30 days");

    fun start(now: Long): Long = when (this) {
        ALL -> 0L
        TODAY -> Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        WEEK -> now - 7 * MatchEngine.DAY
        MONTH -> now - 30 * MatchEngine.DAY
    }
}

val EventType.icon: ImageVector
    get() = when (this) {
        EventType.FOLLOWING -> Icons.Outlined.WarningAmber
        EventType.RETURNED -> Icons.Outlined.History
        EventType.NEW_TRACKER -> Icons.Outlined.Sell
        EventType.NEW_FIND_MY -> Icons.Outlined.TravelExplore
    }

fun eventTitle(event: EventRow) = when (event.type) {
    EventType.FOLLOWING -> "${event.deviceName} may be following you"
    EventType.RETURNED -> "${event.deviceName} is back"
    EventType.NEW_TRACKER -> "New tracker: ${event.deviceName}"
    EventType.NEW_FIND_MY -> "Find My broadcast noticed"
}

fun route(event: EventRow): String = when {
    event.fromPlace != null && event.toPlace != null && event.fromPlace != event.toPlace ->
        "${event.fromPlace} → ${event.toPlace}"
    else -> event.toPlace ?: event.fromPlace ?: ""
}

/** Eligible and shown are different things, and the user deserves to know which happened. */
fun deliveryText(delivery: Delivery): String? = when (delivery) {
    Delivery.NOT_ELIGIBLE -> null
    Delivery.PENDING -> "Notification not confirmed"
    Delivery.POSTED -> "Notified"
    Delivery.DISABLED_IN_SETTINGS -> "Not notified: this alert is off in Settings"
    Delivery.BLOCKED_PERMISSION -> "Not notified: notifications are off"
    Delivery.BLOCKED_CHANNEL -> "Not notified: the Pings category is off"
    Delivery.UNKNOWN -> "Notification was attempted, result not recorded"
}

private fun dayLabel(time: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(day, today) -> "Today"
        same(day, yesterday) -> "Yesterday"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(time))
    }
}

@Composable
fun ActivityScreen(repository: Repository, onOpenEvent: (Long) -> Unit) {
    val palette = LocalPalette.current
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var limit by rememberSaveable(range) { mutableIntStateOf(PAGE) }
    // The range is fixed when chosen. A moving end would restart the query on every tick.
    val from = remember(range) { range.start(System.currentTimeMillis()) }
    val state by remember(from, limit) { repository.dao.watchEvents(from, Long.MAX_VALUE, limit) }.collectAsync()
    val total by remember(from) { repository.dao.watchEventCount(from, Long.MAX_VALUE) }.collectAsStateWithLifecycle(0)
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item {
            Header("Activity", "%,d %s".format(total, if (total == 1) "event" else "events"))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Range.entries) { option -> Pill(option.label, active = option == range) { range = option } }
            }
            Spacer(Modifier.height(10.dp))
        }
        when (val current = state) {
            Async.Loading -> item { LoadingState() }
            is Async.Failed -> item { ErrorState(current.message) }
            is Async.Ready -> {
                val events = current.value
                if (events.isEmpty()) {
                    item {
                        Group {
                            GroupText(
                                if (range == Range.ALL) {
                                    "Nothing yet. Returning devices and trackers will show up here."
                                } else {
                                    "Nothing in this period. Try a longer one."
                                },
                            )
                        }
                    }
                }
                val days = events.groupBy { dayLabel(it.time) }.toList()
                items(days, key = { it.first }) { (day, rows) ->
                    SectionLabel(day)
                    Group {
                        rows.forEachIndexed { index, event ->
                            if (index > 0) Hairline()
                            val alert = event.type == EventType.FOLLOWING || event.type == EventType.NEW_TRACKER
                            val quiet = event.dismissed || !alert
                            ListRow(
                                icon = event.type.icon,
                                tint = if (quiet) palette.accent else palette.alert,
                                badge = if (quiet) palette.accentSoft else palette.alertSoft,
                                title = eventTitle(event),
                                subtitle = listOfNotNull(
                                    route(event).ifEmpty { null },
                                    deliveryText(event.delivery),
                                    if (event.dismissed) "Dismissed" else null,
                                ).joinToString(" · ").ifEmpty { null },
                                onClick = { onOpenEvent(event.id) },
                                trailing = { Text(time.format(Date(event.time)), style = Type.small, color = palette.muted) },
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                item {
                    LoadMore(events.size, total) { limit += PAGE }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
