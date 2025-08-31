package com.adsamcik.tracker.statistics.extension

import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.preferences.type.LengthSystem

/**
 * Extension functions for determining appropriate length systems based on session activities.
 */

/**
 * Determines if a session activity is related to sailing.
 */
fun SessionActivity.isSailingActivity(): Boolean {
	return id == NativeSessionActivity.WATER_VEHICLE.id ||
		   name.contains("sail", ignoreCase = true) ||
		   name.contains("boat", ignoreCase = true) ||
		   name.contains("ship", ignoreCase = true) ||
		   name.contains("yacht", ignoreCase = true)
}

/**
 * Determines if a session activity is related to flying.
 */
fun SessionActivity.isFlyingActivity(): Boolean {
	return id == NativeSessionActivity.AIR_VEHICLE.id ||
		   name.contains("fly", ignoreCase = true) ||
		   name.contains("flight", ignoreCase = true) ||
		   name.contains("plane", ignoreCase = true) ||
		   name.contains("aircraft", ignoreCase = true) ||
		   name.contains("helicopter", ignoreCase = true) ||
		   name.contains("balloon", ignoreCase = true) ||
		   name.contains("glider", ignoreCase = true)
}

/**
 * Returns the appropriate length system for this session activity,
 * or null if no specific system is required.
 */
fun SessionActivity.getPreferredLengthSystem(): LengthSystem? {
	return when {
		isSailingActivity() -> LengthSystem.Sailing
		isFlyingActivity() -> LengthSystem.Flying
		else -> null
	}
}
