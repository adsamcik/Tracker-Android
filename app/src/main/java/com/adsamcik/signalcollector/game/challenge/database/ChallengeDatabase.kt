package com.adsamcik.signalcollector.game.challenge.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.game.challenge.database.dao.ChallengeEntryDao
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.extra.ExplorerChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.typeconverter.ChallengeDifficultyTypeConverter

@Database(entities = [ChallengeEntry::class, ExplorerChallengeEntry::class],
		version = 1)
@TypeConverters(ChallengeDifficultyTypeConverter::class)
abstract class ChallengeDatabase : RoomDatabase() {

	abstract val entryDao: ChallengeEntryDao

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