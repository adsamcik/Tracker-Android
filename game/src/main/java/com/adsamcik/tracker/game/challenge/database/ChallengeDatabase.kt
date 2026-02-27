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
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeHistoryDao
import com.adsamcik.tracker.game.challenge.database.dao.ChallengePersonalRecordDao
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeStreakDao
import com.adsamcik.tracker.game.challenge.database.dao.PlayerProfileDao
import com.adsamcik.tracker.game.challenge.database.dao.XpLedgerDao
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeHistoryEntity
import com.adsamcik.tracker.game.challenge.database.entity.ChallengePersonalRecordEntity
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeStreakEntity
import com.adsamcik.tracker.game.challenge.database.entity.PlayerProfileEntity
import com.adsamcik.tracker.game.challenge.database.entity.XpLedgerEntity
import com.adsamcik.tracker.game.challenge.database.migration.MIGRATION_2_3
import com.adsamcik.tracker.game.challenge.database.migration.MIGRATION_3_4
import com.adsamcik.tracker.game.challenge.database.migration.MIGRATION_4_5
import com.adsamcik.tracker.game.challenge.database.migration.MIGRATION_5_6
import com.adsamcik.tracker.game.challenge.database.migration.MIGRATION_6_7
import com.adsamcik.tracker.game.challenge.database.typeconverter.ChallengeDifficultyStringTypeConverter
import com.adsamcik.tracker.game.minigame.database.MiniGameScoreDao
import com.adsamcik.tracker.game.minigame.database.MiniGameScoreEntity
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
        ActiveTimeChallengeEntity::class,
        ChallengeHistoryEntity::class,
        XpLedgerEntity::class,
        PlayerProfileEntity::class,
        ChallengeStreakEntity::class,
        ChallengePersonalRecordEntity::class,
        MiniGameScoreEntity::class
    ],
    autoMigrations = [
		AutoMigration(from = 1, to = 2, spec = VersionOneToTwoMigration::class)
	],
    version = 7
)
@TypeConverters(ChallengeDifficultyStringTypeConverter::class)
abstract class ChallengeDatabase : RoomDatabase() {

    abstract fun challengeDao(): ChallengeDao

    abstract fun entryDao(): ChallengeEntryDao

    abstract fun sessionDao(): SessionChallengeDataDao

    abstract fun explorerDao(): ExplorerChallengeDao

    abstract fun walkDistanceDao(): WalkDistanceChallengeDao

    abstract fun stepDao(): StepChallengeDao

    abstract fun activeTimeDao(): ActiveTimeChallengeDao

    abstract fun challengeHistoryDao(): ChallengeHistoryDao

    abstract fun xpLedgerDao(): XpLedgerDao

    abstract fun playerProfileDao(): PlayerProfileDao

    abstract fun challengeStreakDao(): ChallengeStreakDao

    abstract fun challengePersonalRecordDao(): ChallengePersonalRecordDao

    abstract fun miniGameScoreDao(): MiniGameScoreDao

    companion object : ObjectBaseDatabase<ChallengeDatabase>(ChallengeDatabase::class.java) {
        override fun setupDatabase(database: Builder<ChallengeDatabase>) {
            database.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
