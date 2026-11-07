package dev.rishikesh.deja.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.data.AppSettings
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.data.Device
import dev.rishikesh.deja.data.DeviceFilter
import dev.rishikesh.deja.data.DeviceQuery
import dev.rishikesh.deja.data.DeviceSort
import dev.rishikesh.deja.data.Repository

private const val PAGE = 100

@Composable
fun DevicesScreen(repository: Repository, settings: AppSettings, onOpen: (Long) -> Unit) {
    val palette = LocalPalette.current
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.REMEMBERED) }
    var sort by rememberSaveable { mutableStateOf(DeviceSort.RECENT) }
    var search by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(filter, search) { mutableIntStateOf(PAGE) }
    val dao = repository.dao
    val state by remember(filter, sort, search, limit) {
        dao.watchDeviceList(DeviceQuery.list(search, filter, sort, limit))
    }.collectAsync()
    val total by remember(filter, search) {
        dao.watchDeviceCount(DeviceQuery.count(search, filter))
    }.collectAsStateWithLifecycle(0)
    val now = System.currentTimeMillis()

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item {
            Header("Devices", filter.explanation)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                textStyle = Type.text,
                placeholder = { Text("Search names, types and addresses", style = Type.text) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DeviceFilter.entries) { option -> Pill(option.label, active = option == filter) { filter = option } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DeviceSort.entries) { option ->
                    Pill(
                        option.label,
                        icon = if (option == sort) Icons.Outlined.SwapVert else null,
                        active = option == sort,
                    ) { sort = option }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when (val current = state) {
            Async.Loading -> item { LoadingState() }
            is Async.Failed -> item { ErrorState(current.message) }
            is Async.Ready -> {
                val shown = current.value
                if (shown.isEmpty()) {
                    item {
                        Group { GroupText(if (search.isBlank()) "No devices here yet." else "No matches for \"$search\".") }
                    }
                }
                itemsIndexed(shown, key = { _, device -> device.id }) { index, device ->
                    val top = if (index == 0) 16.dp else 0.dp
                    val bottom = if (index == shown.lastIndex) 16.dp else 0.dp
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(top, top, bottom, bottom))
                            .background(palette.surface),
                    ) {
                        if (index > 0) Hairline()
                        val (tint, badge) = when {
                            device.category == Category.TRACKER && !device.owned -> palette.alert to palette.alertSoft
                            device.starred || device.owned -> palette.accent to palette.accentSoft
                            else -> palette.ink to palette.neutralSoft
                        }
                        ListRow(
                            icon = device.category.icon,
                            tint = tint,
                            badge = badge,
                            title = device.title,
                            subtitle = deviceNote(device, settings, now),
                            onClick = { onOpen(device.id) },
                            trailing = { Text(ago(device.lastSeen, now), style = Type.small, color = palette.muted) },
                        )
                    }
                }
                item {
                    LoadMore(shown.size, total) { limit += PAGE }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

private fun deviceNote(device: Device, settings: AppSettings, now: Long): String {
    val days = if (device.temporary) settings.retention.encounterDays else settings.retention.unnamedDays
    val state = when {
        device.owned -> "Mine"
        device.ignored -> "Ignored"
        device.starred -> "Watching"
        device.label != null -> "Saved"
        else -> {
            val left = ((device.lastSeen + days * MatchEngine.DAY - now) / MatchEngine.DAY).coerceAtLeast(0)
            "Forgets in $left ${if (left == 1L) "day" else "days"}"
        }
    }
    val type = if (device.title == device.descriptor) device.category.label else device.descriptor ?: device.category.label
    return "$type · $state"
}
