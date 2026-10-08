package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers

/** Unused capture sources are deliberately absent; Location, radio, keys and organization use real Room. */
internal fun rangeReaderForRoomTest(database: AppDatabase, context: Application,
    wifi: DefaultWifiHistoryRepository = mockk(),
    qualifiedLocation: RetainedLocationContextReader = RetainedLocationContextReader(database),
    laneAuthority: SourceProductLaneExecutionAuthority = SourceProductLaneExecutionAuthority { false }): UnifiedHistoryRangeReader {
    val io = Dispatchers.Unconfined
    val lane = laneAuthority
    val steps = StepsSegmentHistorySelector(database, lane)
    val logical = LogicalTrackingHistoryReader(database, steps)
    val pressure = PressureHistorySelector(database, lane)
    val activity = DefaultActivityHistoryRepository(database, lane, io)
    val cell = mockk<DefaultCellHistoryRepository>()
    return UnifiedHistoryRangeReader(database,
        UnifiedHistoryNativeReader(database, logical, steps, wifi, cell, activity, pressure, qualifiedLocation),
        wifi, cell, activity, PressureHistoryPageReader(database, pressure, ImportedPressureHistoryEvaluator(database),
            PortablePressureRoomReader(database, pressure)),
        DefaultAmbientStepsHistoryRepository(database, database.importedAmbientStepsDao(), steps, mockk(), io),
        RoomAmbientWifiRepository(database, io), RoomAmbientCellRepository(database, io), context, io, qualifiedLocation, steps, pressure)
}
