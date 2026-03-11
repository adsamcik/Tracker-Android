package com.adsamcik.tracker.preference.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun DialogListPreference(
	title: String,
	currentValue: String,
	entries: List<String>,
	entryValues: List<String>,
	onValueChange: (Int) -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
) {
	var showDialog by remember { mutableStateOf(false) }
	val selectedIndex = entryValues.indexOf(currentValue).coerceAtLeast(0)

	SettingsRow(
		title = title,
		subtitle = subtitle ?: entries.getOrNull(selectedIndex) ?: currentValue,
		modifier = modifier,
		onClick = { showDialog = true },
	)

	if (showDialog) {
		var tempSelection by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }
		AlertDialog(
			onDismissRequest = { showDialog = false },
			title = { Text(text = title) },
			text = {
				LazyColumn {
					itemsIndexed(entries) { index, item ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.selectable(
									selected = index == tempSelection,
									onClick = { tempSelection = index },
									role = Role.RadioButton,
								)
								.padding(vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							RadioButton(selected = index == tempSelection, onClick = null)
							Text(text = item, modifier = Modifier.padding(start = 12.dp))
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = {
					onValueChange(tempSelection)
					showDialog = false
				}) {
					Text(text = stringResource(android.R.string.ok))
				}
			},
			dismissButton = {
				TextButton(onClick = { showDialog = false }) {
					Text(text = stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

@Composable
private fun SettingsRow(
	title: String,
	subtitle: String,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) {
	TextButton(
		modifier = modifier.fillMaxWidth(),
		onClick = onClick,
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Text(text = title)
			Text(text = subtitle)
		}
	}
}
