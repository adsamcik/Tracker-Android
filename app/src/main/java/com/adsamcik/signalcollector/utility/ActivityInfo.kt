package com.adsamcik.signalcollector.utility

import android.content.Context

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.ResolvedActivity
import com.google.android.gms.location.DetectedActivity

class ActivityInfo(val activity: Int, val confidence: Int) {
    @ResolvedActivity
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
        @ResolvedActivity
        private fun resolveActivity(activity: Int): Int = when (activity) {
            DetectedActivity.STILL -> ResolvedActivity.STILL
            DetectedActivity.RUNNING -> ResolvedActivity.ON_FOOT
            DetectedActivity.ON_FOOT -> ResolvedActivity.ON_FOOT
            DetectedActivity.WALKING -> ResolvedActivity.ON_FOOT
            DetectedActivity.ON_BICYCLE -> ResolvedActivity.IN_VEHICLE
            DetectedActivity.IN_VEHICLE -> ResolvedActivity.IN_VEHICLE
            DetectedActivity.TILTING -> ResolvedActivity.UNKNOWN
            else -> ResolvedActivity.UNKNOWN
        }

        fun getActivityName(activity: Int): String =
                when (activity) {
                    DetectedActivity.IN_VEHICLE -> "In Vehicle"
                    DetectedActivity.ON_BICYCLE -> "On Bicycle"
                    DetectedActivity.ON_FOOT -> "On Foot"
                    DetectedActivity.WALKING -> "Walking"
                    DetectedActivity.STILL -> "Still"
                    DetectedActivity.TILTING -> "Tilting"
                    DetectedActivity.RUNNING -> "Running"
                    DetectedActivity.UNKNOWN -> "Unknown"
                    else -> "N/A"
                }

        fun getResolvedActivityName(context: Context, @ResolvedActivity resolvedActivity: Int): String =
                when (resolveActivity(resolvedActivity)) {
                    ResolvedActivity.STILL -> context.getString(R.string.activity_idle)
                    ResolvedActivity.ON_FOOT -> context.getString(R.string.activity_on_foot)
                    ResolvedActivity.IN_VEHICLE -> context.getString(R.string.activity_in_vehicle)
                    ResolvedActivity.UNKNOWN -> context.getString(R.string.activity_unknown)
                    else -> context.getString(R.string.activity_unknown)
                }
    }
}
