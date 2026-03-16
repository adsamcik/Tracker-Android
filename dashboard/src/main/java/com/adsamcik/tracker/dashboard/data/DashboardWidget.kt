package com.adsamcik.tracker.dashboard.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.dashboard.R

/**
 * Sealed interface representing a widget that can appear in the idle dashboard.
 *
 * Each widget has a stable [id] for persistence, a [defaultPriority] for initial
 * ordering (lower = higher on screen), and a [titleRes] for display in the
 * customize sheet.
 */
@Immutable
sealed interface DashboardWidget {
	val id: String
	val defaultPriority: Int

	@get:StringRes
	val titleRes: Int

	@Immutable
	data object TodayProgress : DashboardWidget {
		override val id: String = "today_progress"
		override val defaultPriority: Int = 0
		override val titleRes: Int = R.string.widget_today_progress
	}

	@Immutable
	data object Streak : DashboardWidget {
		override val id: String = "streak"
		override val defaultPriority: Int = 10
		override val titleRes: Int = R.string.widget_streak
	}

	@Immutable
	data object Challenges : DashboardWidget {
		override val id: String = "challenges"
		override val defaultPriority: Int = 20
		override val titleRes: Int = R.string.widget_challenges
	}

	@Immutable
	data object LastSession : DashboardWidget {
		override val id: String = "last_session"
		override val defaultPriority: Int = 30
		override val titleRes: Int = R.string.widget_last_session
	}

	@Immutable
	data object RecentTrips : DashboardWidget {
		override val id: String = "recent_trips"
		override val defaultPriority: Int = 40
		override val titleRes: Int = R.string.widget_recent_trips
	}

	@Immutable
	data object Exploration : DashboardWidget {
		override val id: String = "exploration"
		override val defaultPriority: Int = 50
		override val titleRes: Int = R.string.widget_exploration
	}

	companion object {
		/** All known widgets in default priority order. */
		val all: List<DashboardWidget> = listOf(
			TodayProgress,
			Streak,
			Challenges,
			LastSession,
			RecentTrips,
			Exploration,
		)

		/** Look up a widget by its stable [id]. Returns null for unknown IDs. */
		fun fromId(id: String): DashboardWidget? = all.find { it.id == id }

		/** Default widget order as a list of IDs. */
		val defaultOrder: List<String> = all.map { it.id }
	}
}
