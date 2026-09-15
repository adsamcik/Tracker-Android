package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive v28 DDL owned by the Pressure maintenance lane.
 *
 * The shared migration owner must invoke this from `MIGRATION_27_28`; Room entities and full-clear
 * wiring remain deliberately outside this source-specific file.
 */
fun createImportedPressureMaintenanceTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS `imported_pressure_retention_receipt` (
		    `entry_identity` TEXT NOT NULL,
		    `collected_data_epoch` INTEGER NOT NULL,
		    `source_evidence_revision` INTEGER NOT NULL,
		    `retained_from_ms` INTEGER NOT NULL,
		    `retained_at_ms` INTEGER NOT NULL,
		    `latest_import_revision` INTEGER NOT NULL,
		    `latest_content_checksum` TEXT NOT NULL,
		    `start_time_ms` INTEGER NOT NULL,
		    `end_time_ms` INTEGER NOT NULL,
		    `received_at_ms` INTEGER NOT NULL,
		    `revision_count` INTEGER NOT NULL,
		    `import_receipt_count` INTEGER NOT NULL,
		    `run_row_count` INTEGER NOT NULL,
		    `window_row_count` INTEGER NOT NULL,
		    `run_deletion_count` INTEGER NOT NULL,
		    `run_deletion_set_checksum` TEXT NOT NULL,
		    `protected_identity_count` INTEGER NOT NULL,
		    `protected_identity_set_checksum` TEXT NOT NULL,
		    `lineage_authority_checksum` TEXT NOT NULL,
		    `effect_checksum` TEXT NOT NULL,
		    PRIMARY KEY(`entry_identity`)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS `imported_pressure_retained_identity` (
		    `protected_identity` TEXT NOT NULL,
		    `entry_identity` TEXT NOT NULL,
		    `identity_kind` TEXT NOT NULL,
		    PRIMARY KEY(`protected_identity`),
		    FOREIGN KEY(`entry_identity`)
		        REFERENCES `imported_pressure_retention_receipt`(`entry_identity`)
		        ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS `idx_imported_pressure_retained_identity_entry` " +
			"ON `imported_pressure_retained_identity` (`entry_identity`)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS `imported_pressure_source_erase` (
		    `id` INTEGER NOT NULL,
		    `collected_data_epoch` INTEGER NOT NULL,
		    `source_evidence_revision` INTEGER NOT NULL,
		    `erased_at_ms` INTEGER NOT NULL,
		    `local_fact_revision_count` INTEGER NOT NULL,
		    `local_wal_event_count` INTEGER NOT NULL,
		    `imported_entry_count` INTEGER NOT NULL,
		    `imported_revision_count` INTEGER NOT NULL,
		    `imported_run_count` INTEGER NOT NULL,
		    `imported_window_count` INTEGER NOT NULL,
		    `fenced_local_run_count` INTEGER NOT NULL,
		    `effect_checksum` TEXT NOT NULL,
		    PRIMARY KEY(`id`)
		)
		""".trimIndent(),
	)
}
