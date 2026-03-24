package com.adsamcik.tracker.logger.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface LoggerBaseDao<T> {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(obj: T): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(obj: Collection<T>): List<Long>

	@Update
	suspend fun update(obj: T)

	@Update
	suspend fun update(obj: Collection<T>)

	@Delete
	suspend fun delete(obj: T)

	@Delete
	suspend fun delete(obj: Collection<T>)
}

interface LoggerBaseUpsertDao<T> : LoggerBaseDao<T> {
	@Transaction
	suspend fun upsert(obj: T) {
		val id = insert(obj)
		if (id == -1L) {
			update(obj)
		}
	}

	@Transaction
	suspend fun upsert(objList: Collection<T>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }
		if (updateList.isNotEmpty()) {
			update(updateList)
		}
	}
}
