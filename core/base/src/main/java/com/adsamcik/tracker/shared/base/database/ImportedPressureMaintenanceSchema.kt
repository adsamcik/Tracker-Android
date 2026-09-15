package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive v28 DDL owned by the Pressure maintenance lane.
 *
 * The shared migration owner must invoke this from `MIGRATION_27_28`; Room entities and full-clear
 * wiring remain deliberately outside this source-specific file. The original
 * `imported_pressure_entry_deletion` CREATE statement must also include
 * `run_deletion_count`, `run_deletion_set_checksum`, `identity_fence_count`, and
 * `identity_fence_set_checksum`.
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
		    `recency_start_time_ms` INTEGER NOT NULL,
		    `recency_end_time_ms` INTEGER NOT NULL,
		    `recency_tie_identity` TEXT NOT NULL,
		    `revision_count` INTEGER NOT NULL,
		    `import_receipt_count` INTEGER NOT NULL,
		    `run_row_count` INTEGER NOT NULL,
		    `window_row_count` INTEGER NOT NULL,
		    `run_deletion_count` INTEGER NOT NULL,
		    `run_deletion_set_checksum` TEXT NOT NULL,
		    `protected_identity_count` INTEGER NOT NULL,
		    `protected_identity_set_checksum` TEXT NOT NULL,
		    `identity_fence_set_checksum` TEXT NOT NULL,
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
		CREATE TABLE IF NOT EXISTS `imported_pressure_identity_fence` (
		    `protected_identity` TEXT NOT NULL,
		    `identity_kind` TEXT NOT NULL,
		    `entry_identity` TEXT NOT NULL,
		    `run_identity` TEXT,
		    `original_collected_data_epoch` INTEGER NOT NULL,
		    `fence_generation` INTEGER NOT NULL,
		    `fenced_at_ms` INTEGER NOT NULL,
		    `fence_reason` TEXT NOT NULL,
		    `effect_checksum` TEXT NOT NULL,
		    PRIMARY KEY(`protected_identity`)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS `idx_imported_pressure_identity_fence_entry` " +
			"ON `imported_pressure_identity_fence` (`entry_identity`)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS `imported_pressure_source_erase` (
		    `id` INTEGER NOT NULL,
		    `collected_data_epoch` INTEGER NOT NULL,
		    `source_evidence_revision` INTEGER NOT NULL,
		    `erased_at_ms` INTEGER NOT NULL,
		    `provider_registration_generation` INTEGER,
		    `legacy_write_fence_generation` INTEGER NOT NULL,
		    `local_fact_revision_count` INTEGER NOT NULL,
		    `local_wal_event_count` INTEGER NOT NULL,
		    `legacy_sample_count` INTEGER NOT NULL,
		    `legacy_sample_set_checksum` TEXT NOT NULL,
		    `imported_entry_count` INTEGER NOT NULL,
		    `imported_revision_count` INTEGER NOT NULL,
		    `imported_run_count` INTEGER NOT NULL,
		    `imported_window_count` INTEGER NOT NULL,
		    `fenced_local_run_count` INTEGER NOT NULL,
		    `local_scope_set_checksum` TEXT NOT NULL,
		    `entry_deletion_count` INTEGER NOT NULL,
		    `entry_deletion_set_checksum` TEXT NOT NULL,
		    `run_deletion_count` INTEGER NOT NULL,
		    `run_deletion_set_checksum` TEXT NOT NULL,
		    `identity_fence_count` INTEGER NOT NULL,
		    `identity_fence_set_checksum` TEXT NOT NULL,
		    `effect_checksum` TEXT NOT NULL,
		    PRIMARY KEY(`id`)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS `imported_pressure_source_erase_witness` (
		    `source_erase_id` INTEGER NOT NULL,
		    `witness_kind` TEXT NOT NULL,
		    `witness_identity` TEXT NOT NULL,
		    `authority_checksum` TEXT NOT NULL,
		    `effect_checksum` TEXT NOT NULL,
		    PRIMARY KEY(`witness_kind`, `witness_identity`),
		    FOREIGN KEY(`source_erase_id`)
		        REFERENCES `imported_pressure_source_erase`(`id`)
		        ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS `idx_imported_pressure_source_erase_witness_receipt` " +
			"ON `imported_pressure_source_erase_witness` (`source_erase_id`)",
	)
}
