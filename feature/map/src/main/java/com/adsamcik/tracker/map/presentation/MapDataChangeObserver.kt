package com.adsamcik.tracker.map.presentation

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

internal enum class MapDataSource {
    Location,
    Cell,
    Wifi,
}

internal sealed interface MapDataChange {
    data object Ready : MapDataChange

    data class Committed(
        val sources: Set<MapDataSource>,
    ) : MapDataChange
}

/**
 * Publishes committed database changes that can affect a live map layer.
 *
 * Room invalidations are emitted after a successful transaction, so consumers never re-query data
 * that is still buffered or later rolled back. Room's initial emission is exposed only as
 * [MapDataChange.Ready], allowing the map to await observer registration before querying without
 * performing a redundant refresh.
 */
class MapDataChangeObserver @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val database by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.database(context.applicationContext)
    }

    internal fun changes(): Flow<MapDataChange> = flow {
        var isInitialEmission = true
        database.invalidationTracker
            .createFlow(LOCATION_TABLE, CELL_TABLE, WIFI_TABLE, emitInitialState = true)
            .collect { invalidatedTables ->
                if (isInitialEmission) {
                    isInitialEmission = false
                    emit(MapDataChange.Ready)
                } else {
                    val sources = buildSet {
                        if (LOCATION_TABLE in invalidatedTables) add(MapDataSource.Location)
                        if (CELL_TABLE in invalidatedTables) add(MapDataSource.Cell)
                        if (WIFI_TABLE in invalidatedTables) add(MapDataSource.Wifi)
                    }
                    if (sources.isNotEmpty()) {
                        emit(MapDataChange.Committed(sources))
                    }
                }
            }
    }

    private companion object {
        const val LOCATION_TABLE = "location_sample"
        const val CELL_TABLE = "cell_sample"
        const val WIFI_TABLE = "wifi_observation"
    }
}
