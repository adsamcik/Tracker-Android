package com.adsamcik.tracker.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.common.database.data.NotificationPreference

@Dao
interface NotificationPreferenceDao : BaseDao<NotificationPreference> {
	@Query("SELECT * FROM notification")
	fun getAll(): List<NotificationPreference>
}
