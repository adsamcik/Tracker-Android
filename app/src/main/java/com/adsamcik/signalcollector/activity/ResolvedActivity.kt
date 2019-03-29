package com.adsamcik.signalcollector.activity

import androidx.annotation.IntDef
import com.adsamcik.signalcollector.activity.ResolvedActivities.ResolvedActivity

/**
 * Singleton that contains [ResolvedActivity]. It is made this way so even the constants are contained within an object.
 */
object ResolvedActivities {
    const val STILL = 0
    const val ON_FOOT = 1
    const val IN_VEHICLE = 2
    const val UNKNOWN = 3

    @IntDef(STILL, ON_FOOT, IN_VEHICLE, UNKNOWN)
    @Retention(AnnotationRetention.SOURCE)
    annotation class ResolvedActivity
}
