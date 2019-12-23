package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface GeneralDao {
	@RawQuery
	fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
