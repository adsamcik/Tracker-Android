package com.adsamcik.signalcollector.game.challenge.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.signalcollector.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.signalcollector.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.signalcollector.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.signalcollector.game.challenge.database.dao.ChallengeEntryDao
import com.adsamcik.signalcollector.game.challenge.database.dao.ExplorerChallengeDao
import com.adsamcik.signalcollector.game.challenge.database.dao.SessionChallengeDataDao
import com.adsamcik.signalcollector.game.challenge.database.dao.StepChallengeDao
import com.adsamcik.signalcollector.game.challenge.database.dao.WalkDistanceChallengeDao
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.signalcollector.game.challenge.database.typeconverter.ChallengeDifficultyTypeConverter

@Database(entities = [ChallengeSessionData::class, ChallengeEntry::class, ExplorerChallengeEntity::class, WalkDistanceChallengeEntity::class, StepChallengeEntity::class],
		version = 1)
@TypeConverters(ChallengeDifficultyTypeConverter::class)
abstract class ChallengeDatabase : RoomDatabase() {

	abstract val entryDao: ChallengeEntryDao

	abstract val sessionDao: SessionChallengeDataDao

	abstract val explorerDao: ExplorerChallengeDao

	abstract val walkDistanceDao: WalkDistanceChallengeDao

	abstract val stepDao: StepChallengeDao

	companion object {
		private var instance_: ChallengeDatabase? = null

		private fun createInstance(context: Context): ChallengeDatabase {
			val instance = Room.databaseBuilder(context.applicationContext, ChallengeDatabase::class.java, "challenge_database")
					.build()

			instance_ = instance
			return instance
		}

		fun getDatabase(context: Context): ChallengeDatabase {
			return instance_ ?: createInstance(context)
		}

		fun getTestDatabase(context: Context): ChallengeDatabase {
			return Room.inMemoryDatabaseBuilder(context.applicationContext, ChallengeDatabase::class.java)
					.build()
		}
	}
}
