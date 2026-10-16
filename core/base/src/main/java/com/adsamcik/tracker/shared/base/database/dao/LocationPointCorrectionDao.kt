package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection

/**
 * Stores one current correction per original Location point and reads those corrections by saved
 * sample IDs.
 */
@Dao
interface LocationPointCorrectionDao {
    /**
     * Inserts [LocationPointCorrection] rows. Conflicting primary or unique keys replace the saved
     * row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(correction: LocationPointCorrection)

    /**
     * Reads `location_point_correction` rows. Query conditions include `sample_id IN (:ids)`.
     */
    @Query("SELECT * FROM location_point_correction WHERE sample_id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<LocationPointCorrection>
}
