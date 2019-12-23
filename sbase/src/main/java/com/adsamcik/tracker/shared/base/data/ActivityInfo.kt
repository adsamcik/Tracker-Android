package com.adsamcik.tracker.shared.base.data

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Ignore
import com.adsamcik.tracker.shared.base.R
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
	constructor(detectedActivity: com.google.android.gms.location.DetectedActivity) : this(
			detectedActivity.type,
			detectedActivity.confidence
	)

	constructor(detectedActivity: DetectedActivity, confidence: Int) : this(
			detectedActivity.value,
			confidence
	)

	val activity: DetectedActivity get() = DetectedActivity.fromDetectedType(activityType)

	@IgnoredOnParcel
	@Ignore
	val groupedActivity: GroupedActivity = activity.groupedActivity

	constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readInt())

	/**
	 * Shortcut function for static version of this function
	 *
	 * @return Localized name of the resolved activity
	 */
	fun getGroupedActivityName(context: Context): String = getGroupedActivityName(
			context,
			groupedActivity
	)

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
