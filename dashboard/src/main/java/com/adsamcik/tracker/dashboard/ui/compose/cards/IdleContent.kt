package com.adsamcik.tracker.dashboard.ui.compose.cards

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.unit.Dp
import android.util.Log
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.data.DashboardWidget
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.components.MotivationalText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.rememberContentColumnCount
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.data.achievement.AchievementFormatting

/**
 * Idle dashboard content shown when the user is NOT tracking.
 *
 * Displays daily summary, streaks, achievements, last session,
 * recent trips, and exploration in a scrollable column.
 *
 * Widget ordering and visibility is driven by [DashboardWidgetRegistry]
 * using the persisted [DashboardLayout]. The motivational text is always
 * pinned at the top and is not part of the widget system.
 */
@Composable
internal fun IdleContent(
	state: DashboardUiState,
	widgets: List<DashboardWidget>,
	listState: LazyListState = rememberLazyListState(),
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onSessionDetailClick: ((Long) -> Unit)?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val configuration = LocalConfiguration.current
	val columns = rememberContentColumnCount()
	val contentPadding = PaddingValues(start = RidgelineSpacing.Lg, end = RidgelineSpacing.Lg, bottom = bottomClearance)
	val itemSpacing = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) RidgelineSpacing.Sm else RidgelineSpacing.Md

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
			if (!state.hasLocationPermission) {
				item(key = "permission_banner") {
					LocationPermissionBanner(onRequestPermission = onRequestPermission)
				}
			}
			renderWidgets(
				widgets = widgets,
				state = state,
				onMapClick = onMapClick,
				onGameClick = onGameClick,
				onSessionDetailClick = onSessionDetailClick,
				onToggleTracking = onToggleTracking,
				onRequestPermission = onRequestPermission,
			)
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
			if (!state.hasLocationPermission) {
				item(
					key = "permission_banner",
					span = { GridItemSpan(columns) },
				) {
					LocationPermissionBanner(onRequestPermission = onRequestPermission)
				}
			}
			renderGridWidgets(
				widgets = widgets,
				columns = columns,
				state = state,
				onMapClick = onMapClick,
				onGameClick = onGameClick,
				onSessionDetailClick = onSessionDetailClick,
				onToggleTracking = onToggleTracking,
				onRequestPermission = onRequestPermission,
			)
		}
	}
}

/**
 * Renders widgets dynamically in a LazyColumn based on the resolved widget list.
 */
private fun LazyListScope.renderWidgets(
	widgets: List<DashboardWidget>,
	state: DashboardUiState,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onSessionDetailClick: ((Long) -> Unit)?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
) {
	widgets.forEach { widget ->
		item(key = widget.id) {
			WidgetContent(
				widget = widget,
				state = state,
				onMapClick = onMapClick,
				onGameClick = onGameClick,
				onSessionDetailClick = onSessionDetailClick,
				onToggleTracking = onToggleTracking,
				onRequestPermission = onRequestPermission,
				modifier = Modifier.animateContentSize(),
			)
		}
	}
}

/**
 * Renders widgets dynamically in a LazyVerticalGrid with appropriate spans.
 */
private fun LazyGridScope.renderGridWidgets(
	widgets: List<DashboardWidget>,
	columns: Int,
	state: DashboardUiState,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onSessionDetailClick: ((Long) -> Unit)?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
) {
	widgets.forEach { widget ->
		val span = when (widget) {
			DashboardWidget.TodayProgress,
			DashboardWidget.LatestAchievement,
			-> GridItemSpan(columns)
			else -> GridItemSpan(1)
		}
		item(key = widget.id, span = { span }) {
			WidgetContent(
				widget = widget,
				state = state,
				onMapClick = onMapClick,
				onGameClick = onGameClick,
				onSessionDetailClick = onSessionDetailClick,
				onToggleTracking = onToggleTracking,
				onRequestPermission = onRequestPermission,
			)
		}
	}
}

