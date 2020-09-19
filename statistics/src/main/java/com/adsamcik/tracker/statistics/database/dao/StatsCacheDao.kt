package com.adsamcik.tracker.statistics.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import com.adsamcik.tracker.statistics.database.data.CacheStatData

/**
 * Dao for statistics cache.
 */
@Dao
interface StatsCacheDao : BaseDao<CacheStatData> {

	/**
	 * Update if exists, insert otherwise
	 */
	@Transaction
	fun upsert(obj: CacheStatData) {
		val id = insert(obj)
		if (id == -1L) {
			update(obj)
		}
	}

	/**
	 * Update if exists, insert otherwise
	 */
	@Transaction
	fun upsert(objList: Collection<CacheStatData>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		if (updateList.isNotEmpty()) {
			update(updateList)
		}
	}

	/**
	 * Returns cache data for session with session id equal to [sessionId].
	 *
	 * @param sessionId Id of session for which cache data will be returned
	 */
	@Query("select * from statCache where session_id = :sessionId")
	fun getAllForSession(sessionId: Long): List<CacheStatData>
}
