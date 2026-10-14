package com.adsamcik.tracker.shared.base.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Development v29 remains readable; the complete assembled additions have one upgrade. */
val MIGRATION_29_30: Migration = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createLegacyPreservedDataSchema(db)
        createRestoredDataEpochSchema(db)
        db.execSQL("DROP VIEW IF EXISTS effective_location_sample")
        createLocationPointCorrectionSchema(db)
        createImportedRadioFileMembershipSchema(db)
    }
}

internal fun createRestoredDataEpochSchema(db: SupportSQLiteDatabase) {
    val exists = db.query("PRAGMA table_info(source_evidence_state)").use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        var found = false
        while (cursor.moveToNext()) if (cursor.getString(name) == "restored_data_epochs") found = true
        found
    }
    if (!exists) db.execSQL("ALTER TABLE source_evidence_state ADD COLUMN restored_data_epochs TEXT NOT NULL DEFAULT ''")
}
