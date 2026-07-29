package com.adsamcik.tracker.feature.tracker.notification

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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSetting
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NotificationManagementViewModel @Inject constructor(
	private val settingsRepository: TrackerNotificationSettingsRepository,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {
	internal var items: List<TrackerNotificationSetting> by mutableStateOf(emptyList())
		private set
	private var hasStartedLoading = false

	var draggingIndex by mutableStateOf<Int?>(null)
		private set

	fun load() {
		if (hasStartedLoading) return
		hasStartedLoading = true
		viewModelScope.launch(dispatchers.io) {
			val loadedSettings = settingsRepository.loadSettings()
			withContext(dispatchers.main) {
				items = loadedSettings
			}
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

	fun persistOrder() {
		persist(items)
	}

	fun updateFlags(id: String, inTitle: Boolean, inContent: Boolean) {
		val updatedItems = items.map { item ->
			if (item.id == id) {
				item.copy(isInTitle = inTitle, isInContent = inContent)
			} else {
				item
			}
		}
		items = updatedItems
		persist(updatedItems)
	}

	private fun persist(settings: List<TrackerNotificationSetting>) {
		viewModelScope.launch(dispatchers.io) {
			settingsRepository.saveSettings(settings)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationManagementRoute(
	onBack: () -> Unit,
) {
	val viewModel: NotificationManagementViewModel = hiltViewModel()
	LaunchedEffect(viewModel) { viewModel.load() }
	val items: List<TrackerNotificationSetting> = viewModel.items
	var editTarget by remember { mutableStateOf<TrackerNotificationSetting?>(null) }

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
									viewModel.persistOrder()
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
					viewModel.updateFlags(target.id, inTitle, inContent)
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
	item: TrackerNotificationSetting,
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
