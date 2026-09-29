package com.adsamcik.tracker.shared.base.location

import android.location.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Temporary Location lease for an explicit map or foreground-service consumer.
 * The app implementation joins these leases with durable tracking on one physical owner.
 */
interface UiLocationProvider {
    val activeRequests: StateFlow<List<UiLocationRequestInfo>>

    fun locationUpdates(request: UiLocationRequest): Flow<Location>
}

data class UiLocationRequest(
    val tag: String,
    val intervalMillis: Long,
    val minUpdateIntervalMillis: Long = intervalMillis / 2,
    val minimumDisplacementMeters: Float = 0f,
    val highAccuracy: Boolean = true,
    val maximumAgeMillis: Long = 10_000L,
    /** False only for a foreground service whose own collector owns cancellation. */
    val cancelOnAppBackground: Boolean = true,
)

data class UiLocationRequestInfo(
    val tag: String,
    val startedAtMillis: Long,
)
