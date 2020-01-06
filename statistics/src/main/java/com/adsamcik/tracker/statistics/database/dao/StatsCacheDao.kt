package com.adsamcik.tracker.statistics.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import com.adsamcik.tracker.statistics.database.data.StatData

/**
 * Dao for statistics cache.
 */
@Dao
interface StatsCacheDao : BaseDao<StatData> {
	/**
	 * Returns cache data for session with session id equal to [sessionId].
	 *
	 * @param sessionId Id of session for which cache data will be returned
	 */
	@Query("select * from statCache where session_id = :sessionId")
	fun getAllForSession(sessionId: Long): List<StatData>
}
