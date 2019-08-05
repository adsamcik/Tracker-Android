package com.adsamcik.signalcollector.activity

import android.content.Context
import android.util.SparseArray
import androidx.core.util.forEach
import androidx.core.util.isEmpty
import androidx.core.util.isNotEmpty
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.common.Reporter
import com.google.android.gms.location.ActivityRecognition
import kotlin.reflect.KClass


object ActivityRequestManager {
	private val activeRequestArray = SparseArray<ActivityRequest>()
	private var minInterval = Integer.MAX_VALUE

	/**
	 * Request activity updates
	 *
	 * @param context    context
	 * @param requestData Request data
	 * @return true if success
	 */
	fun requestActivity(context: Context, requestData: ActivityRequest): Boolean {
		updateDetectionInterval(context, requestData.detectionIntervalS)
		val hash = requestData.key.hashCode()
		activeRequestArray.put(hash, requestData)
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
				updateDetectionInterval(context, getMinInterval())
			}
		} else {
			Reporter.report(Throwable("Trying to remove class that is not subscribed (" + tClass.java.name + ")"))
		}

		if (activeRequestArray.isEmpty()) {
			ActivityRecognition.getClient(context).removeActivityUpdates(ActivityService.getActivityDetectionPendingIntent(context))
		}
	}

	private fun updateDetectionInterval(context: Context) {
		val minInterval = getMinInterval()
		if (minInterval != this.minInterval) {
			this.minInterval = minInterval
			ActivityService.initializeActivityClient(context, minInterval)
		}
	}


	private fun getMinInterval(): Int {
		var min = Integer.MAX_VALUE
		activeRequestArray.forEach { _, value ->
			if (value < min) min = value.detectionIntervalS
		}
		return min
	}
}