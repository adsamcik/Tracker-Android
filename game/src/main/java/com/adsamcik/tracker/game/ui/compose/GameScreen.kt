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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSectionHeader
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.rememberMainNavigationLayout
import java.text.NumberFormat

data class StepsSummaryUi(
	val stepsToday: Int,
	val stepsWeek: Int,
	val goalDay: Int,
	val goalWeek: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
	pointsToday: Int? = null,
	steps: StepsSummaryUi? = null,
	miniGameEntries: List<MiniGameEntry>? = null,
	explorationState: ExplorationState? = null,
	achievementState: AchievementSummaryState? = null,
	modifier: Modifier = Modifier,
	onViewAllAchievements: () -> Unit = {},
	onOpenSettings: () -> Unit = {},
	@Suppress("UNUSED_PARAMETER") onNavigateToTracker: () -> Unit = {},
) {
	val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
	val layoutDirection = LocalLayoutDirection.current
	val horizontalInsetStart = safeDrawingPadding.calculateStartPadding(layoutDirection)
	val horizontalInsetEnd = safeDrawingPadding.calculateEndPadding(layoutDirection)
	val safeBottomPadding = safeDrawingPadding.calculateBottomPadding()
	val navigationLayout = rememberMainNavigationLayout()
	// MainRoot.kt:316 reserves `96.dp + navBarInset` at the NavHost level for the
	// floating navigation bar. The pill itself is 80dp tall + 12dp top breathing
	// padding = 92dp of visible bottom area (plus the system nav inset). The
	// remaining 4dp residual sits at the bottom of the NavHost.
	//
	// Previously this screen added `AppDimensions.FloatingNavBarClearance` (120dp)
	// + system insets on top, triple-counting and producing ~248dp of dead space
	// below the last item. Now we only add a 28dp visual margin so the last item
	// (e.g. the tier-badge label row on the achievement card) has clear breathing
	// room above the pill's haze/border instead of being clipped behind it.
	//
	// SideRail layout has no floating bottom nav (rail is on the side), so MainRoot
	// doesn't reserve anything; we handle the system inset ourselves here.
	val bottomClearance = if (navigationLayout == MainNavigationLayout.SideRail) {
		safeBottomPadding + 24.dp
	} else {
		28.dp
	}
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
				// bottom inset is already covered by MainRoot's NavHost-level reservation
				// for BottomBar mode, and we add a SideRail-aware bottom contentPadding
				// below — applying innerPadding.bottom here would double-count system inset.
				.padding(top = innerPadding.calculateTopPadding())
				.padding(start = horizontalInsetStart, end = horizontalInsetEnd),
			contentPadding = PaddingValues(top = RidgelineSpacing.Lg, bottom = bottomClearance),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			item { if (pointsToday == null) LoadingGameCard() else PointsCard(pointsToday) }
			item {
				val context = LocalContext.current
				val stepCounterSupported = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) }
				if (steps == null) LoadingGameCard() else StepsCard(steps, stepCounterSupported)
			}
			item { if (explorationState == null) LoadingGameCard() else ExplorationCard(state = explorationState) }
			item { RidgelineSectionHeader(title = stringResource(R.string.game_achievement_progress_title)) }
			item { if (achievementState == null) LoadingGameCard() else AchievementCard(state = achievementState, onViewAll = onViewAllAchievements) }
			item { RidgelineSectionHeader(title = stringResource(R.string.minigame_section_title)) }
			item {
				if (miniGameEntries == null) {
					LoadingGameCard()
				} else {
					MiniGamesGrid(
						games = miniGameEntries.map { entry ->
							MiniGameUi(entry.id, stringResource(entry.nameRes), stringResource(entry.descriptionRes), entry.unlockLevel, entry.isUnlocked, entry.isAvailable)
						},
					)
				}
			}
		}
	}
}

@Composable
private fun PointsCard(points: Int) {
	GlassCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
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
	GlassCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
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
	GlassCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
		Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
			Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
				CircularProgressIndicator(modifier = Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
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
