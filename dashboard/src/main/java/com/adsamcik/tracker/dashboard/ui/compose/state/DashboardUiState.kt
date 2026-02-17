package com.adsamcik.tracker.dashboard.ui.compose.state

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Unified UI state for the Dashboard screen.
 *
 * Combines data from tracker, game, exploration, and statistics modules
 * into a single immutable snapshot for Compose rendering.
 *
 * The dashboard has three primary visual modes determined by [dashboardMode]:
 * - EMPTY: First-time user, no tracking data yet
 * - IDLE: Not tracking, showing daily summary and history
 * - TRACKING: Active session with live data
 */
@Immutable
data class DashboardUiState(
	val dashboardMode: DashboardMode = DashboardMode.EMPTY,

	// ─── Tracking state ──────────────────────────────────────────────
	val isTracking: Boolean = false,
	val isLocked: Boolean = false,
	val hasLocationPermission: Boolean = false,
	val policyTier: PolicyTier = PolicyTier.OFF,

	// ─── Live session data (tracking mode) ───────────────────────────
	val sessionData: TrackerSession? = null,
	val collectionData: CollectionData? = null,
	val pathPoints: List<Location>? = null,

	// ─── Daily summary (idle mode) ───────────────────────────────────
	val todaySummary: DailySummary? = null,
	val recentTrips: List<Trip> = emptyList(),

	// ─── Gamification ────────────────────────────────────────────────
	val pointsToday: Int = 0,
	val goalProgress: GoalProgressState = GoalProgressState(),
	val activeChallenges: List<ChallengeUiModel> = emptyList(),
	val streakState: StreakState = StreakState(),

	// ─── Exploration ─────────────────────────────────────────────────
	val explorationState: ExplorationUiState = ExplorationUiState(),
)

@Immutable
enum class DashboardMode {
	/** First-time user, no tracking history */
	EMPTY,

	/** Not tracking — show daily summary, history, challenges */
	IDLE,

	/** Active tracking session — show map, live metrics, real-time data */
	TRACKING,
}

@Immutable
data class GoalProgressState(
	val gamificationEnabled: Boolean = false,
	val dailySteps: Int = 0,
	val dailyGoalSteps: Int = 0,
	val dailyProgress: Float = 0f,
	val weeklySteps: Int = 0,
	val weeklyGoalSteps: Int = 0,
	val weeklyProgress: Float = 0f,
)

/**
 * UI model for an active challenge displayed on the dashboard.
 */
@Immutable
data class ChallengeUiModel(
	val id: Long,
	val title: String,
	val description: String,
	val progress: Float,
	val iconResName: String,
	val difficulty: String,
	val timeRemainingMs: Long,
	val rewardPoints: Int,
)

@Immutable
data class StreakState(
	val currentStreak: Int = 0,
	val bestStreak: Int = 0,
	val weeklyDistances: List<Float> = emptyList(),
	val weeklyTrend: WeeklyTrend = WeeklyTrend.STEADY,
)

@Immutable
enum class WeeklyTrend {
	UP,
	DOWN,
	STEADY,
}

@Immutable
data class ExplorationUiState(
	val newCellsToday: Int = 0,
	val totalCells: Int = 0,
	val seasonsCovered: Int = 0,
	val hasExplorationData: Boolean = false,
)
