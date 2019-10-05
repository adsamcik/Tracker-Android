package com.adsamcik.tracker.common.data

import com.adsamcik.tracker.common.R

/**
 * Singleton that contains [GroupedActivity]. It is made this way so even the constants are contained within an object.
 */
enum class GroupedActivity {
	STILL {
		override val iconRes: Int = R.drawable.ic_still
	},
	ON_FOOT {
		override val iconRes: Int = R.drawable.ic_directions_walk_white
	},
	IN_VEHICLE {
		override val iconRes: Int = R.drawable.ic_baseline_commute
	},
	UNKNOWN {
		override val iconRes: Int = R.drawable.ic_help_white
	};

	val isIdle: Boolean get() = this == UNKNOWN || this == STILL
	abstract val iconRes: Int
}

