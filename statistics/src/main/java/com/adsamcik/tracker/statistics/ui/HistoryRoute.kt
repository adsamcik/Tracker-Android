package com.adsamcik.tracker.statistics.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.presenter.HistoryPresenterViewModel
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/**
 * Entry composable for the History screen. Hosts a segmented button row
 * for tab selection and animated content for each tab.
 */
@Composable
fun HistoryRoute(
	onNavigateToTripDetail: (Long) -> Unit,
	modifier: Modifier = Modifier,
	viewModel: HistoryPresenterViewModel = hiltViewModel(),
) {
	val selectedTab by viewModel.selectedTab.collectAsState()
	val timelineState by viewModel.timelineState.collectAsState()
	val calendarState by viewModel.calendarState.collectAsState()
	val tripsItems = viewModel.pagedTrips.collectAsLazyPagingItems()
	val pendingDeletes by viewModel.pendingDeletes.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }
	val scope = rememberCoroutineScope()
	val deletedMessage = stringResource(R.string.trip_deleted_snackbar)
	val undoLabel = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_undo)

	Box(modifier = modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			HistoryTabRow(
				selectedTab = selectedTab,
				onTabSelected = viewModel::selectTab,
			)

			AnimatedContent(
				targetState = selectedTab,
				label = "history_tab_content",
			) { tab ->
				when (tab) {
					HistoryTab.TIMELINE -> TimelineContent(
						state = timelineState,
						onTripClick = onNavigateToTripDetail,
					)
					HistoryTab.TRIPS -> TripsContent(
						tripsItems = tripsItems,
						onTripClick = onNavigateToTripDetail,
						onDeleteTrip = { tripId ->
							viewModel.requestDeleteTrip(tripId)
							scope.launch {
								val result = snackbarHostState.showSnackbar(
									message = deletedMessage,
									actionLabel = undoLabel,
									duration = SnackbarDuration.Long,
								)
								if (result == SnackbarResult.ActionPerformed) {
									viewModel.undoDeleteTrip(tripId)
								} else {
									viewModel.confirmDeleteTrip(tripId)
								}
							}
						},
						pendingDeletes = pendingDeletes,
					)
					HistoryTab.CALENDAR -> CalendarContent(
						state = calendarState,
						onDayClick = viewModel::selectDay,
						onNavigateToTripDetail = onNavigateToTripDetail,
					)
				}
			}
		}

		SnackbarHost(
			hostState = snackbarHostState,
			modifier = Modifier.align(Alignment.BottomCenter),
		)
	}
}

@Composable
private fun HistoryTabRow(
	selectedTab: HistoryTab,
	onTabSelected: (HistoryTab) -> Unit,
) {
	SingleChoiceSegmentedButtonRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp),
	) {
		HistoryTab.entries.forEachIndexed { index, tab ->
			SegmentedButton(
				selected = selectedTab == tab,
				onClick = { onTabSelected(tab) },
				shape = SegmentedButtonDefaults.itemShape(
					index = index,
					count = HistoryTab.entries.size,
				),
			) {
				Text(
					text = when (tab) {
						HistoryTab.TIMELINE -> stringResource(R.string.history_tab_timeline)
						HistoryTab.TRIPS -> stringResource(R.string.history_tab_trips)
						HistoryTab.CALENDAR -> stringResource(R.string.history_tab_calendar)
					},
				)
			}
		}
	}
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryRoutePreview() {
	val today = LocalDate.now()
	val month = YearMonth.from(today)
	val sampleState = CalendarState(
		currentMonth = month,
		dayData = (1..minOf(month.lengthOfMonth(), 14)).associate { day ->
			val date = month.atDay(day)
			date to CalendarDayData(
				date = date,
				intensity = (day % 5) * 0.2f,
				tripCount = day % 3,
			)
		},
		selectedDay = today,
		selectedDayDetail = CalendarState.DayDetail(
			totalDistanceM = 5420f,
			totalSteps = 7812,
			tripCount = 2,
			trips = listOf(
				Trip(
					id = 1L,
					startTimeMs = System.currentTimeMillis() - 3_600_000L,
					endTimeMs = System.currentTimeMillis() - 1_800_000L,
					distanceM = 3200f,
					steps = 4200,
					primaryActivity = 7,
					activityConfidence = 90,
					sampleCount = 48,
					source = SegmentSource.USER_CREATED,
					createdAt = System.currentTimeMillis() - 3_600_000L,
				),
			),
		),
	)

	AppTheme {
		Column(modifier = Modifier.fillMaxSize()) {
			HistoryTabRow(
				selectedTab = HistoryTab.CALENDAR,
				onTabSelected = {},
			)
			CalendarContent(
				state = sampleState,
				onDayClick = {},
				onNavigateToTripDetail = {},
			)
		}
	}
}
