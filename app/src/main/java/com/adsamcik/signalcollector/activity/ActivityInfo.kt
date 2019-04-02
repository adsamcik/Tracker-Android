package com.adsamcik.signalcollector.activity

import android.content.Context
import androidx.room.Ignore
import com.adsamcik.signalcollector.R
import com.google.android.gms.location.DetectedActivity

/**
 * Class containing information about activity.
 * It stores original activity as well as resolved activity and confidence.
 *
 * Using [getGroupedActivityName]
 */
class ActivityInfo(val activity: Int, val confidence: Int) {
	constructor(detectedActivity: DetectedActivity) : this(detectedActivity.type, detectedActivity.confidence)

	@Ignore
	val groupedActivity: GroupedActivity = resolveActivity(activity)

	/**
	 * Shortcut function for static version of this function
	 *
	 * @return Localized name of the resolved activity
	 */
	fun getGroupedActivityName(context: Context): String = getGroupedActivityName(context, groupedActivity)


	companion object {

		/**
		 * 0 still/default
		 * 1 foot
		 * 2 vehicle
		 * 3 tilting
		 */
		private fun resolveActivity(activity: Int): GroupedActivity = when (activity) {
			DetectedActivity.STILL -> GroupedActivity.STILL
			DetectedActivity.RUNNING -> GroupedActivity.ON_FOOT
			DetectedActivity.ON_FOOT -> GroupedActivity.ON_FOOT
			DetectedActivity.WALKING -> GroupedActivity.ON_FOOT
			DetectedActivity.ON_BICYCLE -> GroupedActivity.IN_VEHICLE
			DetectedActivity.IN_VEHICLE -> GroupedActivity.IN_VEHICLE
			DetectedActivity.TILTING -> GroupedActivity.UNKNOWN
			else -> GroupedActivity.UNKNOWN
		}

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
