package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference

@Dao
interface NotificationPreferenceDao : BaseDao<NotificationPreference> {
	@Query("SELECT * FROM notification")
	fun getAll(): List<NotificationPreference>

	@Transaction
	fun upsert(obj: NotificationPreference) {
		val id = insert(obj)
		if (id == -1L) {
			update(obj)
		}
	}

	@Transaction
	fun upsert(objList: Collection<NotificationPreference>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		if (updateList.isNotEmpty()) {
			update(updateList)
		}
	}
}
