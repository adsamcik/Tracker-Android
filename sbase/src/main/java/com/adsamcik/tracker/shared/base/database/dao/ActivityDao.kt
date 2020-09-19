package com.adsamcik.tracker.shared.base.database.dao

import android.content.Context
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity

/**
 * Session activity data access object.
 */
@Dao
interface ActivityDao : BaseDao<SessionActivity> {
	/**
	 * Get all session activities from database.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM activity")
	fun getAll(): List<SessionActivity>

	/**
	 * Get all session activities created by a user.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM activity WHERE id >= 0")
	fun getAllUser(): List<SessionActivity>

	/**
	 * Get specific session activity.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM activity WHERE id = :id")
	fun get(id: Long): SessionActivity?

	/**
	 * Get specific localized session activity.
	 */
	@Transaction
	fun getLocalized(context: Context, id: Long): SessionActivity? {
		return if (id < 0) {
			val nativeSessionActivity = NativeSessionActivity.values()
					.find { it.id == id }

			requireNotNull(nativeSessionActivity).getSessionActivity(context)
		} else {
			get(id)
		}
	}

	/**
	 * Find session activity with exact name.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM activity WHERE name = :name")
	fun find(name: String): SessionActivity?

	/**
	 * Delete specific session activity.
	 */
	@Query("DELETE FROM activity WHERE id = :id")
	fun delete(id: Long)
}
