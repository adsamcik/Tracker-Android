package com.adsamcik.tracker.shared.base.data

import android.content.Context
import com.adsamcik.tracker.shared.base.R

@Suppress("unused")
enum class NativeSessionActivity(val id: Long) {
	// On-foot movement
	WALKING(-2) {
		override val nameRes: Int = R.string.activity_walking
		override val iconName: String = R.drawable::ic_directions_walk_white.name
	},
	RUNNING(-3) {
		override val nameRes: Int = R.string.activity_running
		override val iconName: String = R.drawable::ic_outline_directions_run_24px.name
	},
	
	// Cycling
	BICYCLE(-4) {
		override val nameRes: Int = R.string.activity_bicycle
		override val iconName: String = R.drawable::ic_baseline_directions_bike.name
	},
	
	// Generic vehicle (when type cannot be determined)
	VEHICLE(-5) {
		override val nameRes: Int = R.string.activity_vehicle
		override val iconName: String = R.drawable::ic_directions_car_white_24dp.name
	},
	
	// Slope sports (skiing, skating, etc.)
	SLOPE_SPORTS(-22) {
		override val nameRes: Int = R.string.activity_slope_sports
		override val iconName: String = R.drawable::ic_icons8_skiing.name
	},
	
	// Land vehicles (cars, buses, trains, motorcycles, etc.)
	LAND_VEHICLE(-34) {
		override val nameRes: Int = R.string.activity_land_vehicle
		override val iconName: String = R.drawable::ic_directions_car_white_24dp.name
	},
	
	// Water vehicles (boats, ships, ferries, etc.)
	WATER_VEHICLE(-26) {
		override val nameRes: Int = R.string.activity_water_vehicle
		override val iconName: String = R.drawable::sailing.name
	},
	
	// Air vehicles (planes, helicopters, etc.)
	AIR_VEHICLE(-31) {
		override val nameRes: Int = R.string.activity_air_vehicle
		override val iconName: String = R.drawable::airplane.name
	};

	/**
	 * Resource id of name for native session activity.
	 */
	abstract val nameRes: Int

	/**
	 * Name of the icon, Allows for dynamic retrieval.
	 */
	abstract val iconName: String

	fun getSessionActivity(context: Context): SessionActivity {
		return SessionActivity(id, context.getString(nameRes), iconName)
	}
}
