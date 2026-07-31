package com.adsamcik.tracker.feature.statistics.api.export

import com.adsamcik.tracker.shared.model.LocationSample
import java.io.OutputStream

/**
 * Consumer-owned port for writing a trip to GPX.
 *
 * Statistics owns the use case while the import/export feature supplies the
 * format implementation at the application composition boundary.
 */
interface TripGpxExporter {
    suspend fun export(
        outputStream: OutputStream,
        dateRange: LongRange,
        streamLocations: suspend ((LocationSample) -> Unit) -> Unit,
    ): TripGpxExportResult
}

sealed interface TripGpxExportResult {
    data object Success : TripGpxExportResult
    data object Failure : TripGpxExportResult
}
