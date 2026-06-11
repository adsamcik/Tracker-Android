package com.adsamcik.tracker.shared.preferences.extension

import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.preferences.type.LengthSystem

/**
 * Extension functions for determining appropriate length systems based on session activities.
 */

/**
 * Determines if a session activity is related to maritime/water activities.
 * These activities typically use nautical units for distance measurement.
 */
fun SessionActivity.isMaritimeActivity(): Boolean {
	return id == NativeSessionActivity.WATER_VEHICLE.id ||
		   // Check for custom activities with maritime-related names
		   name.contains("sail", ignoreCase = true) ||
		   name.contains("boat", ignoreCase = true) ||
		   name.contains("ship", ignoreCase = true) ||
		   name.contains("yacht", ignoreCase = true) ||
		   name.contains("ferry", ignoreCase = true) ||
		   name.contains("canoe", ignoreCase = true) ||
		   name.contains("kayak", ignoreCase = true) ||
		   name.contains("rowing", ignoreCase = true) ||
		   name.contains("water", ignoreCase = true)
}

/**
 * Determines if a session activity is related to aviation/flying activities.
 * These activities typically use flight levels for altitude and nautical miles for distance.
 */
fun SessionActivity.isAviationActivity(): Boolean {
	return id == NativeSessionActivity.AIR_VEHICLE.id ||
		   // Check for custom activities with aviation-related names
		   name.contains("fly", ignoreCase = true) ||
		   name.contains("flight", ignoreCase = true) ||
		   name.contains("plane", ignoreCase = true) ||
		   name.contains("aircraft", ignoreCase = true) ||
		   name.contains("helicopter", ignoreCase = true) ||
		   name.contains("balloon", ignoreCase = true) ||
		   name.contains("glider", ignoreCase = true) ||
		   name.contains("aviation", ignoreCase = true) ||
		   name.contains("air", ignoreCase = true)
}

/**
 * Returns the appropriate length system for this session activity,
 * or null if the default system should be used.
 */
fun SessionActivity.getPreferredLengthSystem(): LengthSystem? {
	return when {
		isMaritimeActivity() -> LengthSystem.Sailing
		isAviationActivity() -> LengthSystem.Flying
		else -> null
	}
}
