package com.adsamcik.signalcollector.database.dao

import androidx.room.*


@Dao
interface BaseDao<T> {
	/**
	 * Insert an object in the database.
	 *
	 * @param obj the object to be inserted.
	 * @return The SQLite row id
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract fun insert(obj: T): Long

	/**
	 * Insert an array of objects in the database.
	 *
	 * @param obj the objects to be inserted.
	 * @return The SQLite row ids
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract fun insert(obj: Collection<T>): List<Long>

	/**
	 * Update an object from the database.
	 *
	 * @param obj the object to be updated
	 */
	@Update
	abstract fun update(obj: T)

	/**
	 * Update an array of objects from the database.
	 *
	 * @param obj the object to be updated
	 */
	@Update
	abstract fun update(obj: Collection<T>)

	/**
	 * Delete an object from the database
	 *
	 * @param obj the object to be deleted
	 */
	@Delete
	abstract fun delete(obj: T)
}

@Dao
interface BaseUpsertDao<T> : BaseDao<T> {
	@Transaction
	open fun upsert(obj: T) {
		val id = insert(obj)
		if (id == -1L) {
			update(obj)
		}
	}

	@Transaction
	open fun upsert(objList: Collection<T>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		if (updateList.isNotEmpty()) {
			update(updateList)
		}
	}
}