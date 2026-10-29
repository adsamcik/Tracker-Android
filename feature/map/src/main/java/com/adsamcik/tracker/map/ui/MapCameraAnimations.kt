package com.adsamcik.tracker.map.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One camera animation at a time; compass updates wait for positioning and keep only their latest value. */
internal class MapCameraAnimations(
    private val scope: CoroutineScope,
    private val shouldRotate: () -> Boolean,
    private val onRotate: suspend (Float) -> Unit,
) {
    private var animation: Job? = null
    private var positioning = false
    private var positionGeneration = 0L
    private var latestBearing: Float? = null
    private var pendingLocationZoom: Double? = null

    fun move(action: suspend () -> Unit) {
        pendingLocationZoom = null
        position(action)
    }

    fun center(zoom: Double?, action: suspend (Double?) -> Unit) {
        val targetZoom = zoom ?: pendingLocationZoom
        pendingLocationZoom = targetZoom
        position {
            action(targetZoom)
            currentCoroutineContext().ensureActive()
            pendingLocationZoom = null
        }
    }

    private fun position(action: suspend () -> Unit) {
        // The native callback bridge is not cancellable. The replacement must reach the
        // renderer so it can cancel the previous native animation and release its callback.
        animation?.cancel()
        val generation = ++positionGeneration
        positioning = true
        animation = scope.launch {
            try {
                action()
            } finally {
                if (generation == positionGeneration) {
                    positioning = false
                }
            }
            currentCoroutineContext().ensureActive()
            latestBearing?.takeIf { shouldRotate() }?.let { onRotate(it) }
        }
    }

    fun rotate(bearing: Float) {
        latestBearing = bearing
        if (positioning || !shouldRotate()) {
            return
        }
        animation?.cancel()
        animation = scope.launch { onRotate(bearing) }
    }
}
