package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.feature.statistics.api.export.TripGpxExportResult
import com.adsamcik.tracker.feature.statistics.api.export.TripGpxExporter
import com.adsamcik.tracker.shared.model.LocationSample
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import javax.inject.Inject

class ImportExportTripGpxExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : TripGpxExporter {
    override suspend fun export(
        outputStream: OutputStream,
        dateRange: LongRange,
        streamLocations: suspend ((LocationSample) -> Unit) -> Unit,
    ): TripGpxExportResult =
        when (
            GpxExporter().export(
                context = context,
                outputStream = outputStream,
                dateRange = dateRange,
                streamLocations = streamLocations,
            )
        ) {
            is ExportResult.Success -> TripGpxExportResult.Success
            is ExportResult.Error -> TripGpxExportResult.Failure
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TripGpxExportModule {
    @Binds
    abstract fun bindTripGpxExporter(
        implementation: ImportExportTripGpxExporter,
    ): TripGpxExporter
}
