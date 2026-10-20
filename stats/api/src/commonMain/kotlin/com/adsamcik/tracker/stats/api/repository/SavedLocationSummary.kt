package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource

/** Selection identifies a read, never permission to collect, restore or erase a point. */
sealed interface SavedLocationSummarySelection {
    /**
     * Selects one positive persisted physical session-segment identity.
     */
    data class Session(val segmentId: Long) : SavedLocationSummarySelection {
        init { require(segmentId > 0L) }
    }
    /**
     * Selects saved Location points within a half-open measured UTC millisecond window.
     */
    data class Window(val window: HistoryObservationWindow) : SavedLocationSummarySelection
    /**
     * Selects all saved sections of one nonblank logical recording identity.
     */
    data class LogicalSession(val logicalTrackingId: String) : SavedLocationSummarySelection {
        init { require(logicalTrackingId.isNotBlank()) }
    }
}

/**
 * Saved-location summary result; available measured sections can survive a later continuation
 * failure as partial data.
 */
sealed interface SavedLocationSummaryRead {
    /**
     * No matching saved source entry or selection remains.
     */
    data object NotFound : SavedLocationSummaryRead
    /**
     * The requested data or authority is unavailable; any named reason preserves the original
     * failure cause.
     */
    data class Unavailable(val reason: HistoryProblemReason) : SavedLocationSummaryRead
    /**
     * Selection and read authority with the measured summary; any continuation problem requires
     * partial coverage.
     */
    data class Snapshot(
        val selection: SavedLocationSummarySelection,
        val readSnapshot: TrackingHistoryReadSnapshot,
        val summary: SavedLocationSummary,
        /** A failed continuation retains its available measured sections as partial data. */
        val problem: HistoryProblemReason? = null,
    ) : SavedLocationSummaryRead {
        init { require(problem == null || summary.partial) }
    }
}

/**
 * Measurements from qualified original saved native points. No inferred acquisition coverage.
 * A distance is the sum of measured continuous sections, never a bridge across a saved gap.
 * Null distance means no measured pair; a stationary pair preserves the real value zero.
 */
data class SavedLocationSummary(
    val pointCount: Long,
    val latestFix: SavedLocationFix?,
    val distanceMeters: Double?,
    val partial: Boolean,
    val qualificationGaps: Set<RadioLocationContextGap> = emptySet(),
    /** Measured continuous moving pairs; null without a pair, zero for measured stationary data. */
    val movingAverageSpeedMetersPerSecond: Double? = null,
    /** Bounded measured sections with distance's saved gaps; live reads retain the recent tail. */
    val routePreview: List<List<SavedLocationFix>> = emptyList(),
) {
    init {
        require(pointCount >= 0L)
        require((pointCount == 0L) == (latestFix == null))
        require(distanceMeters == null || distanceMeters.isFinite() && distanceMeters >= 0.0 && pointCount >= 2L)
        require(qualificationGaps.isEmpty() || partial)
        require(movingAverageSpeedMetersPerSecond == null ||
            movingAverageSpeedMetersPerSecond.isFinite() && movingAverageSpeedMetersPerSecond >= 0.0 &&
                distanceMeters != null)
    }
}

/** An original measured fix, not a new or fresh fix merely because the database was read now. */
data class SavedLocationFix(
    val sampleId: Long,
    val measuredTimeMs: Long,
    val wallTimeUncertaintyMs: Long,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val horizontalAccuracyMeters: Float?,
    val altitudeMeters: Float?,
    val altitudeDatum: AltitudeDatum,
    val altitudeSource: AltitudeSource,
    val verticalAccuracyMeters: Float?,
) {
    init {
        require(sampleId > 0L && measuredTimeMs >= 0L && wallTimeUncertaintyMs >= 0L)
        require(latitudeE7 in -900_000_000..900_000_000 && longitudeE7 in -1_800_000_000..1_800_000_000)
        require(listOf(speedMetersPerSecond, speedAccuracyMetersPerSecond,
            horizontalAccuracyMeters, verticalAccuracyMeters).all { it == null || it.isFinite() && it >= 0f })
        require(altitudeMeters == null || altitudeMeters.isFinite())
    }
}
