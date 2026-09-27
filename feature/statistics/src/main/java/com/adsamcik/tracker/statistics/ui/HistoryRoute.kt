package com.adsamcik.tracker.statistics.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.presenter.SessionOrganizationViewModel
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import com.adsamcik.tracker.statistics.presenter.HistoryPresenterViewModel
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import com.adsamcik.tracker.statistics.viewmodel.HistoryStepsValue
import java.time.LocalDate
import java.time.YearMonth

/**
 * Entry composable for the History screen. Hosts a segmented button row
 * for tab selection and animated content for each tab.
 *
 * Wrapped in a [Scaffold] with a [TopAppBar] so the user always has a
 * visible back affordance — History is hidden from the global floating
 * navigation bar (see MainRoot.hideTopLevelNavigation), so without our
 * own chrome the user would be stranded with only the system BACK
 * gesture as an escape route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRoute(
	onBack: () -> Unit = {},
	onNavigateToTripDetail: (Long) -> Unit,
	onNavigateToMap: (Long, Long, Long) -> Unit = { _, _, _ -> },
	modifier: Modifier = Modifier,
	viewModel: HistoryPresenterViewModel = hiltViewModel(),
	organization: SessionOrganizationViewModel = hiltViewModel(),
) {
	val selectedTab by viewModel.selectedTab.collectAsState()
	val timelineState by viewModel.timelineState.collectAsState()
	val calendarState by viewModel.calendarState.collectAsState()
	val sessionPages = organization.pagedSessions.collectAsLazyPagingItems()
	val recordingPages = viewModel.pagedTrips.collectAsLazyPagingItems()
	val pendingDeletes by viewModel.pendingDeletes.collectAsState()
	var showRecordings by remember { mutableStateOf(false) }
	val snackbarHostState = remember { SnackbarHostState() }
	val deletedMessage = stringResource(R.string.trip_deleted_snackbar)
	val undoLabel = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_undo)
	LaunchedEffect(viewModel, snackbarHostState, deletedMessage, undoLabel) {
		viewModel.pendingDeleteEvents.collect { event ->
			if (snackbarHostState.showSnackbar(deletedMessage, undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
				viewModel.undoDeleteTrip(event.tripId)
		}
	}
	val sessions by organization.read.collectAsState()
	val monthSessions by organization.monthRead.collectAsState()
	val organizationDay by organization.selectedDay.collectAsState()
	val editor by organization.editor.collectAsState()
	val labels by organization.labels.collectAsState()
	val problem by organization.problem.collectAsState()
	val busy by organization.busy.collectAsState()
	LaunchedEffect(calendarState.currentMonth) { organization.showMonth(calendarState.currentMonth) }
	val organizationDays = remember(monthSessions, calendarState.currentMonth) {
		organizationCalendarDays(monthSessions, calendarState.currentMonth)
	}

	BackHandler(onBack = onBack)

	Scaffold(
		modifier = modifier.fillMaxSize(),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.history_screen_title)) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.action_navigate_back),
						)
					}
				},
				actions = {
					TextButton(enabled = sessions is SessionOrganizationRead.Content && !busy, onClick = {
						organization.add(if (selectedTab == HistoryTab.CALENDAR) calendarState.selectedDay else organizationDay)
					}) { Text(stringResource(R.string.organization_add)) }
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.surface,
				),
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { innerPadding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
		) {
			HistoryTabRow(
				selectedTab = selectedTab,
				onTabSelected = viewModel::selectTab,
			)

			if (editor == null && problem != null) Text(stringResource(organizationProblemText(requireNotNull(problem))),
				modifier = Modifier.padding(horizontal = 16.dp))
			AnimatedContent(
				targetState = selectedTab,
				label = "history_tab_content",
			) { tab ->
				when (tab) {
					HistoryTab.TIMELINE -> TimelineContent(
						state = timelineState,
						onTripClick = onNavigateToTripDetail,
					)
					HistoryTab.TRIPS -> Column {
						TextButton(onClick = { showRecordings = !showRecordings }) {
							Text(stringResource(if (showRecordings) R.string.organization_back_sessions else R.string.organization_browse_recordings))
						}
						if (showRecordings && sessions is SessionOrganizationRead.Content) TripsContent(recordingPages, onNavigateToTripDetail,
							onDeleteTrip = viewModel::requestDeleteTrip, pendingDeletes = pendingDeletes,
							onViewTripOnMap = { onNavigateToMap(it.id, it.startTimeMs, it.endTimeMs) })
						else OrganizedSessionsContent(sessions, organizationDay, organization::edit,
							onShowAll = { organization.showDay(null) }, pages = sessionPages)
					}
					HistoryTab.CALENDAR -> CalendarContent(
						state = calendarState,
						onDayClick = viewModel::selectDay,
						organizationDays = organizationDays,
						organizationRead = monthSessions,
						onViewSessionsForDay = { day -> showRecordings = false; organization.showDay(day); viewModel.selectTab(HistoryTab.TRIPS) },
						onMonthChange = viewModel::selectMonth,
						onNavigateToTripDetail = onNavigateToTripDetail,
						onViewTripOnMap = { trip ->
							onNavigateToMap(trip.id, trip.startTimeMs, trip.endTimeMs)
						},
					)
				}
			}
		}
	}
	editor?.let { target -> SessionOrganizationEditor(target, labels, busy, problem,
		onDismiss = organization::dismiss, onReload = organization::reload,
		onSave = organization::save, onRemove = organization::remove, onRecording = onNavigateToTripDetail) }

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
						HistoryTab.TRIPS -> stringResource(R.string.history_tab_sessions)
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
			steps = HistoryStepsValue.Ready(7812L),
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
