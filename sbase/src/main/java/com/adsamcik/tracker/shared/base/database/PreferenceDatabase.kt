package com.adsamcik.tracker.shared.base.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.adsamcik.tracker.shared.base.database.dao.GenericPreferenceDao
import com.adsamcik.tracker.shared.base.database.dao.NotificationPreferenceDao
import com.adsamcik.tracker.shared.base.database.data.GenericPreference
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference

@Database(
		entities = [GenericPreference::class, NotificationPreference::class],
		version = 1
)
abstract class PreferenceDatabase : RoomDatabase() {
	abstract fun getGenericDao(): GenericPreferenceDao

	abstract fun getNotificationDao(): NotificationPreferenceDao

	companion object : ObjectBaseDatabase<PreferenceDatabase>(PreferenceDatabase::class.java) {
		override val databaseName: String = "preference_database"

		override fun setupDatabase(database: Builder<PreferenceDatabase>): Unit = Unit

	}
}
