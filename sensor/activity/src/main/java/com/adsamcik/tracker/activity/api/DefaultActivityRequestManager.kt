package com.adsamcik.tracker.activity.api

import android.content.Context
import android.util.SparseArray
import androidx.core.util.forEach
import androidx.core.util.isEmpty
import androidx.core.util.isNotEmpty
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/**
 * Default activity request manager backed by the configured activity-recognition backend.
 */
@Singleton
class DefaultActivityRequestManager @Inject constructor(
    private val backend: ActivityRecognitionBackend,
) : ActivityRequestManager {
    private val activeRequestArray = SparseArray<ActivityRequestData>()
    private val requestMutex = Mutex()

    private var minInterval = Integer.MAX_VALUE
    private var transitions: Collection<ActivityTransitionData> = emptyList()
    private var configurationKnown = false

    override val lastActivity: RecognizedActivity get() = backend.lastActivity
    override val activityUpdates: Flow<ActivityUpdate> get() = backend.activityUpdates
    override val transitionUpdates: Flow<List<TransitionUpdate>> get() = backend.transitionUpdates

    override suspend fun requestActivity(
        context: Context,
        requestData: ActivityRequestData,
    ): Boolean = requestMutex.withLock {
        require(requestData.transitionData != null || requestData.changeData != null)

        val hash = requestData.key.hashCode()
        val previousRequest = activeRequestArray.get(hash)
        activeRequestArray.put(hash, requestData)
        try {
            if (applyCurrentRequests(context)) {
                return@withLock true
            }
        } catch (exception: Exception) {
            if (previousRequest == null) {
                activeRequestArray.remove(hash)
            } else {
                activeRequestArray.put(hash, previousRequest)
            }
            withContext(NonCancellable) {
                restorePreviousConfiguration(context)
            }
            throw exception
        }

        if (previousRequest == null) {
            activeRequestArray.remove(hash)
        } else {
            activeRequestArray.put(hash, previousRequest)
        }
        restorePreviousConfiguration(context)
        false
    }

    override suspend fun removeActivityRequest(
        context: Context,
        tClass: KClass<*>,
    ) = requestMutex.withLock {
        val index = activeRequestArray.indexOfKey(tClass.hashCode())
        if (index < 0) {
            if (!configurationKnown) {
                if (activeRequestArray.isEmpty()) {
                    backend.stopUpdates()
                    minInterval = Integer.MAX_VALUE
                    transitions = emptyList()
                    configurationKnown = true
                } else {
                    check(applyCurrentRequests(context, force = true)) {
                        "Unable to restore active activity recognition requests"
                    }
                }
                return@withLock
            }
            return@withLock
        }

        val removedRequest = activeRequestArray.valueAt(index)
        activeRequestArray.removeAt(index)
        try {
            val updated = if (activeRequestArray.isEmpty()) {
                backend.stopUpdates()
                minInterval = Integer.MAX_VALUE
                transitions = emptyList()
                configurationKnown = true
                true
            } else {
                applyCurrentRequests(context)
            }
            if (!updated) {
                error("Unable to update activity recognition after removing ${tClass.java.name}")
            }
        } catch (exception: Exception) {
            activeRequestArray.put(tClass.hashCode(), removedRequest)
            withContext(NonCancellable) {
                restorePreviousConfiguration(context)
            }
            throw exception
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

    private suspend fun applyCurrentRequests(
        context: Context,
        force: Boolean = false,
    ): Boolean {
        val requestedInterval = getMinInterval()
        val requestedTransitions = getTransitions()
        val unchanged = configurationKnown &&
            requestedInterval == minInterval &&
            requestedTransitions.size == transitions.size &&
            requestedTransitions.containsAll(transitions)
        if (unchanged && !force) return true

        if (!context.hasActivityPermission) {
            return false
        }

        configurationKnown = false
        val started = backend.startUpdates(
            RecognitionConfig(requestedInterval, requestedTransitions),
        )
        if (started) {
            minInterval = requestedInterval
            transitions = requestedTransitions.toList()
            configurationKnown = true
        }
        return started
    }

    private suspend fun restorePreviousConfiguration(context: Context) {
        try {
            if (activeRequestArray.isEmpty()) {
                backend.stopUpdates()
                minInterval = Integer.MAX_VALUE
                transitions = emptyList()
                configurationKnown = true
            } else {
                check(applyCurrentRequests(context, force = true)) {
                    "Unable to restore previous activity recognition configuration"
                }
            }
        } catch (exception: CancellationException) {
            configurationKnown = false
            throw exception
        } catch (exception: Exception) {
            configurationKnown = false
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
