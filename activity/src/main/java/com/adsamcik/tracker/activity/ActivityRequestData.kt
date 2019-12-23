package com.adsamcik.tracker.activity

import android.content.Context
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.google.android.gms.location.ActivityTransition
import kotlin.reflect.KClass

typealias ActivityChangeRequestCallback = (context: Context, activity: ActivityInfo, elapsedTime: Long) -> Unit
typealias ActivityTransitionRequestCallback =
		(context: Context, activity: ActivityTransitionData, elapsedTime: Long) -> Unit

data class ActivityRequestData(
		val key: KClass<*>,
		val changeData: ActivityChangeRequestData? = null,
		val transitionData: ActivityTransitionRequestData? = null
)

data class ActivityChangeRequestData(
		val detectionIntervalS: Int,
		val callback: ActivityChangeRequestCallback
)

data class ActivityTransitionRequestData(
		val transitionList: Collection<ActivityTransitionData>,
		val callback: ActivityTransitionRequestCallback
)

data class ActivityTransitionData(val activity: DetectedActivity, val type: ActivityTransitionType)

enum class ActivityTransitionType(val value: Int) {
	ENTER(ActivityTransition.ACTIVITY_TRANSITION_ENTER),
	EXIT(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
}

