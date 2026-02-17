package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.base.Time
import java.util.Calendar

/**
 * Time-aware and context-aware motivational text.
 *
 * Shows contextual encouragement when tracking (based on duration),
 * or a time-of-day greeting when idle. Appends streak info when active.
 */
@Composable
internal fun MotivationalText(
	state: DashboardUiState,
	modifier: Modifier = Modifier,
) {
	val primaryText = resolvePrimaryText(state)
	val streakSuffix = resolveStreakSuffix(state)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
	) {
		Text(
			text = if (streakSuffix != null) "$primaryText  $streakSuffix" else primaryText,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

@Composable
private fun resolvePrimaryText(state: DashboardUiState): String {
	if (state.isTracking && state.sessionData != null) {
		return resolveTrackingText(state)
	}
	return resolveIdleGreeting()
}

@Composable
private fun resolveTrackingText(state: DashboardUiState): String {
	val session = state.sessionData ?: return stringResource(R.string.dashboard_motivational_getting_started)

	val sessionEnd = when {
		session.end > session.start -> session.end
		else -> Time.nowMillis
	}
	val durationMinutes = ((sessionEnd - session.start) / 60_000L).coerceAtLeast(0L)

	return when {
		durationMinutes < 5 -> stringResource(R.string.dashboard_motivational_getting_started)
		durationMinutes < 30 -> stringResource(R.string.dashboard_motivational_keep_going)
		durationMinutes < 60 -> stringResource(R.string.dashboard_motivational_impressive)
		else -> stringResource(R.string.dashboard_motivational_marathon)
	}
}

@Composable
private fun resolveIdleGreeting(): String {
	val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
	return when (hour) {
		in 6..11 -> stringResource(R.string.dashboard_motivational_morning)
		in 12..16 -> stringResource(R.string.dashboard_motivational_afternoon)
		in 17..21 -> stringResource(R.string.dashboard_motivational_evening)
		else -> stringResource(R.string.dashboard_motivational_night)
	}
}

@Composable
private fun resolveStreakSuffix(state: DashboardUiState): String? {
	val streak = state.streakState.currentStreak
	if (streak <= 1) return null
	return stringResource(R.string.dashboard_motivational_streak, streak)
}
