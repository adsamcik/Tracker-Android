package com.adsamcik.tracker.dashboard.data

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A resolved widget entry ready for rendering in IdleContent.
 *
 * @param widget The widget definition.
 * @param visible Whether the user has this widget enabled (not hidden).
 */
@Immutable
data class ResolvedWidget(
	val widget: DashboardWidget,
	val visible: Boolean,
)

/**
 * Registry that resolves which widgets should appear in the idle dashboard,
 * in what order, and whether they are available given the current UI state.
 *
 * Handles:
 * - User-defined ordering from [DashboardLayout]
 * - User-defined visibility (hidden widgets)
 * - Conditional availability based on [DashboardUiState]
 *   (e.g., LatestAchievement uses an empty state, LastSession only if session data exists)
 */
@Singleton
class DashboardWidgetRegistry @Inject constructor(
	private val layoutRepository: DashboardLayoutRepository,
) {

	val layout = layoutRepository.layout

	/**
	 * Resolve the visible, ordered list of widgets for the idle dashboard.
	 *
	 * @param state Current dashboard UI state (used for conditional inclusion).
	 * @param layout Current user layout preferences.
	 * @return Ordered list of widgets that should be rendered.
	 */
	fun resolveWidgets(
		state: DashboardUiState,
		layout: DashboardLayout,
	): List<DashboardWidget> {
		val customOrder = layout.widgetOrder.withIndex().associate { it.value to it.index }
		return DashboardWidget.all
			.filter { widget -> isAvailable(widget, state) }
			.filter { widget -> widget.id !in layout.hiddenWidgets }
			.sortedBy { widget ->
				customOrder[widget.id] ?: (widget.defaultPriority + DashboardWidget.all.size)
			}
	}

	/**
	 * Resolve all widgets with their visibility and availability info,
	 * for use in the customize sheet.
	 *
	 * @param state Current dashboard UI state.
	 * @param layout Current user layout preferences.
	 * @return Ordered list of all known widgets with resolved metadata.
	 */
	fun resolveAllWidgets(
		layout: DashboardLayout,
	): List<ResolvedWidget> {
		val customOrder = layout.widgetOrder.withIndex().associate { it.value to it.index }
		return DashboardWidget.all
			.sortedBy { widget ->
				customOrder[widget.id] ?: (widget.defaultPriority + DashboardWidget.all.size)
			}
			.map { widget ->
				ResolvedWidget(
					widget = widget,
					visible = widget.id !in layout.hiddenWidgets,
				)
			}
	}

	internal fun isAvailable(
		widget: DashboardWidget,
		state: DashboardUiState,
	): Boolean = when (widget) {
		DashboardWidget.LastSession -> state.sessionData != null
		DashboardWidget.Exploration -> state.explorationState.hasExplorationData
		DashboardWidget.TodayProgress,
		DashboardWidget.Streak,
		DashboardWidget.LatestAchievement,
		DashboardWidget.RecentTrips,
		-> true
	}
}
