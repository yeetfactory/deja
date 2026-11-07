package dev.rishikesh.deja.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
            TextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                textStyle = Type.text,
                placeholder = { Text("Search devices", style = Type.text) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.surface,
                    unfocusedContainerColor = palette.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DeviceFilter.entries) { option -> Pill(option.label, active = option == filter) { filter = option } }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%,d %s".format(total, if (total == 1) "device" else "devices"),
                    style = Type.small,
                    color = palette.muted,
                    modifier = Modifier.weight(1f),
                )
                SortMenu(sort) { sort = it }
            }
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

/** Sorting is used far less than filtering, so it stays out of the way until it's wanted. */
@Composable
private fun SortMenu(sort: DeviceSort, onSort: (DeviceSort) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort: ${sort.label}", style = Type.label, color = palette.ink)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DeviceSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, style = Type.body, color = palette.ink) },
                    trailingIcon = if (option == sort) {
                        { Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = palette.ink, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
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
