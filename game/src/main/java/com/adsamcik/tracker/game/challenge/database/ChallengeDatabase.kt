package com.adsamcik.tracker.game.challenge.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeEntryDao
import com.adsamcik.tracker.game.challenge.database.dao.ExplorerChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.SessionChallengeDataDao
import com.adsamcik.tracker.game.challenge.database.dao.StepChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.WalkDistanceChallengeDao
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.tracker.game.challenge.database.typeconverter.ChallengeDifficultyTypeConverter
import com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase

/**
 * Challenge database
 */
@Database(
		entities = [
			ChallengeSessionData::class,
			ChallengeEntry::class,
			ExplorerChallengeEntity::class,
			WalkDistanceChallengeEntity::class,
			StepChallengeEntity::class
		],
		version = 1
)
@TypeConverters(ChallengeDifficultyTypeConverter::class)
abstract class ChallengeDatabase : RoomDatabase() {

	abstract val entryDao: ChallengeEntryDao

	abstract val sessionDao: SessionChallengeDataDao

	abstract val explorerDao: ExplorerChallengeDao

	abstract val walkDistanceDao: WalkDistanceChallengeDao

	abstract val stepDao: StepChallengeDao

	companion object : ObjectBaseDatabase<ChallengeDatabase>(ChallengeDatabase::class.java) {
		override fun setupDatabase(database: Builder<ChallengeDatabase>): Unit = Unit

		override val databaseName: String get() = DATABASE_NAME

		private const val DATABASE_NAME = "challenge_database"
	}
}

