package com.adsamcik.tracker.activity.api

import android.content.Context
import android.util.SparseArray
import androidx.core.util.forEach
import androidx.core.util.isEmpty
import androidx.core.util.isNotEmpty
import com.adsamcik.tracker.activity.ACTIVITY_LOG_SOURCE
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * Default activity request manager backed by the configured activity-recognition backend.
 */
@Singleton
class DefaultActivityRequestManager @Inject constructor(
    private val backend: ActivityRecognitionBackend,
) : ActivityRequestManager {
    private val activeRequestArray = SparseArray<ActivityRequestData>()

    private var minInterval = Integer.MAX_VALUE
    private var transitions: Collection<ActivityTransitionData> = emptyList()

    override val lastActivity: RecognizedActivity get() = backend.lastActivity
    override val activityUpdates: Flow<ActivityUpdate> get() = backend.activityUpdates
    override val transitionUpdates: Flow<List<TransitionUpdate>> get() = backend.transitionUpdates

    @Synchronized
    override fun requestActivity(context: Context, requestData: ActivityRequestData): Boolean {
        require(requestData.transitionData != null || requestData.changeData != null)

        logActivity(
            LogData(
                message = "new activity request",
                data = requestData,
                source = ACTIVITY_LOG_SOURCE,
            ),
        )

        val hash = requestData.key.hashCode()
        activeRequestArray.put(hash, requestData)
        onRequestChange(context)
        return true
    }

    @Synchronized
    override fun removeActivityRequest(context: Context, tClass: KClass<*>) {
        val index = activeRequestArray.indexOfKey(tClass.hashCode())
        if (index >= 0) {
            activeRequestArray.removeAt(index)

            logActivity(
                LogData(
                    message = "removed request for ${tClass.java.name}",
                    source = ACTIVITY_LOG_SOURCE,
                ),
            )

            if (activeRequestArray.isNotEmpty()) {
                onRequestChange(context)
            }
        } else {
            Reporter.report("Trying to remove class that is not subscribed (${tClass.java.name})")
        }

        if (activeRequestArray.isEmpty()) {
            backend.stopUpdates()
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

        if (minInterval != this.minInterval ||
            transitions.size != this.transitions.size ||
            !transitions.containsAll(this.transitions)
        ) {
            updateActivityService(context, minInterval, transitions)
        }
    }

    private fun updateActivityService(
        context: Context,
        interval: Int,
        transitions: Collection<ActivityTransitionData>,
    ) {
        minInterval = interval
        this.transitions = transitions

        if (context.hasActivityPermission) {
            backend.startUpdates(RecognitionConfig(minInterval, transitions))
        } else {
            val message = "activity recognition permission missing; request not started"
            logActivity(LogData(message = message, source = ACTIVITY_LOG_SOURCE))
            Reporter.log(message)
        }
    }

    private fun getMinInterval(): Int {
        var min = Integer.MAX_VALUE

        activeRequestArray.forEach { _, value ->
            val detectionInterval = value.changeData?.detectionIntervalS ?: return@forEach
            if (detectionInterval < min) min = detectionInterval
        }
        return if (min == Integer.MAX_VALUE) Integer.MIN_VALUE else min
    }

}
