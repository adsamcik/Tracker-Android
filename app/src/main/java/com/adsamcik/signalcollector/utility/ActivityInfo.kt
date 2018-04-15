package com.adsamcik.signalcollector.utility

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.ResolvedActivities
import com.adsamcik.signalcollector.utility.ActivityInfo.Companion.getActivityName
import com.adsamcik.signalcollector.utility.ActivityInfo.Companion.getResolvedActivityName
import com.google.android.gms.location.DetectedActivity

/**
 * Class containing information about activity.
 * It stores original activity as well as resolved activity and confidence.
 *
 * Using [getActivityName] and [getResolvedActivityName]
 */
class ActivityInfo(val activity: Int, val confidence: Int) {
    constructor(detectedActivity: DetectedActivity) : this(detectedActivity.type, detectedActivity.confidence)

    @ResolvedActivities.ResolvedActivity
    val resolvedActivity: Int

    val activityName: String
        get() = getActivityName(activity)

    init {
        this.resolvedActivity = resolveActivity(activity)
    }


    companion object {

        /**
         * 0 still/default
         * 1 foot
         * 2 vehicle
         * 3 tilting
         */
        @ResolvedActivities.ResolvedActivity
        private fun resolveActivity(activity: Int): Int = when (activity) {
            DetectedActivity.STILL -> ResolvedActivities.STILL
            DetectedActivity.RUNNING -> ResolvedActivities.ON_FOOT
            DetectedActivity.ON_FOOT -> ResolvedActivities.ON_FOOT
            DetectedActivity.WALKING -> ResolvedActivities.ON_FOOT
            DetectedActivity.ON_BICYCLE -> ResolvedActivities.IN_VEHICLE
            DetectedActivity.IN_VEHICLE -> ResolvedActivities.IN_VEHICLE
            DetectedActivity.TILTING -> ResolvedActivities.UNKNOWN
            else -> ResolvedActivities.UNKNOWN
        }

        /**
         * Returns activity string using [DetectedActivity.toString] method.
         * String might not be localized, it is used mainly for debugging.
         */
        fun getActivityName(activity: Int): String = DetectedActivity(activity, 0).toString()

        /**
         * Returns resolved activity string. String is localized.
         */
        fun getResolvedActivityName(context: Context, @ResolvedActivities.ResolvedActivity resolvedActivity: Int): String =
                when (resolvedActivity) {
                    ResolvedActivities.STILL -> context.getString(R.string.activity_idle)
                    ResolvedActivities.ON_FOOT -> context.getString(R.string.activity_on_foot)
                    ResolvedActivities.IN_VEHICLE -> context.getString(R.string.activity_in_vehicle)
                    ResolvedActivities.UNKNOWN -> context.getString(R.string.activity_unknown)
                    else -> context.getString(R.string.activity_unknown)
                }
    }
}
