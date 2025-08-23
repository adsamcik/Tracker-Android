package com.adsamcik.tracker.map.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.MapEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheet(
    registry: LayerRegistry,
    store: MapStore,
) {
    val sheetState = rememberModalBottomSheetState()
    var open by remember { mutableStateOf(true) }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState
        ) {
            val layers = remember { registry.getAllLayers() }
            val isFollowing by store.state.collectAsState()
            Column(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { store.dispatch(MapEvent.ToggleFollow) }) {
                    val enabled = isFollowing.isFollowing
                    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(Icons.Filled.MyLocation, contentDescription = "Toggle follow", tint = tint)
                }
                Text("Layers")
                LazyColumn(contentPadding = PaddingValues(8.dp)) {
                    items(layers, key = { it.id }) { d ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(onClick = { store.dispatch(MapEvent.SelectLayer(d.id)) }) {
                                Text(text = d.id)
                            }
                        }
                    }
                }
            }
        }
    }
}
