package com.adsamcik.tracker.shared.base.location

import android.location.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-lifecycle-aware source for location requested by UI surfaces.
 *
 * Requests are cancelled while the app is backgrounded. This must not be used by the
 * tracking service's independent sampling pipeline.
 */
interface UiLocationProvider {
    val activeRequests: StateFlow<List<UiLocationRequestInfo>>

    fun locationUpdates(request: UiLocationRequest): Flow<Location>
}

data class UiLocationRequest(
    val tag: String,
    val intervalMillis: Long,
    val minUpdateIntervalMillis: Long = intervalMillis / 2,
    val highAccuracy: Boolean = true,
)

data class UiLocationRequestInfo(
    val tag: String,
    val startedAtMillis: Long,
)
