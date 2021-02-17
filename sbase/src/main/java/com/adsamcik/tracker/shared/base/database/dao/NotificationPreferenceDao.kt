package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference

/**
 * Data access object for notification preferences
 */
@Dao
interface NotificationPreferenceDao : BaseDao<NotificationPreference> {
	/**
	 * Finds all notification preferences
	 */
	@Query("SELECT * FROM notification")
	fun getAll(): List<NotificationPreference>

	/**
	 * Ensures a notification preference is set to provided value.
	 *
	 * @param preference preference value
	 */
	@Transaction
	fun upsert(preference: NotificationPreference) {
		val id = insert(preference)
		if (id == -1L) {
			update(preference)
		}
	}

	/**
	 * Ensures all provided notification preferences are set to their values.
	 *
	 * @param preferenceList List of preferences
	 */
	@Transaction
	fun upsert(preferenceList: Collection<NotificationPreference>) {
		val insertResult = insert(preferenceList)
		val updateList = preferenceList.filterIndexed { index, _ -> insertResult[index] == -1L }

		if (updateList.isNotEmpty()) {
			update(updateList)
		}
	}
}
