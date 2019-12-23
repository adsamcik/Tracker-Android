package com.adsamcik.tracker.common.data

import com.adsamcik.tracker.common.R

enum class DetectedActivity(val value: Int) {
	STILL(com.google.android.gms.location.DetectedActivity.STILL) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.STILL

		override val nameRes: Int
			get() = R.string.activity_raw_still
	},
	RUNNING(com.google.android.gms.location.DetectedActivity.RUNNING) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.ON_FOOT

		override val nameRes: Int
			get() = R.string.activity_run
	},
	ON_FOOT(com.google.android.gms.location.DetectedActivity.ON_FOOT) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.ON_FOOT

		override val nameRes: Int
			get() = R.string.activity_on_foot
	},
	ON_BICYCLE(com.google.android.gms.location.DetectedActivity.ON_BICYCLE) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.IN_VEHICLE

		override val nameRes: Int
			get() = R.string.activity_bicycle
	},
	IN_VEHICLE(com.google.android.gms.location.DetectedActivity.IN_VEHICLE) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.IN_VEHICLE

		override val nameRes: Int
			get() = R.string.activity_in_vehicle
	},
	TILTING(com.google.android.gms.location.DetectedActivity.TILTING) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.UNKNOWN

		override val nameRes: Int
			get() = R.string.activity_raw_tilting
	},
	UNKNOWN(com.google.android.gms.location.DetectedActivity.UNKNOWN) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.UNKNOWN

		override val nameRes: Int
			get() = R.string.activity_unknown
	},
	WALKING(com.google.android.gms.location.DetectedActivity.WALKING) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.ON_FOOT

		override val nameRes: Int
			get() = R.string.activity_walk
	};

	abstract val groupedActivity: GroupedActivity

	abstract val nameRes: Int

	companion object {
		fun fromDetectedType(type: Int): DetectedActivity {
			return values().find { it.value == type }
					?: throw IllegalArgumentException("Activity type with value $type is not defined.")
		}
	}
}
