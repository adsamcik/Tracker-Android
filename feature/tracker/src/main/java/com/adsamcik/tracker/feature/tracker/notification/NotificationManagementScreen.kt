package com.adsamcik.tracker.feature.tracker.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSetting
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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
	private var isLoading = false
	internal var loadFailed by mutableStateOf(false)
		private set
	internal var saveFailed by mutableStateOf(false)
		private set
	private var revision = 0L
	private val saves = Channel<Pair<Long, List<TrackerNotificationSetting>>>(Channel.CONFLATED)

	init {
		viewModelScope.launch(dispatchers.io) {
			for ((savedRevision, settings) in saves) {
				try {
					settingsRepository.saveSettings(settings)
					withContext(dispatchers.main) {
						if (savedRevision == revision) saveFailed = false
					}
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					withContext(dispatchers.main) {
						if (savedRevision == revision) saveFailed = true
					}
				}
			}
		}
	}

	var draggingIndex by mutableStateOf<Int?>(null)
		private set

	fun load() {
		if (isLoading || items.isNotEmpty()) return
		isLoading = true
		loadFailed = false
		viewModelScope.launch(dispatchers.io) {
			try {
				val loadedSettings = settingsRepository.loadSettings()
				check(loadedSettings.isNotEmpty()) { "Notification settings catalog is empty" }
				withContext(dispatchers.main) {
					items = loadedSettings
					isLoading = false
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				withContext(dispatchers.main) {
					isLoading = false
					loadFailed = true
				}
			}
		}
	}

	fun setDragging(index: Int?) { draggingIndex = index }

	fun moveItem(from: Int, to: Int) {
		if (from == to || from !in items.indices || to !in items.indices) return
		val mutable = items.toMutableList()
		val item = mutable.removeAt(from)
		mutable.add(to, item)
		items = mutable
		draggingIndex = to
	}

	fun persistOrder() {
		persist(items)
	}

	fun moveItemAndPersist(from: Int, to: Int) {
		if (from == to || from !in items.indices || to !in items.indices) return
		moveItem(from, to)
		persistOrder()
		draggingIndex = null
	}

	fun retrySave() {
		if (saveFailed) persist(items)
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
		revision += 1
		saveFailed = false
		saves.trySend(revision to settings)
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
				if (viewModel.loadFailed) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(stringResource(R.string.settings_notification_load_failed))
						TextButton(onClick = viewModel::load) { Text(stringResource(R.string.settings_notification_retry)) }
					}
				} else {
					CircularProgressIndicator()
				}
			}
		} else {
			val rowHeight = 72.dp
			val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
			LazyColumn(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding),
				contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
			) {
				if (viewModel.saveFailed) {
					item {
						Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
							Text(stringResource(R.string.settings_notification_save_failed), modifier = Modifier.weight(1f))
							TextButton(onClick = viewModel::retrySave) { Text(stringResource(R.string.settings_notification_retry)) }
						}
					}
				}
				item {
					Text(
						text = stringResource(R.string.settings_notification_customize_summary),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
					)
					Card(
						modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
						colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
					) {
						Column {
							items.forEachIndexed { index, item ->
								val isDragging = index == viewModel.draggingIndex
								NotificationItemRow(
									item = item,
									rowHeight = rowHeight,
									isDragging = isDragging,
									onEdit = { editTarget = item },
									canMoveUp = index > 0,
									canMoveDown = index < items.lastIndex,
									onMoveUp = { viewModel.moveItemAndPersist(index, index - 1) },
									onMoveDown = { viewModel.moveItemAndPersist(index, index + 1) },
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
												if (dragAmount.y > rowHeightPx / 2 && current < items.lastIndex) {
													viewModel.moveItem(current, current + 1)
												} else if (dragAmount.y < -rowHeightPx / 2 && current > 0) {
													viewModel.moveItem(current, current - 1)
												}
											},
										)
									},
								)
								if (index != items.lastIndex) {
									HorizontalDivider(
										modifier = Modifier.padding(start = 68.dp),
										color = MaterialTheme.colorScheme.outlineVariant,
									)
									}
							}
						}
					}
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
internal fun NotificationItemRow(
	item: TrackerNotificationSetting,
	rowHeight: androidx.compose.ui.unit.Dp,
	isDragging: Boolean,
	onEdit: () -> Unit,
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	onMoveUp: () -> Unit,
	onMoveDown: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val bg = if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
	val moveUpLabel = stringResource(R.string.settings_notification_move_up)
	val moveDownLabel = stringResource(R.string.settings_notification_move_down)
	Row(
		modifier
			.testTag("notification_item_${item.id}")
			.semantics {
				customActions = buildList {
					if (canMoveUp) add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
					if (canMoveDown) add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
				}
			}
			.fillMaxWidth()
			.heightIn(min = rowHeight)
			.background(bg)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Surface(
			shape = RoundedCornerShape(12.dp),
			color = MaterialTheme.colorScheme.primaryContainer,
			contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
			modifier = Modifier.size(40.dp),
		) {
			Box(contentAlignment = Alignment.Center) {
				Icon(Icons.Filled.DragIndicator, contentDescription = null, modifier = Modifier.size(22.dp))
			}
		}
		Spacer(Modifier.width(12.dp))
		Text(
			text = stringResource(id = item.titleRes),
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.weight(1f),
		)
		if (item.isInTitle) {
			Icon(painterResource(id = R.drawable.ic_format_title), contentDescription = stringResource(R.string.hint_customize_notification_show_in_title), modifier = Modifier.size(20.dp))
		}
		if (item.isInContent) {
			Spacer(Modifier.width(4.dp))
			Icon(painterResource(id = R.drawable.ic_subtitles_outline), contentDescription = stringResource(R.string.hint_customize_notification_show_in_content), modifier = Modifier.size(20.dp))
		}
		Spacer(Modifier.width(8.dp))
		IconButton(onClick = onEdit) {
			Icon(Icons.Filled.Edit, contentDescription = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_edit))
		}
	}
}
