package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.ui.compose.components.MotivationalText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState

/**
 * Idle dashboard content shown when the user is NOT tracking.
 *
 * Displays daily summary, streaks, challenges, last session,
 * recent trips, and exploration in a scrollable column.
 */
@Composable
internal fun IdleContent(
	state: DashboardUiState,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onSessionDetailClick: ((Long) -> Unit)?,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// Motivational greeting
		item(key = "motivational") {
			MotivationalText(state = state)
		}

		// Today's progress
		item(key = "today_progress") {
			TodayProgressCard(state = state)
		}

		// Streak banner
		item(key = "streak") {
			StreakBanner(streakState = state.streakState)
		}

		// Challenge cards (horizontal scroll)
		if (state.activeChallenges.isNotEmpty() || onGameClick != null) {
			item(key = "challenges") {
				ChallengeCardsRow(
					challenges = state.activeChallenges,
					onChallengeClick = onGameClick,
				)
			}
		}

		// Last session card
		val session = state.sessionData
		if (session != null) {
			item(key = "last_session") {
				LastSessionCard(
					session = session,
					pathPoints = state.pathPoints,
					onMapClick = onMapClick,
				)
			}
		}

		// Recent trips
		if (state.recentTrips.isNotEmpty()) {
			item(key = "recent_trips") {
				RecentTripsCard(
					trips = state.recentTrips,
					onTripClick = onSessionDetailClick,
				)
			}
		}

		// Exploration
		if (state.explorationState.hasExplorationData) {
			item(key = "exploration") {
				ExplorationCard(explorationState = state.explorationState)
			}
		}

		// Bottom spacer for FAB clearance
		item(key = "bottom_spacer") {
			Spacer(Modifier.height(120.dp))
		}
	}
}
