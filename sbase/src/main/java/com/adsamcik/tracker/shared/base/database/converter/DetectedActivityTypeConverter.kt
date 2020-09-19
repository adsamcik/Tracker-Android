package com.adsamcik.tracker.shared.base.database.converter

import androidx.room.TypeConverter
import com.adsamcik.tracker.shared.base.data.DetectedActivity

class DetectedActivityTypeConverter {
	@TypeConverter
	fun toDetectedActivity(value: Int): DetectedActivity {
		return DetectedActivity.fromDetectedType(value)
	}

	@TypeConverter
	fun fromDetectedActivity(value: DetectedActivity): Int {
		return value.value
	}
}
