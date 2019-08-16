package com.adsamcik.tracker.activity.api

import android.content.Context
import android.util.SparseArray
import androidx.core.util.forEach
import androidx.core.util.isEmpty
import androidx.core.util.isNotEmpty
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.service.ActivityService
import com.adsamcik.tracker.common.Reporter
import com.adsamcik.tracker.common.data.ActivityInfo
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
	 * @param requestDataData Request data
	 * @return true if success
	 */
	fun requestActivity(context: Context, requestDataData: ActivityRequestData): Boolean {
		val hash = requestDataData.key.hashCode()
		activeRequestArray.put(hash, requestDataData)
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

		}
	}

	private fun getTransitions(): Collection<ActivityTransitionData> {
		val list = mutableSetOf<ActivityTransitionData>()
		activeRequestArray.forEach { _, value ->
			list.addAll(value.transitionList)
		}
		return list
	}

	private fun onRequestChange(context: Context) {
		val minInterval = getMinInterval()
		val transitions = getTransitions()

		if (minInterval != ActivityRequestManager.minInterval || transitions.size != ActivityRequestManager.transitions.size || !transitions.containsAll(
						ActivityRequestManager.transitions)) {
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
}

