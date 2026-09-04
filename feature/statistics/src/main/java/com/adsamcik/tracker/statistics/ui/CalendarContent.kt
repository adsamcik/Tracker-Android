package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.viewmodel.HistoryStepsValue
import com.adsamcik.tracker.statistics.viewmodel.activityIcon
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.statistics.viewmodel.formatDistanceLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Displays the calendar tab with a month grid and day detail.
 */
@Composable
internal fun CalendarContent(
	state: CalendarState,
	onDayClick: (LocalDate) -> Unit,
	onMonthChange: (YearMonth) -> Unit = {},
	onNavigateToTripDetail: (Long) -> Unit,
	onViewTripOnMap: (Trip) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier.fillMaxSize()) {
		MonthHeader(
			yearMonth = state.currentMonth,
			onPreviousMonth = { onMonthChange(state.currentMonth.minusMonths(1)) },
			onNextMonth = { onMonthChange(state.currentMonth.plusMonths(1)) },
		)

		CalendarGrid(
			yearMonth = state.currentMonth,
			dayData = state.dayData,
			selectedDay = state.selectedDay,
			onDayClick = onDayClick,
		)

		// Day detail bottom section
		state.selectedDayDetail?.let { detail ->
			HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
			DayDetailSection(
				detail = detail,
				onTripClick = onNavigateToTripDetail,
				onViewTripOnMap = onViewTripOnMap,
			)
		}
	}
}

@Composable
private fun MonthHeader(
	yearMonth: YearMonth,
	onPreviousMonth: () -> Unit,
	onNextMonth: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconButton(onClick = onPreviousMonth) {
			Icon(
				Icons.AutoMirrored.Filled.ArrowBack,
				contentDescription = stringResource(R.string.history_calendar_previous_month),
			)
		}
		Text(
			text = "${yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${yearMonth.year}",
			style = MaterialTheme.typography.titleMedium,
		)
		IconButton(onClick = onNextMonth) {
			Icon(
				Icons.AutoMirrored.Filled.ArrowForward,
				contentDescription = stringResource(R.string.history_calendar_next_month),
			)
		}
	}
}

@Composable
private fun CalendarGrid(
	yearMonth: YearMonth,
	dayData: Map<LocalDate, CalendarDayData>,
	selectedDay: LocalDate?,
	onDayClick: (LocalDate) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier.padding(horizontal = 16.dp)) {
		// Day of week headers
		Row(modifier = Modifier.fillMaxWidth()) {
			for (day in DayOfWeek.entries) {
				Text(
					text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
					modifier = Modifier.weight(1f),
					textAlign = TextAlign.Center,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		Spacer(Modifier.height(4.dp))

		// Calendar cells
		val firstDay = yearMonth.atDay(1)
		val startOffset = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
		val totalDays = yearMonth.lengthOfMonth()
		val totalCells = startOffset + totalDays
		val rows = (totalCells + 6) / 7

		for (row in 0 until rows) {
			Row(modifier = Modifier.fillMaxWidth()) {
				for (col in 0..6) {
					val cellIndex = row * 7 + col
					val dayNum = cellIndex - startOffset + 1
					if (dayNum in 1..totalDays) {
						val date = yearMonth.atDay(dayNum)
						val data = dayData[date]
						CalendarCell(
							dayNum = dayNum,
							intensity = data?.intensity ?: 0f,
							isSelected = date == selectedDay,
							isToday = date == LocalDate.now(),
							onClick = { onDayClick(date) },
							modifier = Modifier.weight(1f),
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
				}
			}
		}
	}
}

@Composable
private fun CalendarCell(
	dayNum: Int,
	intensity: Float,
	isSelected: Boolean,
	isToday: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val bgColor = when {
		isSelected -> MaterialTheme.colorScheme.primaryContainer
		intensity > 0f -> lerp(
			MaterialTheme.colorScheme.surfaceVariant,
			MaterialTheme.colorScheme.primary,
			intensity.coerceIn(0f, 1f) * 0.6f,
		)
		else -> Color.Transparent
	}
	val textColor = when {
		isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
		isToday -> MaterialTheme.colorScheme.primary
		else -> MaterialTheme.colorScheme.onSurface
	}

	Box(
		modifier = modifier
			.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
			.aspectRatio(1f)
			.padding(2.dp)
			.clip(CircleShape)
			.background(bgColor)
			.clickable(onClick = onClick)
			.semantics { contentDescription = "Day $dayNum" },
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = dayNum.toString(),
			style = if (isToday) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
			color = textColor,
		)
	}
}

@Composable
private fun DayDetailSection(
	detail: CalendarState.DayDetail,
	onTripClick: (Long) -> Unit,
	onViewTripOnMap: (Trip) -> Unit,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier.fillMaxWidth(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = 120.dp,
		),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// Summary stats
		item(key = "day_summary") {
			Card(modifier = Modifier.fillMaxWidth()) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(16.dp),
					horizontalArrangement = Arrangement.SpaceEvenly,
					verticalAlignment = Alignment.CenterVertically,
				) {
					DayStatItem(
						label = stringResource(R.string.history_calendar_distance),
						value = formatDistanceLabel(detail.totalDistanceM),
					)
					DayStatItem(
						label = stringResource(R.string.history_calendar_steps),
						value = detail.steps.calendarText(),
					)
					DayStatItem(
						label = stringResource(R.string.history_calendar_trips),
						value = detail.tripCount.toString(),
					)
				}
			}
		}

		// Trip list for the day
		items(
			items = detail.trips,
			key = { it.id },
		) { trip ->
			CalendarTripCard(
				trip = trip,
				onClick = { onTripClick(trip.id) },
				onViewOnMap = { onViewTripOnMap(trip) },
			)
		}
	}
}

