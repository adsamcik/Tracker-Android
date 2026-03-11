package com.adsamcik.tracker.points.database

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

/**
 * DAO for awarded points
 */
@Dao
interface PointsAwardedDao : BaseDao<PointsAwarded> {
	/**
	 * Returns number of points earned between two time intervals.
	 */
	@Query("SELECT COALESCE(SUM(value), 0) FROM points_awarded WHERE time >= :from AND time <= :to")
	fun countBetween(from: Long, to: Long): Int

	/**
	 * Returns number of points earned between two time intervals as a Flow.
	 */
	@Query("SELECT COALESCE(SUM(value), 0) FROM points_awarded WHERE time >= :from AND time <= :to")
	fun countBetweenFlow(from: Long, to: Long): Flow<Int>

	@Query("SELECT EXISTS(SELECT 1 FROM points_awarded WHERE time = :time AND source = :source LIMIT 1)")
	fun hasAwardAt(time: Long, source: String): Boolean


}
