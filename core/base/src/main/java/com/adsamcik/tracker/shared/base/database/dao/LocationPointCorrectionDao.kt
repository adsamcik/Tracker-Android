package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection

@Dao
interface LocationPointCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(correction: LocationPointCorrection)

    @Query("SELECT * FROM location_point_correction WHERE sample_id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<LocationPointCorrection>
}
