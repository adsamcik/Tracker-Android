package com.adsamcik.tracker.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.common.data.NetworkOperator

@Dao
interface CellOperatorDao : BaseDao<NetworkOperator> {
	@Query("DELETE FROM network_operator")
	fun deleteAll()
}
