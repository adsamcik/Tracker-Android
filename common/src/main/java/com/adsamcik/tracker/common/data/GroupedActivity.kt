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
		override val iconRes: Int = R.drawable.ic_directions_walk_white_24dp
	},
	IN_VEHICLE {
		override val iconRes: Int = R.drawable.ic_directions_car_white_24dp
	},
	UNKNOWN {
		override val iconRes: Int = R.drawable.ic_help_white_24dp
	};

	val isIdle: Boolean get() = this == UNKNOWN || this == STILL
	abstract val iconRes: Int
}

