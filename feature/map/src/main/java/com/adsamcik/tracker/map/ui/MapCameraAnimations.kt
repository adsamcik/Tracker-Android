package com.adsamcik.tracker.map.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** One camera animation at a time; compass updates wait for positioning and keep only their latest value. */
internal class MapCameraAnimations(
    private val scope: CoroutineScope,
    private val shouldRotate: () -> Boolean,
    private val onRotate: suspend (Float) -> Unit,
) {
    private var animation: Job? = null
    private var positioning = false
    private var latestBearing: Float? = null
    private var pendingLocationZoom: Double? = null

    suspend fun move(action: suspend () -> Unit) {
        pendingLocationZoom = null
        position(action)
    }

    suspend fun center(zoom: Double?, action: suspend (Double?) -> Unit) {
        val targetZoom = zoom ?: pendingLocationZoom
        pendingLocationZoom = targetZoom
        position {
            action(targetZoom)
            pendingLocationZoom = null
        }
    }

    private suspend fun position(action: suspend () -> Unit) {
        animation?.cancelAndJoin()
        positioning = true
        animation = scope.launch {
            try {
                action()
            } finally {
                positioning = false
            }
            latestBearing?.takeIf { shouldRotate() }?.let { onRotate(it) }
        }
    }

    suspend fun rotate(bearing: Float) {
        latestBearing = bearing
        if (positioning || !shouldRotate()) {
            return
        }
        animation?.cancelAndJoin()
        animation = scope.launch { onRotate(bearing) }
    }
}
