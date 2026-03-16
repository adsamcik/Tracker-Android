package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.data.ResolvedWidget
import kotlin.math.roundToInt

/**
 * Modal bottom sheet for customizing dashboard widget order and visibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizeDashboardSheet(
    resolvedWidgets: List<ResolvedWidget>,
    onReorder: (List<String>) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items = remember(resolvedWidgets) { resolvedWidgets.toMutableStateList() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val rowHeight = 64.dp
    val reorderThresholdPx = 40.dp

    LaunchedEffect(resolvedWidgets) {
        items.clear()
        items.addAll(resolvedWidgets)
        draggingIndex = -1
        dragOffsetPx = 0f
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.dashboard_customize_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            items.forEachIndexed { index, resolved ->
                val widgetTitle = stringResource(resolved.widget.titleRes)
                val isDragging = draggingIndex == index

                WidgetReorderRow(
                    title = widgetTitle,
                    isVisible = resolved.visible,
                    isDragging = isDragging,
                    onToggleVisibility = {
                        items[index] = resolved.copy(visible = !resolved.visible)
                        onToggleVisibility(resolved.widget.id)
                    },
                    dragHandleModifier = Modifier.pointerInput(index, items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetPx = 0f
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffsetPx = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetPx = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val currentIndex = draggingIndex
                                if (currentIndex !in items.indices) return@detectDragGesturesAfterLongPress

                                dragOffsetPx += dragAmount.y
                                val threshold = reorderThresholdPx.toPx()

                                when {
                                    dragOffsetPx > threshold && currentIndex < items.lastIndex -> {
                                        val movedItem = items.removeAt(currentIndex)
                                        items.add(currentIndex + 1, movedItem)
                                        draggingIndex = currentIndex + 1
                                        dragOffsetPx -= rowHeight.toPx()
                                        onReorder(items.map { it.widget.id })
                                    }

                                    dragOffsetPx < -threshold && currentIndex > 0 -> {
                                        val movedItem = items.removeAt(currentIndex)
                                        items.add(currentIndex - 1, movedItem)
                                        draggingIndex = currentIndex - 1
                                        dragOffsetPx += rowHeight.toPx()
                                        onReorder(items.map { it.widget.id })
                                    }
                                }
                            },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = if (isDragging) dragOffsetPx.roundToInt() else 0,
                            )
                        }
                        .zIndex(if (isDragging) 1f else 0f),
                )

                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 48.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onResetToDefault,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_customize_reset),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun WidgetReorderRow(
    title: String,
    isVisible: Boolean,
    isDragging: Boolean,
    onToggleVisibility: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val toggleDesc = stringResource(R.string.dashboard_customize_cd_toggle, title)
    val dragDesc = stringResource(R.string.dashboard_customize_cd_drag_handle, title)

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = dragDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier
                    .size(48.dp)
                    .padding(12.dp),
            )

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (isVisible) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Switch(
                checked = isVisible,
                onCheckedChange = { onToggleVisibility() },
                modifier = Modifier.semantics { contentDescription = toggleDesc },
            )
        }
    }
}
