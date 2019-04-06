package com.adsamcik.signalcollector.game.challenge.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.database.data.*
import com.adsamcik.signalcollector.game.challenge.database.dao.ExplorerChallengeDao
import com.adsamcik.signalcollector.tracker.data.TrackerSession

@Database(entities = [DatabaseLocation::class,
	TrackerSession::class,
	DatabaseWifiData::class,
	DatabaseCellData::class,
	DatabaseMapMaxHeat::class],
		version = 6)
@TypeConverters(CellTypeTypeConverter::class)
abstract class ChallengeDatabase : RoomDatabase() {

	abstract fun explorerDao(): ExplorerChallengeDao

	companion object {
		private var instance_: ChallengeDatabase? = null

		private fun createInstance(context: Context): ChallengeDatabase {
			val instance = Room.databaseBuilder(context.applicationContext, ChallengeDatabase::class.java, "challenge_database")
					.build()

			instance_ = instance
			return instance
		}

		fun getAppDatabase(context: Context): ChallengeDatabase {
			return instance_ ?: createInstance(context)
		}

		fun getTestDatabase(context: Context): ChallengeDatabase {
			return Room.inMemoryDatabaseBuilder(context.applicationContext, ChallengeDatabase::class.java).build()
		}
	}
}