package com.adsamcik.tracker.testing.fake

import android.content.Context
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.reflect.KClass

class FakeActivityRequestManager(
	override var lastActivity: RecognizedActivity = RecognizedActivity.UNKNOWN,
) : ActivityRequestManager {
	private val mutableActivityUpdates = MutableSharedFlow<ActivityUpdate>(extraBufferCapacity = 16)
	override val activityUpdates: Flow<ActivityUpdate> = mutableActivityUpdates.asSharedFlow()
	private val mutableTransitionUpdates = MutableSharedFlow<List<TransitionUpdate>>(extraBufferCapacity = 16)
	override val transitionUpdates: Flow<List<TransitionUpdate>> = mutableTransitionUpdates.asSharedFlow()

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

	fun emitActivityUpdate(update: ActivityUpdate) {
		lastActivity = update.activity
		check(mutableActivityUpdates.tryEmit(update)) { "Fake activity update buffer is full" }
	}

	fun emitTransitionUpdates(updates: List<TransitionUpdate>) {
		check(mutableTransitionUpdates.tryEmit(updates)) { "Fake transition update buffer is full" }
	}

	fun reset() {
		activeRequests.clear()
		requestCount = 0
		removeCount = 0
		lastActivity = RecognizedActivity.UNKNOWN
	}
}
