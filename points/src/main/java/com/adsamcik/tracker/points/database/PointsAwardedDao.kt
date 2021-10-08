package com.adsamcik.tracker.points.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

/**
 * DAO for awarded points
 */
@Dao
interface PointsAwardedDao : BaseDao<PointsAwarded> {
	/**
	 * Returns number of points earned between two time intervals.
	 */
	@Query("SELECT SUM(value) FROM points_awarded WHERE time >= :from AND time <= :to")
	fun countBetween(from: Long, to: Long): Int

	/**
	 * Returns number of points earned between two time intervals.
	 */
	@Query("SELECT SUM(value) FROM points_awarded WHERE time >= :from AND time <= :to")
	fun countBetweenLive(from: Long, to: Long): LiveData<Int>
}
