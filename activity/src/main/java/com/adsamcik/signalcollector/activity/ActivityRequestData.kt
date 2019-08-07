package com.adsamcik.signalcollector.activity

import android.content.Context
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.DetectedActivity
import com.google.android.gms.location.ActivityTransition
import kotlin.reflect.KClass

typealias ActivityRequestCallback = (context: Context, activity: ActivityInfo, elapsedTime: Long) -> Unit

data class ActivityRequestData(val key: KClass<*>,
                               val detectionIntervalS: Int,
                               val transitionList: Collection<ActivityTransitionData>,
                               val callback: ActivityRequestCallback)

data class ActivityTransitionData(val activity: DetectedActivity, val type: ActivityTransitionType)

enum class ActivityTransitionType(val value: Int) {
	ENTER(ActivityTransition.ACTIVITY_TRANSITION_ENTER),
	EXIT(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
}