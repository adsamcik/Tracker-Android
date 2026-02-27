package com.adsamcik.tracker.game.challenge.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recreate legacy 'entry' table with TEXT columns for type/difficulty
        // to match Room's current schema expectations.
        // Data was already migrated to the unified 'challenge' table in MIGRATION_2_3.
        db.execSQL("DROP TABLE IF EXISTS `entry`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `entry` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type` TEXT NOT NULL,
                `start_time` INTEGER NOT NULL,
                `end_time` INTEGER NOT NULL,
                `difficulty` TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}
