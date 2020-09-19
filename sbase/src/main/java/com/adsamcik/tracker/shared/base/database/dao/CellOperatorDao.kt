package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.data.NetworkOperator

@Dao
interface CellOperatorDao : BaseDao<NetworkOperator> {
	@Query("DELETE FROM network_operator")
	fun deleteAll()
}
