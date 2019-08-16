package com.adsamcik.tracker.activity.api

import android.content.Context
import android.util.SparseArray
import androidx.core.util.forEach
import androidx.core.util.isEmpty
import androidx.core.util.isNotEmpty
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionRequestData
import com.adsamcik.tracker.activity.service.ActivityService
import com.adsamcik.tracker.common.Reporter
import com.adsamcik.tracker.common.data.ActivityInfo
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import kotlin.reflect.KClass


object ActivityRequestManager {
	private val activeRequestArray = SparseArray<ActivityRequestData>()

	private var minInterval = Integer.MAX_VALUE
	private var transitions: Collection<ActivityTransitionData> = mutableListOf()

	val lastActivity: ActivityInfo get() = ActivityService.lastActivity

	/**
	 * Request activity updates
	 *
	 * @param context    context
	 * @param requestData Request data
	 * @return true if success
	 */
	fun requestActivity(context: Context, requestData: ActivityRequestData): Boolean {
		require(requestData.transitionData != null || requestData.changeData != null)

		val hash = requestData.key.hashCode()
		activeRequestArray.put(hash, requestData)
		onRequestChange(context)
		return true
	}

	/**
	 * Removes previous activity request
	 */
	fun removeActivityRequest(context: Context, tClass: KClass<*>) {
		val index = activeRequestArray.indexOfKey(tClass.hashCode())
		if (index >= 0) {
			val request = activeRequestArray.valueAt(index)

			activeRequestArray.removeAt(index)

			if (minInterval <= request.detectionIntervalS && activeRequestArray.isNotEmpty()) {
				onRequestChange(context)
			}
		} else {
			Reporter.report(Throwable("Trying to remove class that is not subscribed (" + tClass.java.name + ")"))
		}

		if (activeRequestArray.isEmpty()) {
			ActivityService.stopActivityRecognition(context)
		}
	}

	private fun getTransitions(): Collection<ActivityTransitionData> {
		val list = mutableSetOf<ActivityTransitionData>()
		activeRequestArray.forEach { _, value ->
			value.transitionData?.let { transitionData ->
				list.addAll(transitionData.transitionList)
			}
		}
		return list
	}

	private fun onRequestChange(context: Context) {
		val minInterval = getMinInterval()
		val transitions = getTransitions()

		if (minInterval != ActivityRequestManager.minInterval ||
				transitions.size != ActivityRequestManager.transitions.size ||
				!transitions.containsAll(ActivityRequestManager.transitions)) {
			updateActivityService(context, minInterval, transitions)
		}
	}

	private fun updateActivityService(context: Context,
	                                  interval: Int,
	                                  transitions: Collection<ActivityTransitionData>) {
		minInterval = interval
		ActivityRequestManager.transitions = transitions
		ActivityService.startActivityRecognition(context, minInterval, transitions)
	}

	private fun getMinInterval(): Int {
		var min = Integer.MAX_VALUE
		activeRequestArray.forEach { _, value ->
			if (value.detectionIntervalS < min) min = value.detectionIntervalS
		}
		return min
	}

	internal fun onActivityUpdate(context: Context, result: ActivityInfo, elapsedMillis: Long) {
		activeRequestArray.forEach { _, value ->
			value.changeData?.callback?.invoke(context, result, elapsedMillis)
		}
	}

	private fun onActivityTransition(context: Context,
	                                 requestData: ActivityTransitionRequestData,
	                                 descendingEvents: List<ActivityTransitionEvent>) {
		requestData.transitionList.forEach {
			for (transition in descendingEvents) {
				if (transition.transitionType == it.activity.value &&
						transition.activityType == it.activity.value) {
					requestData.callback.invoke(context, it, transition.elapsedRealTimeNanos)
					return
				}
			}
		}
	}

	internal fun onActivityTransition(context: Context, result: ActivityTransitionResult) {
		val reversedEvents = result.transitionEvents.reversed()
		activeRequestArray.forEach { _, value ->
			val transitionData = value.transitionData
			transitionData?.let { onActivityTransition(context, it, reversedEvents) }
		}
	}
}

