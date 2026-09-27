package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun createOrganizationAnnotationTable(database: SupportSQLiteDatabase) {
	database.execSQL("""
		CREATE TABLE IF NOT EXISTS organization_annotation (
			local_id TEXT NOT NULL, revision INTEGER NOT NULL,
			created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
			recording_adapter_key TEXT, start_time_ms INTEGER, end_time_ms INTEGER,
			display_zone_id TEXT, name TEXT, activity_label_id INTEGER, hidden INTEGER NOT NULL,
			PRIMARY KEY(local_id)
		)
	""".trimIndent())
	database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_organization_adapter " +
		"ON organization_annotation(recording_adapter_key)")
	database.execSQL("CREATE INDEX IF NOT EXISTS idx_organization_bounds " +
		"ON organization_annotation(hidden, start_time_ms, end_time_ms)")
	database.execSQL("CREATE INDEX IF NOT EXISTS idx_organization_end ON organization_annotation(end_time_ms)")
}

/** Negative built-in IDs are virtual references, so a FK to activity cannot represent all labels. */
internal fun createOrganizationAnnotationLabelTrigger(database: SupportSQLiteDatabase) {
	database.execSQL("""
		CREATE TRIGGER IF NOT EXISTS clear_organization_activity_label
		AFTER DELETE ON activity
		BEGIN
			UPDATE organization_annotation SET activity_label_id = NULL, revision = revision + 1
			WHERE activity_label_id = OLD.id;
		END
	""".trimIndent())
}
