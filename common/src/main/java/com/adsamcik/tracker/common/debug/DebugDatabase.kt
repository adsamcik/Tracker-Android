package com.adsamcik.tracker.common.debug

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration

@Database(
		entities = [LogData::class],
		version = 1
)
abstract class DebugDatabase : RoomDatabase() {
	companion object {
		private var instance_: DebugDatabase? = null
		private const val DATABASE_NAME = "DebugDatabase"

		fun createInstance(context: Context): DebugDatabase {
			val configuration = SQLiteDatabaseConfiguration(
					context.getDatabasePath(DATABASE_NAME).path,
					SQLiteDatabase.OPEN_CREATE or SQLiteDatabase.OPEN_READWRITE
			)
			val options = RequerySQLiteOpenHelperFactory.ConfigurationOptions { configuration }
			val instance = Room.databaseBuilder(
					context.applicationContext,
					DebugDatabase::class.java,
					DATABASE_NAME
			)
					.openHelperFactory(RequerySQLiteOpenHelperFactory(listOf(options)))
					.build()
			instance_ = instance
			return instance
		}
	}
}
