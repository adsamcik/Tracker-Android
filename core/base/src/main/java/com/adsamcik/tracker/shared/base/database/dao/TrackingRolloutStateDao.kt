package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity

@Dao
interface TrackingRolloutStateDao {
	@Query("SELECT * FROM tracking_rollout_state WHERE id = 1")
	suspend fun get(): TrackingRolloutStateEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun save(entity: TrackingRolloutStateEntity)
}

