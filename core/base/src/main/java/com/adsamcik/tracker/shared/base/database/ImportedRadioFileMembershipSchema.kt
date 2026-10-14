package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun createImportedRadioFileMembershipSchema(database: SupportSQLiteDatabase) {
	database.execSQL("CREATE TABLE IF NOT EXISTS imported_radio_file_membership (" +
		"source_kind INTEGER NOT NULL, import_job_id TEXT NOT NULL, import_entry_key TEXT NOT NULL, " +
		"entry_identity TEXT NOT NULL, entry_import_revision INTEGER NOT NULL, file_entry_checksum TEXT NOT NULL, " +
		"member_kind TEXT NOT NULL, member_identity TEXT NOT NULL, member_revision INTEGER NOT NULL, " +
		"member_checksum TEXT NOT NULL, collected_data_epoch INTEGER NOT NULL, " +
		"PRIMARY KEY(source_kind, import_job_id, import_entry_key, member_kind, member_identity))")
	database.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_radio_file_entry ON imported_radio_file_membership " +
		"(source_kind, entry_identity, entry_import_revision)")
}
