package com.adsamcik.tracker.shared.base.database.converter

import androidx.room.TypeConverter
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType

/**
 * Type converters for sessionless tracking enums.
 */
class SessionlessTypeConverter {
	
	@TypeConverter
	fun toSampleQuality(value: String): SampleQuality {
		return SampleQuality.entries.firstOrNull { it.name == value } ?: SampleQuality.COARSE
	}

	@TypeConverter
	fun fromSampleQuality(value: SampleQuality): String {
		return value.name
	}

	@TypeConverter
	fun toMotionState(value: String): MotionState {
		return MotionState.valueOf(value)
	}

	@TypeConverter
	fun fromMotionState(value: MotionState): String {
		return value.name
	}

	@TypeConverter
	fun toCoordinateProvenance(value: String): CoordinateProvenance {
		return CoordinateProvenance.valueOf(value)
	}

	@TypeConverter
	fun fromCoordinateProvenance(value: CoordinateProvenance): String {
		return value.name
	}

	@TypeConverter
	fun toSegmentSource(value: String): SegmentSource {
		return SegmentSource.valueOf(value)
	}

	@TypeConverter
	fun fromSegmentSource(value: SegmentSource): String {
		return value.name
	}

	@TypeConverter
	fun toSkiSegmentType(value: String): SkiSegmentType {
		return SkiSegmentType.valueOf(value)
	}

	@TypeConverter
	fun fromSkiSegmentType(value: SkiSegmentType): String {
		return value.name
	}
}
