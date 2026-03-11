package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity

/**
 * Raw query based unified geo DAO returning generic GeoFeatureEntity objects.
 */
@Dao
interface UnifiedGeoDao {
    /**
     * Execute a raw query that must project columns as: time (Long), lat (Double), lon (Double).
     * Observes the location_sample table for invalidation.
     */
    @RawQuery(observedEntities = [LocationSample::class])
    fun queryLocations(query: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>

    /**
     * Wifi geo features. Observes the wifi_observation table for invalidation.
     */
    @RawQuery(observedEntities = [WifiObservation::class])
    fun queryWifi(query: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>

    /**
     * Cell geo features. Observes the cell_sample table for invalidation.
     */
    @RawQuery(observedEntities = [CellSample::class])
    fun queryCells(query: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>

    @RawQuery(observedEntities = [LocationSample::class])
    fun queryLocationsWeighted(query: SupportSQLiteQuery): Flow<List<GeoWeightedFeatureEntity>>
    @RawQuery(observedEntities = [WifiObservation::class])
    fun queryWifiWeighted(query: SupportSQLiteQuery): Flow<List<GeoWeightedFeatureEntity>>
    @RawQuery(observedEntities = [CellSample::class])
    fun queryCellsWeighted(query: SupportSQLiteQuery): Flow<List<GeoWeightedFeatureEntity>>
}
