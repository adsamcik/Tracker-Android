package com.adsamcik.signalcollector.common.data

import android.content.Context
import com.adsamcik.signalcollector.common.R

enum class NativeSessionActivity(val id: Long) {
	WALK(-2) {
		override val nameRes: Int = R.string.activity_walk
		override val iconName: String = R.drawable::ic_directions_walk_white_24dp.name
	},
	RUN(-3) {
		override val nameRes: Int = R.string.activity_run
		override val iconName: String = R.drawable::ic_outline_directions_run_24px.name
	},
	BICYCLE(-4) {
		override val nameRes: Int = R.string.activity_bicycle
		override val iconName: String = R.drawable::ic_baseline_directions_bike.name
	},
	VEHICLE(-5) {
		override val nameRes: Int = R.string.activity_vehicle
		override val iconName: String = R.drawable::ic_directions_car_white_24dp.name
	};

	abstract val nameRes: Int
	abstract val iconName: String


	fun getSessionActivity(context: Context): SessionActivity {
		return SessionActivity(id, context.getString(nameRes), iconName)
	}
}
