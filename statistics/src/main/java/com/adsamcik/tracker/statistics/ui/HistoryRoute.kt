package com.adsamcik.tracker.statistics.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.HistoryPresenterViewModel
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab

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

	Column(modifier = modifier.fillMaxSize()) {
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
				)
				HistoryTab.CALENDAR -> CalendarContent(
					state = calendarState,
					onDayClick = viewModel::selectDay,
					onNavigateToTripDetail = onNavigateToTripDetail,
				)
			}
		}
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
