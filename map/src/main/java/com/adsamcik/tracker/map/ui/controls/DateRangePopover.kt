package com.adsamcik.tracker.map.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.adsamcik.tracker.map.R

/**
 * Popover for date-range presets. Opens from the Dates chip; the custom range picker
 * is a separate [androidx.compose.material3.DatePickerDialog] that the caller owns —
 * this popover only emits a [onRequestCustom] signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangePopover(
	currentRange: LongRange,
	onSetRange: (LongRange) -> Unit,
	onRequestCustom: () -> Unit,
	onDismiss: () -> Unit,
) {
	Popup(
		onDismissRequest = onDismiss,
		properties = PopupProperties(
			focusable = true,
			dismissOnBackPress = true,
			dismissOnClickOutside = true,
		),
	) {
		val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.fillMaxHeight()
				.background(Color.Black.copy(alpha = 0.32f))
				.clickable(onClick = onDismiss),
			contentAlignment = Alignment.BottomCenter,
		) {
			Surface(
				modifier = Modifier
					.fillMaxWidth()
					.padding(
						start = 16.dp,
						end = 16.dp,
						bottom = navBarInset + 128.dp,
					)
					.clickable(enabled = false, onClick = {}),
				shape = RoundedCornerShape(32.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				tonalElevation = 4.dp,
				shadowElevation = 3.dp,
			) {
				Column(
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Text(
						text = stringResource(R.string.tips_map_date_range_title),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
					)

					val now = remember { System.currentTimeMillis() }
					val oneWeekMs = 7L * 24 * 60 * 60 * 1000
					val oneMonthMs = 30L * 24 * 60 * 60 * 1000
					val isAllTime = currentRange.first == 0L && currentRange.last == Long.MAX_VALUE
					val isLastWeek = !isAllTime && currentRange.first >= now - oneWeekMs
					val isLastMonth = !isAllTime &&
						currentRange.first >= now - oneMonthMs &&
						currentRange.first < now - oneWeekMs
					val isCustom = !isAllTime && !isLastWeek && !isLastMonth

					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
						modifier = Modifier.fillMaxWidth(),
					) {
						FilterChip(
							selected = isAllTime,
							onClick = {
								onSetRange(0L..Long.MAX_VALUE)
								onDismiss()
							},
							label = { Text(stringResource(R.string.map_date_range_all_time)) },
							shape = MaterialTheme.shapes.medium,
						)
						FilterChip(
							selected = isLastWeek,
							onClick = {
								onSetRange((now - oneWeekMs)..now)
								onDismiss()
							},
							label = { Text(stringResource(R.string.map_date_preset_week)) },
							shape = MaterialTheme.shapes.medium,
						)
						FilterChip(
							selected = isLastMonth,
							onClick = {
								onSetRange((now - oneMonthMs)..now)
								onDismiss()
							},
							label = { Text(stringResource(R.string.map_date_preset_month)) },
							shape = MaterialTheme.shapes.medium,
						)
						FilterChip(
							selected = isCustom,
							onClick = {
								onRequestCustom()
								onDismiss()
							},
							label = { Text(stringResource(R.string.map_date_preset_custom)) },
							trailingIcon = {
								Icon(
									imageVector = Icons.Filled.DateRange,
									contentDescription = null,
									modifier = Modifier.size(18.dp),
								)
							},
							shape = MaterialTheme.shapes.medium,
						)
					}

					if (!isAllTime) {
						val rangeText = remember(currentRange) {
							val fmt = java.text.SimpleDateFormat(
								"MMM dd, yyyy",
								java.util.Locale.getDefault(),
							)
							"${fmt.format(java.util.Date(currentRange.first))} – " +
								fmt.format(java.util.Date(currentRange.last))
						}
						Text(
							text = rangeText,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}
