package com.adsamcik.tracker.game.challenge.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.game.challenge.data.entity.ActiveTimeChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase.VersionOneToTwoMigration
import com.adsamcik.tracker.game.challenge.database.dao.ActiveTimeChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeEntryDao
import com.adsamcik.tracker.game.challenge.database.dao.ExplorerChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.SessionChallengeDataDao
import com.adsamcik.tracker.game.challenge.database.dao.StepChallengeDao
import com.adsamcik.tracker.game.challenge.database.dao.WalkDistanceChallengeDao
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.database.migration.MIGRATION_2_3
import com.adsamcik.tracker.game.challenge.database.typeconverter.ChallengeDifficultyTypeConverter
import com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase

/**
 * Challenge database
 */
@Database(
    entities = [
        ChallengeSessionData::class,
        ChallengeEntry::class,
        ChallengeEntity::class,
        ExplorerChallengeEntity::class,
        WalkDistanceChallengeEntity::class,
        StepChallengeEntity::class,
        ActiveTimeChallengeEntity::class
    ],
    autoMigrations = [
		AutoMigration(from = 1, to = 2, spec = VersionOneToTwoMigration::class)
	],
    version = 3
)
@TypeConverters(ChallengeDifficultyTypeConverter::class)
abstract class ChallengeDatabase : RoomDatabase() {

    abstract fun challengeDao(): ChallengeDao

    abstract fun entryDao(): ChallengeEntryDao

    abstract fun sessionDao(): SessionChallengeDataDao

    abstract fun explorerDao(): ExplorerChallengeDao

    abstract fun walkDistanceDao(): WalkDistanceChallengeDao

    abstract fun stepDao(): StepChallengeDao

    abstract fun activeTimeDao(): ActiveTimeChallengeDao

    companion object : ObjectBaseDatabase<ChallengeDatabase>(ChallengeDatabase::class.java) {
        override fun setupDatabase(database: Builder<ChallengeDatabase>) {
            database.addMigrations(MIGRATION_2_3)
        }

        override val databaseName: String get() = DATABASE_NAME

        private const val DATABASE_NAME = "challenge_database"
    }


    class VersionOneToTwoMigration : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
            CREATE TABLE IF NOT EXISTS challenge_active_time (
                id INTEGER NOT NULL PRIMARY KEY,
                activeTimeInMinutes INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                requiredActiveTimeInMinutes INTEGER NOT NULL,
                entry_id INTEGER NOT NULL
            )
        """)
        }
    }
}