@Composable
private fun HistoryStepsValue.calendarText(): String = when (this) {
	is HistoryStepsValue.Ready -> count.formatReadable()
	HistoryStepsValue.Materializing -> stringResource(R.string.history_value_materializing)
	is HistoryStepsValue.Unavailable -> when (reason) {
		com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason.NOT_CAPTURED ->
			stringResource(R.string.history_value_not_captured)
		com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason.PARTIAL_CAPTURE ->
			stringResource(R.string.history_value_partial)
		else -> stringResource(R.string.history_value_unavailable)
	}
}

@Composable
private fun DayStatItem(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text = value,
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.primary,
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun CalendarTripCard(
	trip: Trip,
	onClick: () -> Unit,
	onViewOnMap: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	Card(
		onClick = onClick,
		modifier = modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				imageVector = activityIcon(trip.primaryActivity),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = formatCalendarTripTitle(trip),
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = formatDistanceLabel(trip.distanceM),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.primary,
			)
			if (onViewOnMap != null) {
				IconButton(onClick = onViewOnMap) {
					Icon(
						imageVector = Icons.Filled.Map,
						contentDescription = stringResource(R.string.history_view_trip_on_map),
						tint = MaterialTheme.colorScheme.primary,
					)
				}
			}
		}
	}
}

private fun formatCalendarTripTitle(trip: Trip): String {
	val mode = activityLabel(trip.primaryActivity)
	val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
	val time = java.time.Instant.ofEpochMilli(trip.startTimeMs)
		.atZone(java.time.ZoneId.systemDefault())
		.toLocalTime()
		.format(formatter)
	return "$mode at $time"
}

// ─── Previews ────────────────────────────────────────────────────────

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CalendarContentPreview() {
	val today = LocalDate.now()
	val month = YearMonth.from(today)
	val sampleDayData = (1..today.dayOfMonth).associate { day ->
		val date = month.atDay(day)
		date to CalendarDayData(date = date, intensity = (day % 5) * 0.25f, tripCount = day % 3)
	}
	AppTheme {
		CalendarContent(
			state = CalendarState(
				currentMonth = month,
				dayData = sampleDayData,
				selectedDay = today,
			),
			onDayClick = {},
			onNavigateToTripDetail = {},
		)
	}
}
