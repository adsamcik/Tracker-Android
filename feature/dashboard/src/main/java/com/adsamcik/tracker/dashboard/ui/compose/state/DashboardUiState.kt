package com.adsamcik.tracker.dashboard.ui.compose.state

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import com.adsamcik.tracker.tracker.insights.SessionInsight
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

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
	val sessionData: TrackerSessionSnapshot? = null,
	val collectionSnapshot: TrackerCollectionSnapshot? = null,
	val pathPoints: List<Location>? = null,
	val liveSessionPresentation: DashboardLiveSessionPresentation =
		DashboardLiveSessionPresentation.Inactive,

	// ─── Daily summary (idle mode) ───────────────────────────────────
	val todaySummary: DailySummary? = null,
	val recentHistory: DashboardRecentHistoryState = DashboardRecentHistoryState.Loading,

	// ─── Gamification ────────────────────────────────────────────────
	val pointsToday: Int = 0,
	val goalProgress: GoalProgressState = GoalProgressState(),
	val latestAchievement: LatestAchievementUi? = null,
	val streakState: StreakState = StreakState(),

	// ─── Exploration ─────────────────────────────────────────────────
	val explorationState: ExplorationUiState = ExplorationUiState(),

	// ─── Post-session insights ───────────────────────────────────────
	val sessionInsights: List<SessionInsight> = emptyList(),
) {
	companion object {
		/** Preview factory for idle state with representative sample data. */
		fun previewIdle(): DashboardUiState = DashboardUiState(
			dashboardMode = DashboardMode.IDLE,
			isTracking = false,
			hasLocationPermission = true,
			policyTier = PolicyTier.OFF,
			pointsToday = 42,
			goalProgress = GoalProgressState(
				gamificationEnabled = true,
				dailySteps = QualifiedStepCount.Ready(PREVIEW_IDLE_DAILY_STEPS),
				dailyGoalSteps = 10000,
				weeklySteps = QualifiedStepCount.Ready(PREVIEW_IDLE_WEEKLY_STEPS),
				weeklyGoalSteps = 70000,
			),
			streakState = StreakState(
				currentStreak = 3,
				bestStreak = 7,
				weeklyDistances = listOf(0.2f, 0.5f, 0.8f, 0.6f, 0.3f, 0.9f, 0.0f),
				weeklyTrend = WeeklyTrend.UP,
			),
			explorationState = ExplorationUiState(
				newCellsToday = 5,
				totalCells = 128,
				seasonsCovered = 2,
				hasExplorationData = true,
			),
		)

		/** Preview factory for tracking-active state. */
		fun previewTracking(): DashboardUiState = DashboardUiState(
			dashboardMode = DashboardMode.TRACKING,
			isTracking = true,
			hasLocationPermission = true,
			policyTier = PolicyTier.PRECISION,
			pointsToday = 15,
			goalProgress = GoalProgressState(
				gamificationEnabled = true,
				dailySteps = QualifiedStepCount.Ready(PREVIEW_TRACKING_DAILY_STEPS),
				dailyGoalSteps = 10000,
			),
		)
	}
}

@Immutable
enum class DashboardMode {
	/** First-time user, no tracking history */
	EMPTY,

	/** Not tracking — show daily summary, history, achievements */
	IDLE,

	/** Active tracking session — show map, live metrics, real-time data */
	TRACKING,
}

@Immutable
data class GoalProgressState(
	val gamificationEnabled: Boolean = false,
	val dailySteps: QualifiedStepCount = QualifiedStepCount.Unavailable(
		QualifiedStepCountUnavailableReason.MISSING,
	),
	val dailyGoalSteps: Int = 0,
	val weeklySteps: QualifiedStepCount = QualifiedStepCount.Unavailable(
		QualifiedStepCountUnavailableReason.MISSING,
	),
	val weeklyGoalSteps: Int = 0,
) {
	val dailyProgress: Float?
		get() = dailySteps.progressAgainst(dailyGoalSteps)

	val weeklyProgress: Float?
		get() = weeklySteps.progressAgainst(weeklyGoalSteps)
}

private fun QualifiedStepCount.progressAgainst(goal: Int): Float? =
	(this as? QualifiedStepCount.Ready)?.let { ready ->
		if (goal > 0) {
			(ready.value.toFloat() / goal).coerceIn(0f, 1f)
		} else {
			0f
		}
	}

private const val PREVIEW_IDLE_DAILY_STEPS = 6_500
private const val PREVIEW_IDLE_WEEKLY_STEPS = 35_000
private const val PREVIEW_TRACKING_DAILY_STEPS = 2_100

/** Raw legacy milestone inputs are valid only for the exact current Standard physical segment. */
internal val DashboardUiState.allowsRuntimeMilestones: Boolean
	get() {
		val standard = liveSessionPresentation as? DashboardLiveSessionPresentation.Standard
			?: return false
		return sessionData?.id == standard.segmentId
	}

/** Complete source-qualified Steps for celebratory effects on the exact active segment. */
internal val DashboardUiState.qualifiedMilestoneSteps: Long?
	get() {
		val standard = liveSessionPresentation as? DashboardLiveSessionPresentation.Standard
			?: return null
		if (sessionData?.id != standard.segmentId) {
			return null
		}
		return (standard.steps as? DashboardLiveStepsValue.Complete)?.count
	}

@Immutable
data class LatestAchievementUi(
	val id: String,
	val nameRes: String,
	val tier: AchievementTier,
	val unlockedAt: Long,
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
