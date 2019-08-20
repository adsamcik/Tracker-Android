package com.adsamcik.tracker.common.data

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Ignore
import com.adsamcik.tracker.common.R
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize

/**
 * Class containing information about activity.
 * It stores original activity as well as resolved activity and confidence.
 *
 * Using [getGroupedActivityName]
 */
@Parcelize
data class ActivityInfo(
		@ColumnInfo(name = "activity")
		val activityType: Int,
		val confidence: Int
) : Parcelable {
	constructor(detectedActivity: com.google.android.gms.location.DetectedActivity) : this(detectedActivity.type,
			detectedActivity.confidence)

	constructor(detectedActivity: DetectedActivity, confidence: Int) : this(detectedActivity.value, confidence)

	val activity: DetectedActivity get() = DetectedActivity.fromDetectedType(activityType)

	@IgnoredOnParcel
	@Ignore
	val groupedActivity: GroupedActivity = activity.groupedActivity

	constructor(parcel: Parcel) : this(
			parcel.readInt(),
			parcel.readInt())

	/**
	 * Shortcut function for static version of this function
	 *
	 * @return Localized name of the resolved activity
	 */
	fun getGroupedActivityName(context: Context): String = getGroupedActivityName(context, groupedActivity)

	companion object {
		val UNKNOWN: ActivityInfo get() = ActivityInfo(DetectedActivity.UNKNOWN, 0)
		/**
		 * Returns resolved activity string. String is localized.
		 */
		fun getGroupedActivityName(context: Context, resolvedActivity: GroupedActivity): String =
				when (resolvedActivity) {
					GroupedActivity.STILL -> context.getString(R.string.activity_idle)
					GroupedActivity.ON_FOOT -> context.getString(R.string.activity_on_foot)
					GroupedActivity.IN_VEHICLE -> context.getString(R.string.activity_in_vehicle)
					GroupedActivity.UNKNOWN -> context.getString(R.string.activity_unknown)
				}
	}
}

enum class DetectedActivity(val value: Int) {
	STILL(com.google.android.gms.location.DetectedActivity.STILL) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.STILL
	},
	RUNNING(com.google.android.gms.location.DetectedActivity.RUNNING) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.ON_FOOT
	},
	ON_FOOT(com.google.android.gms.location.DetectedActivity.ON_FOOT) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.ON_FOOT
	},
	ON_BICYCLE(com.google.android.gms.location.DetectedActivity.ON_BICYCLE) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.IN_VEHICLE
	},
	IN_VEHICLE(com.google.android.gms.location.DetectedActivity.IN_VEHICLE) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.IN_VEHICLE
	},
	TILTING(com.google.android.gms.location.DetectedActivity.TILTING) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.UNKNOWN
	},
	UNKNOWN(com.google.android.gms.location.DetectedActivity.UNKNOWN) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.UNKNOWN
	},
	WALKING(com.google.android.gms.location.DetectedActivity.WALKING) {
		override val groupedActivity: GroupedActivity
			get() = GroupedActivity.ON_FOOT
	};

	abstract val groupedActivity: GroupedActivity

	companion object {
		fun fromDetectedType(type: Int): DetectedActivity {
			return values().find { it.value == type }
					?: throw IllegalArgumentException("Activity type with value $type is not defined.")
		}
	}
}

