package com.adsamcik.signalcollector.utility

import android.content.Context
import androidx.room.Ignore
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.ResolvedActivities
import com.google.android.gms.location.DetectedActivity

/**
 * Class containing information about activity.
 * It stores original activity as well as resolved activity and confidence.
 *
 * Using [getResolvedActivityName]
 */
class ActivityInfo(val activity: Int, val confidence: Int) {
    constructor(detectedActivity: DetectedActivity) : this(detectedActivity.type, detectedActivity.confidence)

    @ResolvedActivities.ResolvedActivity
    @Ignore
    val resolvedActivity: Int

    init {
        this.resolvedActivity = resolveActivity(activity)
    }

    /**
     * Shortcut function for static version of this function
     *
     * @return Localized name of the resolved activity
     */
    fun getResolvedActivityName(context: Context) = getResolvedActivityName(context, resolvedActivity)


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
