package com.adsamcik.signalcollector.common.database.converter

import androidx.room.TypeConverter
import com.adsamcik.signalcollector.common.data.DetectedActivity

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