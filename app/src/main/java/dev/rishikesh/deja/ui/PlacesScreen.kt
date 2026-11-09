package dev.rishikesh.deja.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.core.FollowEngine
import dev.rishikesh.deja.core.PlaceClusterer
import dev.rishikesh.deja.data.Place
import dev.rishikesh.deja.data.PlaceRow
import dev.rishikesh.deja.data.Repository
import kotlinx.coroutines.launch

@Composable
fun PlacesScreen(
    repository: Repository,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val dao = repository.dao
    val state by remember { dao.watchPlaceRows() }.collectAsync()
    var selected by remember { mutableStateOf<PlaceRow?>(null) }
    var renaming by remember { mutableStateOf<PlaceRow?>(null) }
    var merging by remember { mutableStateOf<PlaceRow?>(null) }
    val now = System.currentTimeMillis()

    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item { Header("Places", "Where Déjà has heard devices. Tap one to rename or merge it.") }
        when (val current = state) {
            Async.Loading -> {
                item { LoadingState() }
            }

            is Async.Failed -> {
                item { ErrorState(current.message) }
            }

            is Async.Ready -> {
                val rows = current.value
                val duplicates = PlaceClusterer.duplicates(rows.map { Place(id = it.id, name = it.name, lat = it.lat, lng = it.lng) })
                item {
                    duplicates.take(3).forEach { (a, b) ->
                        Banner(
                            "${a.name} and ${b.name} may be one place",
                            "They are about ${FollowEngine.distance(PlaceClusterer.metersBetween(a, b))} apart.",
                            Icons.AutoMirrored.Outlined.CallMerge,
                            action = "Merge into ${a.name}",
                            onAction = { scope.launch { repository.mergePlaces(b.id, a.id) } },
                        )
                    }
                    Group {
                        if (rows.isEmpty()) {
                            GroupText(
                                "No places yet. A place appears once two location fixes agree you've stopped somewhere.",
                            )
                        }
                        rows.forEachIndexed { index, row ->
                            if (index > 0) Hairline()
                            ListRow(
                                icon = Icons.Outlined.Place,
                                tint = palette.ink,
                                badge = palette.neutralSoft,
                                title = row.name,
                                subtitle =
                                    "%,d sightings · %d devices".format(row.sightings, row.devices) +
                                        (row.lastSeen?.let { " · ${ago(it, now).lowercase()}" } ?: ""),
                                onClick = { selected = row },
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(row.name, style = Type.title) },
            text = { Text("%,d sightings of %d devices.".format(row.sightings, row.devices), style = Type.text) },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    renaming = row
                }) { Text("Rename", style = Type.label) }
            },
            dismissButton = {
                TextButton(onClick = {
                    selected = null
                    merging = row
                }) { Text("Merge into…", style = Type.label) }
            },
        )
    }

    renaming?.let { row ->
        TextEntryDialog(
            "Name this place",
            row.name,
            onSave = {
                if (it.isNotBlank()) scope.launch { dao.renamePlace(row.id, it) }
                renaming = null
            },
            onCancel = { renaming = null },
        )
    }

    merging?.let { row ->
        val others = (state as? Async.Ready)?.value.orEmpty().filter { it.id != row.id }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge ${row.name} into", style = Type.title) },
            text = {
                LazyColumn {
                    if (others.isEmpty()) item { Text("There is no other place to merge into.", style = Type.text) }
                    others.forEach { other ->
                        item(key = other.id) {
                            TextButton(onClick = {
                                merging = null
                                scope.launch { repository.mergePlaces(row.id, other.id) }
                            }) { Text(other.name, style = Type.body) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text("Cancel", style = Type.label) } },
        )
    }
}
