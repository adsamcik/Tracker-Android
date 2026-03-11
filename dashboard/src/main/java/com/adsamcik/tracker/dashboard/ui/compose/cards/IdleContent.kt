package com.adsamcik.tracker.dashboard.ui.compose.cards

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.components.MotivationalText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.utils.style.compose.rememberContentColumnCount

/**
 * Idle dashboard content shown when the user is NOT tracking.
 *
 * Displays daily summary, streaks, challenges, last session,
 * recent trips, and exploration in a scrollable column.
 */
@Composable
internal fun IdleContent(
	state: DashboardUiState,
	listState: LazyListState = rememberLazyListState(),
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onChallengesClick: (() -> Unit)? = onGameClick,
	onSessionDetailClick: ((Long) -> Unit)?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val configuration = LocalConfiguration.current
	val columns = rememberContentColumnCount()
	val contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomClearance)
	val itemSpacing = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 8.dp else 12.dp

	if (columns == 1) {
		LazyColumn(
			state = listState,
			modifier = modifier.fillMaxSize(),
			contentPadding = contentPadding,
			verticalArrangement = Arrangement.spacedBy(itemSpacing),
		) {
			item(key = "motivational") {
				MotivationalText(state = state)
			}
			item(key = "today_progress") {
				TodayProgressCard(
					state = state,
					onToggleTracking = onToggleTracking,
					onRequestPermission = onRequestPermission,
				)
			}
			item(key = "streak") {
				StreakBanner(
					streakState = state.streakState,
					onClick = onGameClick,
				)
			}
			if (state.activeChallenges.isNotEmpty() || onChallengesClick != null) {
				item(key = "challenges") {
					ChallengeCardsRow(
						challenges = state.activeChallenges,
						onChallengeClick = onChallengesClick,
					)
				}
			}
			val session = state.sessionData
			if (session != null) {
				item(key = "last_session") {
					LastSessionCard(
						session = session,
						pathPoints = state.pathPoints,
						onMapClick = onMapClick,
						onSessionDetailClick = onSessionDetailClick,
					)
				}
			}
			item(key = "recent_trips") {
				RecentTripsCard(
					trips = state.recentTrips,
					onTripClick = onSessionDetailClick,
				)
			}
			if (state.explorationState.hasExplorationData) {
				item(key = "exploration") {
					ExplorationCard(
						explorationState = state.explorationState,
					)
				}
			}
		}
	} else {
		LazyVerticalGrid(
			columns = GridCells.Fixed(columns),
			modifier = modifier.fillMaxSize(),
			contentPadding = contentPadding,
			verticalArrangement = Arrangement.spacedBy(itemSpacing),
			horizontalArrangement = Arrangement.spacedBy(itemSpacing),
		) {
			item(
				key = "motivational",
				span = { GridItemSpan(columns) },
			) {
				MotivationalText(state = state)
			}
			item(
				key = "today_progress",
				span = { GridItemSpan(columns) },
			) {
				TodayProgressCard(
					state = state,
					onToggleTracking = onToggleTracking,
					onRequestPermission = onRequestPermission,
				)
			}
			item(key = "streak") {
				StreakBanner(
					streakState = state.streakState,
					onClick = onGameClick,
				)
			}
			if (state.explorationState.hasExplorationData) {
				item(key = "exploration") {
					ExplorationCard(explorationState = state.explorationState)
				}
			}
			if (state.activeChallenges.isNotEmpty() || onChallengesClick != null) {
				item(
					key = "challenges",
					span = { GridItemSpan(columns) },
				) {
					ChallengeCardsRow(
						challenges = state.activeChallenges,
						onChallengeClick = onChallengesClick,
					)
				}
			}
			val session = state.sessionData
			if (session != null) {
				item(key = "last_session") {
					LastSessionCard(
						session = session,
						pathPoints = state.pathPoints,
						onMapClick = onMapClick,
						onSessionDetailClick = onSessionDetailClick,
					)
				}
			}
			item(key = "recent_trips") {
				RecentTripsCard(
					trips = state.recentTrips,
					onTripClick = onSessionDetailClick,
				)
			}
		}
	}
}