/**
 * Dispatches rendering to the appropriate card composable for each widget type.
 * Existing card composables are invoked unchanged.
 */
@Composable
private fun WidgetContent(
	widget: DashboardWidget,
	state: DashboardUiState,
	onMapClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onSessionDetailClick: ((Long) -> Unit)?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	when (widget) {
		DashboardWidget.TodayProgress -> TodayProgressCard(
			state = state,
			onToggleTracking = onToggleTracking,
			onRequestPermission = onRequestPermission,
			modifier = modifier,
		)
		DashboardWidget.Streak -> StreakBanner(
			streakState = state.streakState,
			onClick = onGameClick,
			modifier = modifier,
		)
		DashboardWidget.LatestAchievement -> LatestAchievementCard(state = state, modifier = modifier)
		DashboardWidget.LastSession -> {
			val session = state.sessionData
			if (session != null) {
				Column {
					LastSessionCard(
						session = session,
						pathPoints = state.pathPoints,
						onMapClick = onMapClick,
						onSessionDetailClick = onSessionDetailClick,
						modifier = modifier,
					)
					if (state.sessionInsights.isNotEmpty()) {
						Spacer(modifier = Modifier.height(8.dp))
						SessionInsightsCard(
							insights = state.sessionInsights,
							modifier = modifier,
						)
					}
				}
			}
		}
		DashboardWidget.RecentTrips -> RecentTripsCard(
			trips = state.recentTrips,
			onTripClick = onSessionDetailClick,
			modifier = modifier,
		)
		DashboardWidget.Exploration -> ExplorationCard(
			explorationState = state.explorationState,
			modifier = modifier,
		)
	}
}
@Composable
private fun LatestAchievementCard(state: DashboardUiState, modifier: Modifier = Modifier) {
	GlassCard(modifier = modifier) {
		Column {
			Text(
				text = stringResource(R.string.dashboard_latest_achievement_title),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(modifier = Modifier.height(8.dp))
			val latest = state.latestAchievement
			// Resolve the live AchievementDefinition from the id so we can
			// render through the canonical AchievementFormatting (now in
			// :stats-data). This produces correctly localised titles like
			// "First session" / "Travel 5 km" / "%d-day streak" instead of
			// falling through resolveStringResource to humanizeResourceKey
			// (R8 r8-dashboard-use-achievementformatting).
			val title = latest?.let { ui ->
				val definition = AchievementCatalog.byId(ui.id)
				if (definition != null) {
					val context = LocalContext.current
					remember(definition.id, context) {
						AchievementFormatting.formatTitle(context, definition)
					}
				} else {
					// Fallback path retained for catalog/persistence drift
					// (e.g. a stale row referring to an id that was removed).
					resolveStringResource(ui.nameRes)
				}
			} ?: stringResource(R.string.dashboard_latest_achievement_empty)
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun resolveStringResource(name: String): String {
	val context = LocalContext.current
	val resId = remember(name, context) { context.resources.getIdentifier(name, "string", context.packageName) }
	return if (resId != 0) {
		stringResource(resId)
	} else {
		// Reached only when AchievementCatalog.byId(...) returns null for the
		// stored achievement id — meaning the catalog changed since the row
		// was persisted. Log so we can detect catalog drift, and show a
		// humanized label so the user never sees a raw resource key.
		LaunchedEffect(name) {
			Log.w(
				TAG_IDLE_CONTENT,
				"achievement id not in catalog; missing string resource '$name'; humanized fallback in use",
			)
		}
		humanizeResourceKey(name)
	}
}

private const val TAG_IDLE_CONTENT = "IdleContent"

@VisibleForTesting
internal fun humanizeResourceKey(name: String): String {
	val core = name
		.removePrefix("achievement_")
		.removeSuffix("_title")
		.removeSuffix("_desc")
		.removeSuffix("_description")
	if (core.isBlank()) return name
	return core.split('_')
		.filter { it.isNotEmpty() }
		.joinToString(" ") { token ->
			token.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
		}
}

