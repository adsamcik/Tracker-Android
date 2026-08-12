package com.adsamcik.tracker.app.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Contract: Displays a list selection dialog when clicked. Shows current value.
 * Inputs: title, current value index, list of option titles & values, onValueChange callback
 * Outputs: Calls onValueChange(selectedIndex) when user confirms new selection
 * Failure modes: None (UI component only)
 */
@Composable
fun DialogListPreference(
    title: String,
    currentValue: String,
    entries: List<String>,
    entryValues: List<String>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentIndex = entryValues.indexOf(currentValue).coerceAtLeast(0)

    SettingsItemWithValue(
        title = title,
        value = entries.getOrNull(currentIndex) ?: currentValue,
        subtitle = subtitle,
        icon = icon,
        onClick = { showDialog = true },
        modifier = modifier
    )

    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            options = entries,
            selectedIndex = currentIndex,
            onConfirm = { selectedIndex ->
                onValueChange(selectedIndex)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * Contract: Material 3 AlertDialog with single-choice radio button list.
 * Inputs: title, list of options, currently selected index, callbacks
 * Outputs: Calls onConfirm(selectedIndex) when user confirms
 * Failure modes: None (UI component only)
 */
@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tempSelectedIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }
    val coroutineScope = rememberCoroutineScope()

    fun dismissSafely() {
        coroutineScope.launch {
            withFrameNanos { }
            onDismiss()
        }
    }

    fun confirmSafely() {
        coroutineScope.launch {
            withFrameNanos { }
            onConfirm(tempSelectedIndex)
        }
    }

    AlertDialog(
        onDismissRequest = ::dismissSafely,
        properties = DialogProperties(dismissOnClickOutside = true),
        title = { Text(title) },
        text = {
            LazyColumn {
                itemsIndexed(options) { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == tempSelectedIndex,
                                onClick = { tempSelectedIndex = index },
                                role = Role.RadioButton
                            )
                            .heightIn(min = 48.dp)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = index == tempSelectedIndex,
                            onClick = null // Click handled by Row
                        )
                        Text(
                            text = option,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirmSafely) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismissSafely) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
