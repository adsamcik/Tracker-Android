package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Dao that provides database wide query support.
 */
@Dao
interface GeneralDao {
	/**
	 * Creates full checkpoint for the whole database.
	 * This mode blocks (it invokes the busy-handler callback) until there is no database writer
	 * and all readers are reading from the most recent database snapshot.
	 * It then checkpoints all frames in the log file and syncs the database file.
	 * This mode blocks new database writers while it is pending, but new database readers
	 * are allowed to continue unimpeded.
	 *
	 * At the time of writing, pragma in [Query] was not supported
	 * so [RawQuery] had to be used instead.
	 */
	@RawQuery
	fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
