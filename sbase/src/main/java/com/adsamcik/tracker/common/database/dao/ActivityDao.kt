package com.adsamcik.tracker.common.database.dao

import android.content.Context
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.common.data.NativeSessionActivity
import com.adsamcik.tracker.common.data.SessionActivity

@Dao
interface ActivityDao : BaseDao<SessionActivity> {
	@Query("SELECT * FROM activity")
	fun getAll(): List<SessionActivity>

	@Query("SELECT * FROM activity WHERE id >= 0")
	fun getAllUser(): List<SessionActivity>

	@Query("SELECT * FROM activity WHERE id = :id")
	fun get(id: Long): SessionActivity?

	@Transaction
	fun getLocalized(context: Context, id: Long): SessionActivity? {
		val sessionActivity = get(id) ?: return null

		if (sessionActivity.id < 0) {
			val nativeSessionActivity = NativeSessionActivity.values()
					.find { it.id == sessionActivity.id }

			return requireNotNull(nativeSessionActivity).getSessionActivity(context)
		}

		return sessionActivity
	}

	@Query("SELECT * FROM activity WHERE name = :name")
	fun find(name: String): SessionActivity?

	@Query("DELETE FROM activity WHERE id = :id")
	fun delete(id: Long)
}
