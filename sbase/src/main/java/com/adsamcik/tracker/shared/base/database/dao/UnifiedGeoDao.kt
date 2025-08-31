package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity

/**
 * Raw query based unified geo DAO returning generic GeoFeatureEntity objects.
 * Phase 3.1: Only location implementation required; wifi/cell to follow.
 */
@Dao
interface UnifiedGeoDao {
    /**
     * Execute a raw query that must project columns as: time (Long), lat (Double), lon (Double)
     * Optional additional numeric columns are mapped into the properties map (handled later at repository layer).
     * For now we only expose a Flow<List<GeoFeatureEntity>> for location backing table changes.
     */
    @RawQuery(observedEntities = [DatabaseLocation::class])
    fun queryLocations(query: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>

    /**
     * Wifi geo features. Query must alias latitude/longitude/last_seen as lat/lon/time respectively
     * (SafeQueryBuilder handles this automatically).
     */
    @RawQuery(observedEntities = [DatabaseWifiData::class])
    fun queryWifi(query: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>

    /**
     * Cell geo features. Table already uses lat/lon/time column names.
     */
    @RawQuery(observedEntities = [DatabaseCellLocation::class])
    fun queryCells(query: SupportSQLiteQuery): Flow<List<GeoFeatureEntity>>

    // Weighted variants. Query must project lat, lon, time, weight (numeric)
    @RawQuery(observedEntities = [DatabaseLocation::class])
    fun queryLocationsWeighted(query: SupportSQLiteQuery): Flow<List<GeoWeightedFeatureEntity>>
    @RawQuery(observedEntities = [DatabaseWifiData::class])
    fun queryWifiWeighted(query: SupportSQLiteQuery): Flow<List<GeoWeightedFeatureEntity>>
    @RawQuery(observedEntities = [DatabaseCellLocation::class])
    fun queryCellsWeighted(query: SupportSQLiteQuery): Flow<List<GeoWeightedFeatureEntity>>
}
