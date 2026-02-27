package com.adsamcik.tracker.tracker.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.PreferenceDatabase
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// NotificationManagementActivity removed — screen is now a NavHost route.

internal data class UiItem(
	val id: String,
	val titleRes: Int,
	val isInTitle: Boolean,
	val isInContent: Boolean,
)

private fun TrackerNotificationComponent.toUiItem(): UiItem =
	UiItem(id = id, titleRes = titleRes, isInTitle = preference.isInTitle, isInContent = preference.isInContent)

class NotificationManagementViewModel : ViewModel() {
	private val dispatchers = DefaultDispatchersProvider
	// Backing state kept internal; expose as read-only list to callers
	internal var items: List<UiItem> by mutableStateOf(emptyList())
		private set

	// Drag state
	var draggingIndex by mutableStateOf<Int?>(null)
		private set

	fun load(context: android.content.Context) {
		if (items.isNotEmpty()) return
		viewModelScope.launch(dispatchers.default) {
			TrackerNotificationProvider.updatePreferences(context)
			val raw = TrackerNotificationProvider.internalActiveList
				.sortedBy { it.preference.order }
				.onEachIndexed { index, comp ->
					comp.preference = comp.preference.copy(order = index)
				}
			withContext(dispatchers.main) { items = raw.map { it.toUiItem() } }
		}
	}

	fun setDragging(index: Int?) { draggingIndex = index }

	fun moveItem(from: Int, to: Int) {
		if (from == to) return
		val mutable = items.toMutableList()
		val item = mutable.removeAt(from)
		mutable.add(to, item)
		items = mutable
		draggingIndex = to
	}

	fun persistOrder(context: android.content.Context) {
		viewModelScope.launch(dispatchers.io) {
			val dao = PreferenceDatabase.database(context).getNotificationDao()
			val update = items.mapIndexed { index, ui ->
				NotificationPreference(
					id = ui.id,
					order = index,
					isInTitle = ui.isInTitle,
					isInContent = ui.isInContent
				)
			}
			dao.upsert(update)
		}
	}

	fun updateFlags(context: android.content.Context, id: String, inTitle: Boolean, inContent: Boolean) {
		items = items.map { if (it.id == id) it.copy(isInTitle = inTitle, isInContent = inContent) else it }
		viewModelScope.launch(dispatchers.io) {
			val index = items.indexOfFirst { it.id == id }
			if (index >= 0) {
				PreferenceDatabase.database(context).getNotificationDao().upsert(
					NotificationPreference(id, index, inTitle, inContent)
				)
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationManagementRoute(
	onBack: () -> Unit,
) {
	val viewModel: NotificationManagementViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
	val context = androidx.compose.ui.platform.LocalContext.current
	LaunchedEffect(Unit) { viewModel.load(context) }
	val items: List<UiItem> = viewModel.items
	var editTarget by remember { mutableStateOf<UiItem?>(null) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(id = R.string.settings_notification_customize_title)) },
				navigationIcon = {
					IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_navigate_back)) }
				}
			)
		}
	) { padding ->
		if (items.isEmpty()) {
			Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}
		} else {
			val rowHeight = 56.dp
			val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
			LazyColumn(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding)
			) {
				itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
					val isDragging = index == viewModel.draggingIndex
					NotificationItemRow(
						item = item,
						rowHeight = rowHeight,
						isDragging = isDragging,
						onEdit = { editTarget = item },
						modifier = Modifier.pointerInput(items) {
							detectDragGesturesAfterLongPress(
								onDragStart = { viewModel.setDragging(index) },
								onDragEnd = {
									viewModel.setDragging(null)
									viewModel.persistOrder(context)
								},
								onDragCancel = { viewModel.setDragging(null) },
								onDrag = { change, dragAmount ->
									change.consume()
									val current = viewModel.draggingIndex ?: return@detectDragGesturesAfterLongPress
									val offsetY = dragAmount.y
									if (offsetY > rowHeightPx / 2 && current < items.lastIndex) {
										viewModel.moveItem(current, current + 1)
									} else if (offsetY < -rowHeightPx / 2 && current > 0) {
										viewModel.moveItem(current, current - 1)
									}
								}
							)
						}
					)
				}
			}
		}
	}

	val target = editTarget
	if (target != null) {
		var inTitle by remember(target) { mutableStateOf(target.isInTitle) }
		var inContent by remember(target) { mutableStateOf(target.isInContent) }
		AlertDialog(
			onDismissRequest = { editTarget = null },
			confirmButton = {
				TextButton(onClick = {
					viewModel.updateFlags(context, target.id, inTitle, inContent)
					editTarget = null
				}) { Text(stringResource(android.R.string.ok)) }
			},
			dismissButton = {
				TextButton(onClick = { editTarget = null }) { Text(stringResource(android.R.string.cancel)) }
			},
			title = { Text(stringResource(R.string.settings_notification_customize_title)) },
			text = {
				Column {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = inTitle, onCheckedChange = { inTitle = it })
						Text(text = stringResource(R.string.hint_customize_notification_show_in_title))
					}
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = inContent, onCheckedChange = { inContent = it })
						Text(text = stringResource(R.string.hint_customize_notification_show_in_content))
					}
				}
			}
		)
	}
}

@Composable
private fun NotificationItemRow(
	item: UiItem,
	rowHeight: androidx.compose.ui.unit.Dp,
	isDragging: Boolean,
	onEdit: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val bg = if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
	Row(
		modifier
			.fillMaxWidth()
			.height(rowHeight)
			.background(bg)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Icon(Icons.Filled.DragIndicator, contentDescription = "Drag", modifier = Modifier.size(32.dp))
		Spacer(Modifier.width(4.dp))
		Text(
			text = stringResource(id = item.titleRes),
			modifier = Modifier.weight(1f)
		)
		if (item.isInTitle) {
			Icon(painterResource(id = R.drawable.ic_format_title), contentDescription = "Title", modifier = Modifier.size(20.dp))
		}
		if (item.isInContent) {
			Spacer(Modifier.width(4.dp))
			Icon(painterResource(id = R.drawable.ic_subtitles_outline), contentDescription = "Content", modifier = Modifier.size(20.dp))
		}
		Spacer(Modifier.width(8.dp))
		IconButton(onClick = onEdit) {
			Icon(Icons.Filled.Edit, contentDescription = "Edit")
		}
	}
}
