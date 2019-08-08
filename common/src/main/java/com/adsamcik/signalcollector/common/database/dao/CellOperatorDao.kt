package com.adsamcik.signalcollector.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.signalcollector.common.data.NetworkOperator

@Dao
interface CellOperatorDao : BaseDao<NetworkOperator> {
	@Query("DELETE FROM network_operator")
	fun deleteAll()
}