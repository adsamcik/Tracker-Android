package com.adsamcik.tracker.testing.fake

import android.content.Context
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import kotlin.reflect.KClass

class FakeActivityRequestManager(
	override var lastActivity: ActivityInfo = ActivityInfo.UNKNOWN,
) : ActivityRequestManager {
	val activeRequests: MutableMap<KClass<*>, ActivityRequestData> = linkedMapOf()
	var requestCount: Int = 0
		private set
	var removeCount: Int = 0
		private set

	override fun requestActivity(context: Context, requestData: ActivityRequestData): Boolean {
		activeRequests[requestData.key] = requestData
		requestCount++
		return true
	}

	override fun removeActivityRequest(context: Context, tClass: KClass<*>) {
		activeRequests.remove(tClass)
		removeCount++
	}

	fun reset() {
		activeRequests.clear()
		requestCount = 0
		removeCount = 0
		lastActivity = ActivityInfo.UNKNOWN
	}
}
