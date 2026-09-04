package com.adsamcik.tracker.impexp.exporter.data

import com.adsamcik.tracker.shared.model.LocationSample

/**
 * Feature-facing read port for export availability, location streaming, and share metadata.
 *
 * The contract intentionally exposes only shared models and feature-owned snapshots so
 * presentation code does not depend on Room or the application database.
 */
interface ImportExportDataRepository {
    suspend fun hasTrips(): Boolean

    suspend fun countLocationSamples(
        fromMs: Long,
        toMs: Long,
    ): Int

    fun pagedLocationSamples(
        fromMs: Long,
        toMs: Long,
        pageSize: Int,
    ): Sequence<LocationSample>

    suspend fun loadTripShareSnapshot(
        fromMs: Long,
        toMs: Long,
    ): TripShareSnapshot?
}

data class TripShareSnapshot(
    val startTimeMs: Long,
    val totalDistanceM: Float,
    val totalDurationMs: Long,
    val activityName: String?,
    val activityId: Long?,
)
