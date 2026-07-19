package com.adsamcik.tracker.game.ui.compose

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.leaderboard.LeaderboardMetric
import com.adsamcik.tracker.game.leaderboard.WeeklyLeaderboardCard
import com.adsamcik.tracker.game.leaderboard.WeeklyLeaderboardErrorCard
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSectionHeader
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.bottomNavSafeClearance
import com.adsamcik.tracker.shared.utils.style.compose.rememberMainNavigationLayout
import java.text.NumberFormat

internal data class StepsSummaryUi(
	val stepsToday: Int,
	val stepsWeek: Int,
	val goalDay: Int,
	val goalWeek: Int,
)

/**
 * Ordered hub sections. Play always comes first; the rest form a
 * progress report below it. See [gameHubSectionOrder].
 */
internal enum class GameHubSection {
	ACTIVE_SESSION,
	PLAY,
	LEADERBOARD,
	GOALS,
	PROGRESS,
	HISTORY,
}

/**
 * Canonical hub ordering: play first, local "This Week" leaderboard second,
 * compact goals third, progress (points/exploration/achievements) after, and
 * the personal-best / history link last. An active session, when present,
 * takes the very top slot.
 */
internal fun gameHubSectionOrder(hasActiveSession: Boolean): List<GameHubSection> = buildList {
	if (hasActiveSession) add(GameHubSection.ACTIVE_SESSION)
	add(GameHubSection.PLAY)
	add(GameHubSection.LEADERBOARD)
	add(GameHubSection.GOALS)
	add(GameHubSection.PROGRESS)
	add(GameHubSection.HISTORY)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameScreen(
	hub: GameHubState?,
	explorationState: ExplorationState? = null,
	achievementState: AchievementSummaryState? = null,
	modifier: Modifier = Modifier,
	onViewAllAchievements: () -> Unit = {},
	onOpenSettings: () -> Unit = {},
	onPlayMiniGame: (gameId: String) -> Unit = {},
	onViewMiniGameScores: () -> Unit = {},
	onSelectLeaderboardMetric: (LeaderboardMetric) -> Unit = {},
	onRetryLeaderboard: () -> Unit = {},
) {
	val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
	val layoutDirection = LocalLayoutDirection.current
	val horizontalInsetStart = safeDrawingPadding.calculateStartPadding(layoutDirection)
	val horizontalInsetEnd = safeDrawingPadding.calculateEndPadding(layoutDirection)
	val safeBottomPadding = safeDrawingPadding.calculateBottomPadding()
	val navigationLayout = rememberMainNavigationLayout()
	val bottomClearance = bottomNavSafeClearance(navigationLayout, safeBottomPadding)
	Scaffold(
		modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
		topBar = {
			TopAppBar(title = {}, actions = {
				IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
					Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.game_open_settings))
				}
			})
		},
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				// Apply only top + horizontal slices of Scaffold's innerPadding. The
				// bottom inset is covered by MainRoot's NavHost-level reservation and
				// the SideRail-aware bottom contentPadding below.
				.padding(top = innerPadding.calculateTopPadding())
				.padding(start = horizontalInsetStart, end = horizontalInsetEnd),
			contentPadding = PaddingValues(top = RidgelineSpacing.Lg, bottom = bottomClearance),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			if (hub == null) {
				item { LoadingGameCard() }
				return@LazyColumn
			}
			gameHubSectionOrder(hub.activeSession != null).forEach { section ->
				when (section) {
					GameHubSection.ACTIVE_SESSION -> hub.activeSession?.let { active ->
						item(key = "active-session") { ActiveSessionCard(active) }
					}
					GameHubSection.PLAY -> {
						item(key = "play-header") {
							RidgelineSectionHeader(title = stringResource(R.string.game_play_section_title))
						}
						item(key = "play-section") {
							MiniGamePlaySection(
								games = hub.games,
								onPlay = onPlayMiniGame,
								modifier = Modifier.testTag("game_play_section"),
							)
						}
					}
					GameHubSection.LEADERBOARD -> {
						item(key = "leaderboard-header") {
							RidgelineSectionHeader(title = stringResource(R.string.leaderboard_current_week))
						}
						item(key = "leaderboard-card") {
							LeaderboardSection(
								state = hub.leaderboard,
								onMetricSelected = onSelectLeaderboardMetric,
								onRetry = onRetryLeaderboard,
							)
						}
					}
					GameHubSection.GOALS -> {
						item(key = "goals-header") {
							RidgelineSectionHeader(title = stringResource(R.string.game_steps_goals_title))
						}
						item(key = "goals-card") {
							val context = LocalContext.current
							val stepCounterSupported = remember {
								context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
							}
							if (hub.steps == null) {
								LoadingGameCard()
							} else {
								StepsCard(hub.steps, stepCounterSupported)
							}
						}
					}
					GameHubSection.PROGRESS -> {
						item(key = "progress-header") {
							RidgelineSectionHeader(title = stringResource(R.string.game_progress_section_title))
						}
						item(key = "progress-points") { PointsCard(hub.pointsToday) }
						item(key = "progress-exploration") {
							if (explorationState == null) LoadingGameCard() else ExplorationCard(state = explorationState)
						}
						item(key = "progress-achievements") {
							if (achievementState == null) {
								LoadingGameCard()
							} else {
								AchievementCard(state = achievementState, onViewAll = onViewAllAchievements)
							}
						}
					}
					GameHubSection.HISTORY -> item(key = "history-link") {
						MiniGameScoresLink(
							onClick = onViewMiniGameScores,
							modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg).fillMaxWidth(),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun LeaderboardSection(
	state: LeaderboardUiState,
	onMetricSelected: (LeaderboardMetric) -> Unit,
	onRetry: () -> Unit,
) {
	when (state) {
		LeaderboardUiState.Loading -> LoadingGameCard()
		is LeaderboardUiState.Ready -> WeeklyLeaderboardCard(
			state = state.state,
			onMetricSelected = onMetricSelected,
			modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg),
		)
		LeaderboardUiState.Error -> WeeklyLeaderboardErrorCard(
			onRetry = onRetry,
			modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg),
		)
	}
}

@Composable
private fun ActiveSessionCard(active: ActiveGameSessionUi) {
	GlassCard(modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg).fillMaxWidth()) {
		Column {
			Text(
				text = stringResource(active.gameNameRes),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = stringResource(
					if (active.isPaused) {
						R.string.minigame_active_session_paused
					} else {
						R.string.minigame_active_session_running
					},
				),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun MiniGameScoresLink(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	TextButton(onClick = onClick, modifier = modifier) {
		Text(stringResource(R.string.minigame_view_past_scores))
	}
}

@Composable
private fun PointsCard(points: Int) {
	GlassCard(modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg).fillMaxWidth()) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
				Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
			}
			Column(Modifier.padding(start = 16.dp)) {
				Text(NumberFormat.getIntegerInstance().format(points), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
				Text(stringResource(R.string.points_earned_today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

@Composable
private fun StepsCard(steps: StepsSummaryUi, stepCounterSupported: Boolean) {
	GlassCard(modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg).fillMaxWidth()) {
		Column {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
				Text(stringResource(R.string.game_steps_goals_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 12.dp))
			}
			if (!stepCounterSupported && steps.stepsToday <= 0 && steps.stepsWeek <= 0) {
				Text(stringResource(R.string.game_step_sensor_unavailable), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
				Text(stringResource(R.string.game_step_sensor_unavailable_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
			} else {
				Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
					Stat(stringResource(R.string.game_steps_today), steps.stepsToday, steps.goalDay)
					Stat(stringResource(R.string.game_steps_week), steps.stepsWeek, steps.goalWeek)
				}
			}
		}
	}
}

@Composable
private fun LoadingGameCard() {
	GlassCard(modifier = Modifier.padding(horizontal = RidgelineSpacing.Lg).fillMaxWidth()) {
		Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
			Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
				androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
				Text(stringResource(R.string.game_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

@Composable
private fun Stat(label: String, value: Int, goal: Int) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(if (goal > 0) "%,d / %,d".format(value, goal) else "%,d".format(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
		val progress = if (goal > 0) (value.toFloat() / goal).coerceIn(0f, 1f) else 0f
		Box(modifier = Modifier.padding(top = 8.dp).width(80.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)).semantics { progressBarRangeInfo = ProgressBarRangeInfo(current = progress, range = 0f..1f) }) {
			Box(modifier = Modifier.fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.primary))
		}
	}
}
