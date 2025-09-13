package com.adsamcik.tracker.shared.utils.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.R as BaseR

/**
 * Compose replacement for MaterialDialog single-choice list dialogs.
 * 
 * @param visible Whether the dialog is shown
 * @param title Dialog title text
 * @param items List of selectable items
 * @param selectedIndex Currently selected item index (-1 for none)
 * @param onItemSelected Called when user selects an item
 * @param onDismiss Called when user dismisses dialog
 * @param confirmLabel Text for confirm button (defaults to "OK")
 * @param dismissLabel Text for dismiss button (defaults to "Cancel")
 */
@Composable
fun SingleChoiceDialog(
    visible: Boolean,
    title: String? = null,
    items: List<String>,
    selectedIndex: Int = -1,
    onItemSelected: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(BaseR.string.generic_ok),
    dismissLabel: String = stringResource(BaseR.string.generic_cancel)
) {
    if (visible) {
        var currentSelection by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }
        
        AlertDialog(
            modifier = Modifier.testTag("singleChoiceDialog"),
            onDismissRequest = onDismiss,
            title = title?.let { { Text(text = it) } },
            text = {
                LazyColumn(
                    modifier = Modifier.selectableGroup()
                ) {
                    itemsIndexed(items) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = currentSelection == index,
                                    onClick = { currentSelection = index },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp)
                                .testTag("choiceItem_$index"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSelection == index,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (currentSelection >= 0) {
                            onItemSelected(currentSelection)
                        }
                        onDismiss()
                    }
                ) {
                    Text(text = confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = dismissLabel)
                }
            }
        )
    }
}
