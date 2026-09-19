package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive portable-origin storage; these tables confer no native Steps acquisition authority. */
@Suppress("LongMethod")
internal fun createImportedAmbientStepsTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_archive (
			archive_identity TEXT NOT NULL,
			content_checksum TEXT NOT NULL,
			source_format TEXT NOT NULL,
			source_schema_version INTEGER NOT NULL,
			encoded_byte_count INTEGER NOT NULL,
			day_count INTEGER NOT NULL,
			fact_count INTEGER NOT NULL,
			gap_count INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			first_received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(archive_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_steps_archive_checksum " +
			"ON imported_ambient_steps_archive(content_checksum)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_receipt (
			import_job_id TEXT NOT NULL,
			archive_key TEXT NOT NULL,
			receipt_identity TEXT NOT NULL,
			source_name TEXT NOT NULL,
			received_at_ms INTEGER NOT NULL,
			archive_identity TEXT NOT NULL,
			archive_content_checksum TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			PRIMARY KEY(import_job_id, archive_key),
			FOREIGN KEY(archive_identity) REFERENCES imported_ambient_steps_archive(archive_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_receipt_archive " +
			"ON imported_ambient_steps_receipt(archive_identity)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_steps_receipt_identity " +
			"ON imported_ambient_steps_receipt(receipt_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_archive_day (
			archive_identity TEXT NOT NULL,
			ordinal INTEGER NOT NULL,
			day_identity TEXT NOT NULL,
			day_content_checksum TEXT NOT NULL,
			bound_day_import_revision INTEGER NOT NULL,
			bound_count_domain_graph_revision INTEGER NOT NULL,
			fact_count INTEGER NOT NULL,
			gap_count INTEGER NOT NULL,
			PRIMARY KEY(archive_identity, ordinal),
			FOREIGN KEY(archive_identity) REFERENCES imported_ambient_steps_archive(archive_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_steps_archive_day_owner " +
			"ON imported_ambient_steps_archive_day(archive_identity, day_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_archive_day_entry " +
			"ON imported_ambient_steps_archive_day(day_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_day_revision (
			day_identity TEXT NOT NULL,
			import_revision INTEGER NOT NULL,
			supersedes_import_revision INTEGER,
			archive_identity TEXT NOT NULL,
			day_content_checksum TEXT NOT NULL,
			deletion_scope_identity TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_day_start_time_ms INTEGER NOT NULL,
			structural_day_end_time_ms INTEGER NOT NULL,
			retained_from_time_ms INTEGER,
			coverage TEXT NOT NULL,
			partial_causes TEXT NOT NULL,
			retained_step_count INTEGER NOT NULL,
			fact_count INTEGER NOT NULL,
			gap_count INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(day_identity, import_revision),
			FOREIGN KEY(archive_identity) REFERENCES imported_ambient_steps_archive(archive_identity)
				ON UPDATE NO ACTION ON DELETE RESTRICT
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_steps_day_archive_owner " +
			"ON imported_ambient_steps_day_revision(archive_identity, day_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_day_structural " +
			"ON imported_ambient_steps_day_revision(structural_epoch_day, stored_zone_id, structural_day_start_time_ms)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_day_scope " +
			"ON imported_ambient_steps_day_revision(deletion_scope_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_fact (
			day_identity TEXT NOT NULL,
			day_import_revision INTEGER NOT NULL,
			fact_identity TEXT NOT NULL,
			content_checksum TEXT NOT NULL,
			interval_start_time_ms INTEGER NOT NULL,
			interval_end_time_ms INTEGER NOT NULL,
			step_count INTEGER NOT NULL,
			PRIMARY KEY(day_identity, day_import_revision, fact_identity),
			FOREIGN KEY(day_identity, day_import_revision)
				REFERENCES imported_ambient_steps_day_revision(day_identity, import_revision)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_fact_day " +
			"ON imported_ambient_steps_fact(day_identity, day_import_revision)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_fact_identity " +
			"ON imported_ambient_steps_fact(fact_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_fact_window " +
			"ON imported_ambient_steps_fact(interval_start_time_ms, interval_end_time_ms)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_gap (
			day_identity TEXT NOT NULL,
			day_import_revision INTEGER NOT NULL,
			gap_identity TEXT NOT NULL,
			content_checksum TEXT NOT NULL,
			interval_start_time_ms INTEGER NOT NULL,
			interval_end_time_ms INTEGER NOT NULL,
			reason TEXT NOT NULL,
			PRIMARY KEY(day_identity, day_import_revision, gap_identity),
			FOREIGN KEY(day_identity, day_import_revision)
				REFERENCES imported_ambient_steps_day_revision(day_identity, import_revision)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_gap_day " +
			"ON imported_ambient_steps_gap(day_identity, day_import_revision)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_gap_identity " +
			"ON imported_ambient_steps_gap(gap_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_gap_window " +
			"ON imported_ambient_steps_gap(interval_start_time_ms, interval_end_time_ms)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_day_fence (
			day_identity TEXT NOT NULL,
			deletion_scope_identity TEXT NOT NULL,
			fence_kind TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			source_evidence_revision INTEGER NOT NULL,
			fenced_at_ms INTEGER NOT NULL,
			retained_from_ms INTEGER,
			latest_import_revision INTEGER NOT NULL,
			latest_content_checksum TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_day_start_time_ms INTEGER NOT NULL,
			structural_day_end_time_ms INTEGER NOT NULL,
			revision_count INTEGER NOT NULL,
			archive_count INTEGER NOT NULL,
			fact_row_count INTEGER NOT NULL,
			gap_row_count INTEGER NOT NULL,
			protected_identity_count INTEGER NOT NULL,
			protected_identity_set_checksum TEXT NOT NULL,
			lineage_checksum TEXT NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(day_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_fence_window " +
			"ON imported_ambient_steps_day_fence(structural_day_start_time_ms, structural_day_end_time_ms)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_steps_fence_scope " +
			"ON imported_ambient_steps_day_fence(deletion_scope_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_protected_identity (
			protected_identity TEXT NOT NULL,
			owner_day_identity TEXT NOT NULL,
			identity_kind TEXT NOT NULL,
			PRIMARY KEY(protected_identity, owner_day_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_steps_protected_owner " +
			"ON imported_ambient_steps_protected_identity(owner_day_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_steps_source_fence (
			id INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			revoked_consent_epoch INTEGER NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			deletion_completed INTEGER NOT NULL,
			completed_at_ms INTEGER,
			reopened_consent_epoch INTEGER,
			reopened_at_ms INTEGER,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(id)
		)
		""".trimIndent(),
	)
}
