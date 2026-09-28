@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun createAdditionalTrackingTables(database: SupportSQLiteDatabase) {
	createLocationFileIdentityTable(database)
	createOrganizationAnnotationTable(database)
	createCollectedDataDeletionOperationTable(database)
	createRetentionWorkExecutionReceiptTable(database)
	createSourceEventRetentionProofTable(database)
	createSourceEventRetentionScopeProofTable(database)
	createCapturedRadioFactRetentionProofTable(database)
	createWifiSelectedDeletionTables(database)
	createCellSelectedDeletionTables(database)
	createAmbientStepsRetentionTable(database)
	createAmbientStepsNativeReplayFootprintTable(database)
	createLocationPurposeTables(database)
	createAmbientRadioTables(database)
	createRuntimeSettlementTables(database)
}

internal fun createLocationFileIdentityTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"CREATE TABLE IF NOT EXISTS location_file_identity (" +
			"portable_id TEXT NOT NULL, sample_id INTEGER NOT NULL, " +
			"imported INTEGER NOT NULL, reported_origin TEXT NOT NULL, " +
			"PRIMARY KEY(portable_id), FOREIGN KEY(sample_id) REFERENCES location_sample(id) " +
			"ON UPDATE NO ACTION ON DELETE CASCADE)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_location_file_sample " +
			"ON location_file_identity(sample_id)",
	)
}

private fun createCapturedRadioFactRetentionProofTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS captured_radio_fact_retention_proof (
			source_kind INTEGER NOT NULL,
			writer_projection_id TEXT NOT NULL,
			writer_projection_version INTEGER NOT NULL,
			writer_binding_generation INTEGER NOT NULL,
			writer_owner_generation INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			mutation_id TEXT NOT NULL,
			fact_effect_checksum TEXT NOT NULL,
			source_event_id TEXT NOT NULL,
			source_admission_ordinal INTEGER NOT NULL,
			wal_integrity_identity TEXT NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			capture_consent_epoch INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			logical_tracking_id TEXT NOT NULL,
			service_run_id TEXT NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			logical_run_scope_digest TEXT NOT NULL,
			cursor_revision INTEGER NOT NULL,
			cursor_mutation_id TEXT NOT NULL,
			cursor_effect_checksum TEXT NOT NULL,
			cursor_source_admission_ordinal INTEGER NOT NULL,
			cursor_updated_at_ms INTEGER NOT NULL,
			aggregate_owner_logical_fact_id TEXT,
			aggregate_owner_semantic_revision INTEGER,
			aggregate_owner_source_event_id TEXT,
			aggregate_owner_source_admission_ordinal INTEGER,
			aggregate_owner_wal_integrity_identity TEXT,
			aggregate_owner_effect_checksum TEXT,
			aggregate_owner_cursor_revision INTEGER,
			retained_from_ms INTEGER NOT NULL,
			retained_at_ms INTEGER NOT NULL,
			reason TEXT NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(source_kind, writer_projection_id, writer_projection_version, logical_fact_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_captured_radio_retention_event " +
			"ON captured_radio_fact_retention_proof(source_kind, source_event_id)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_captured_radio_retention_floor " +
			"ON captured_radio_fact_retention_proof(source_kind, retained_from_ms)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_captured_radio_retention_run_admission " +
			"ON captured_radio_fact_retention_proof(source_kind, logical_tracking_id, " +
			"service_run_id, collected_data_epoch, source_admission_ordinal)",
	)
}

private fun createSourceEventRetentionProofTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_event_retention_proof (
			source_kind INTEGER NOT NULL,
			event_id TEXT NOT NULL,
			source_admission_ordinal INTEGER NOT NULL,
			purpose_eligibility_mask INTEGER NOT NULL,
			source_policy_revision INTEGER,
			capture_consent_epoch INTEGER,
			collected_data_epoch INTEGER NOT NULL,
			logical_tracking_id TEXT,
			service_run_id TEXT,
			scope_deletion_generation INTEGER,
			logical_run_scope_digest TEXT,
			wal_payload_checksum TEXT NOT NULL,
			wal_integrity_identity TEXT NOT NULL,
			proof_authority_fingerprint TEXT NOT NULL,
			reason TEXT NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(source_kind, event_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_source_event_retention_proof_admission " +
			"ON source_event_retention_proof(source_kind, source_admission_ordinal)",
	)
}

private fun createSourceEventRetentionScopeProofTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_event_retention_scope_proof (
			source_kind INTEGER NOT NULL,
			purpose TEXT NOT NULL,
			logical_tracking_id TEXT NOT NULL,
			service_run_id TEXT NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			capture_consent_epoch INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			logical_run_scope_digest TEXT NOT NULL,
			retained_event_high_water_ordinal INTEGER NOT NULL,
			retained_event_authority_checksum TEXT NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(source_kind, logical_run_scope_digest)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_source_event_retention_scope_run " +
			"ON source_event_retention_scope_proof(" +
			"source_kind, logical_tracking_id, service_run_id)",
	)
}

private fun createCollectedDataDeletionOperationTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS collected_data_deletion_operation (
			operation_id TEXT NOT NULL,
			target_collected_data_epoch INTEGER NOT NULL,
			retained_from_ms INTEGER,
			deleted_at_ms INTEGER NOT NULL,
			phase TEXT NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			retention_work_execution_id TEXT,
			retention_destructive_plan TEXT,
			settled_retained_from_ms INTEGER,
			source_maintenance_at_ms INTEGER,
			retention_active_sources TEXT,
			PRIMARY KEY(operation_id)
		)
		""".trimIndent(),
	)
}

private fun createRetentionWorkExecutionReceiptTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS retention_work_execution_receipt (
			execution_id TEXT NOT NULL,
			work_request_id TEXT NOT NULL,
			execution_generation INTEGER NOT NULL,
			worker_kind TEXT NOT NULL,
			started_at_ms INTEGER NOT NULL,
			state TEXT NOT NULL,
			destructive_plan TEXT,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(execution_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_retention_work_execution_generation " +
			"ON retention_work_execution_receipt(work_request_id, execution_generation)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_retention_work_execution_state " +
			"ON retention_work_execution_receipt(work_request_id, state)",
	)
}

private fun createAmbientStepsNativeReplayFootprintTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_steps_native_replay_footprint (
			protected_identity TEXT NOT NULL,
			identity_kind TEXT NOT NULL,
			owner_day_identity TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			protected_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(protected_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_steps_native_footprint_owner " +
			"ON ambient_steps_native_replay_footprint(owner_day_identity)",
	)
}

private fun createAmbientStepsRetentionTable(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_steps_retention_authority (
			scope TEXT NOT NULL,
			approval_revision INTEGER NOT NULL,
			state TEXT NOT NULL,
			opaque_policy_id TEXT NOT NULL,
			source_policy_revision INTEGER,
			ambient_consent_epoch INTEGER,
			collected_data_epoch INTEGER NOT NULL,
			retained_from_ms INTEGER,
			effective_boot_id TEXT NOT NULL,
			effective_elapsed_realtime_nanos INTEGER NOT NULL,
			effective_wall_time_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(scope, approval_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_steps_retention_effective " +
			"ON ambient_steps_retention_authority(" +
			"scope, effective_boot_id, effective_elapsed_realtime_nanos, approval_revision)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_steps_retention_scope " +
			"ON ambient_steps_retention_authority(scope)",
	)
}

private fun createWifiSelectedDeletionTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS wifi_selected_deletion_receipt (
			selection_identity TEXT NOT NULL,
			origin TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			selected_import_revision INTEGER,
			selected_content_checksum TEXT,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			expected_run_count INTEGER NOT NULL,
			expected_observation_count INTEGER NOT NULL,
			expected_protected_identity_count INTEGER NOT NULL,
			protected_identity_set_checksum TEXT NOT NULL,
			run_deletion_set_checksum TEXT NOT NULL,
			source_fence_set_checksum TEXT NOT NULL,
			retained_from_ms INTEGER,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(selection_identity, origin)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS wifi_selected_deletion_protected_identity (
			selection_identity TEXT NOT NULL,
			receipt_origin TEXT NOT NULL,
			identity_kind TEXT NOT NULL,
			protected_identity TEXT NOT NULL,
			owner_entry_identity TEXT NOT NULL,
			owner_run_identity TEXT,
			deletion_scope_digest TEXT,
			aggregate_owner_identity TEXT,
			aggregate_owner_semantic_revision INTEGER,
			revision_count INTEGER NOT NULL,
			revision_set_checksum TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(selection_identity, receipt_origin, identity_kind, protected_identity),
			FOREIGN KEY(selection_identity, receipt_origin)
				REFERENCES wifi_selected_deletion_receipt(selection_identity, origin)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_wifi_selected_deletion_protected_receipt " +
			"ON wifi_selected_deletion_protected_identity(selection_identity, receipt_origin)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_wifi_selected_deletion_protected_identity " +
			"ON wifi_selected_deletion_protected_identity(protected_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_wifi_selected_deletion_owner_entry " +
			"ON wifi_selected_deletion_protected_identity(owner_entry_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_wifi_selected_deletion_owner_run " +
			"ON wifi_selected_deletion_protected_identity(owner_run_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_wifi_selected_deletion_scope " +
			"ON wifi_selected_deletion_protected_identity(deletion_scope_digest)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_wifi_selected_deletion_aggregate_owner " +
			"ON wifi_selected_deletion_protected_identity(aggregate_owner_identity)",
	)
}

private fun createCellSelectedDeletionTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_cell_entry_deletion_receipt (
			entry_identity TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			deleted_import_revision INTEGER NOT NULL,
			deleted_content_checksum TEXT NOT NULL,
			session_mode TEXT NOT NULL,
			subscription_grouping TEXT NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			retained_from_ms INTEGER,
			expected_revision_count INTEGER NOT NULL,
			expected_receipt_count INTEGER NOT NULL,
			expected_run_count INTEGER NOT NULL,
			expected_observation_count INTEGER NOT NULL,
			expected_protected_identity_count INTEGER NOT NULL,
			protected_identity_set_checksum TEXT NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(entry_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_cell_deleted_identity (
			protected_identity TEXT NOT NULL,
			entry_identity TEXT NOT NULL,
			identity_kind TEXT NOT NULL,
			run_identity TEXT,
			aggregate_owner_identity TEXT,
			content_checksum TEXT,
			included_in_latest INTEGER NOT NULL,
			observation_ordinal INTEGER,
			deletion_scope_digest TEXT,
			run_start_time_ms INTEGER,
			run_end_time_ms INTEGER,
			capture_coverage TEXT,
			availability TEXT,
			acquisition_completeness TEXT,
			retention_loss INTEGER,
			subscription_grouping TEXT,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(protected_identity),
			FOREIGN KEY(entry_identity)
				REFERENCES imported_cell_entry_deletion_receipt(entry_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_cell_deleted_identity_entry " +
			"ON imported_cell_deleted_identity(entry_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS cell_captured_entry_deletion_receipt (
			logical_tracking_id TEXT NOT NULL,
			entry_identity TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			expected_run_count INTEGER NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			run_footprint_set_checksum TEXT NOT NULL,
			retained_from_ms INTEGER,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(logical_tracking_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_cell_captured_entry_deletion_identity " +
			"ON cell_captured_entry_deletion_receipt(entry_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS cell_captured_deleted_run (
			logical_tracking_id TEXT NOT NULL,
			service_run_id TEXT NOT NULL,
			session_segment_id INTEGER NOT NULL,
			scope_identity_digest TEXT NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			generation INTEGER NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(logical_tracking_id, service_run_id),
			FOREIGN KEY(logical_tracking_id)
				REFERENCES cell_captured_entry_deletion_receipt(logical_tracking_id)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_cell_captured_deleted_run_segment " +
			"ON cell_captured_deleted_run(session_segment_id)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_cell_captured_deleted_run_scope " +
			"ON cell_captured_deleted_run(scope_identity_digest)",
	)
}

@Suppress("LongMethod")
private fun createAmbientRadioTables(database: SupportSQLiteDatabase) {
	createAmbientWifiTables(database)
	createAmbientCellTables(database)
}

@Suppress("LongMethod")
private fun createAmbientWifiTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_authority (
			authority_revision INTEGER NOT NULL,
			state TEXT NOT NULL,
			writer_id TEXT NOT NULL,
			writer_version INTEGER NOT NULL,
			writer_owner_generation INTEGER NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			ambient_consent_epoch INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			effective_boot_id TEXT NOT NULL,
			effective_elapsed_realtime_nanos INTEGER NOT NULL,
			effective_wall_time_ms INTEGER NOT NULL,
			rollout_revision INTEGER NOT NULL,
			owner_cas_token TEXT NOT NULL,
			reconciliation_attempt INTEGER NOT NULL,
			demand_id TEXT,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(authority_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_authority_effective " +
			"ON ambient_wifi_authority(" +
			"effective_boot_id, effective_elapsed_realtime_nanos, authority_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_retention_authority (
			scope TEXT NOT NULL,
			approval_revision INTEGER NOT NULL,
			state TEXT NOT NULL,
			opaque_policy_id TEXT NOT NULL,
			source_policy_revision INTEGER,
			ambient_consent_epoch INTEGER,
			collected_data_epoch INTEGER NOT NULL,
			retained_from_ms INTEGER,
			effective_boot_id TEXT NOT NULL,
			effective_elapsed_realtime_nanos INTEGER NOT NULL,
			effective_wall_time_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(scope, approval_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_retention_effective " +
			"ON ambient_wifi_retention_authority(" +
			"scope, effective_boot_id, effective_elapsed_realtime_nanos, approval_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_fact_revision (
			writer_id TEXT NOT NULL,
			writer_version INTEGER NOT NULL,
			writer_owner_generation INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			supersedes_semantic_revision INTEGER,
			mutation_id TEXT NOT NULL,
			fact_kind TEXT NOT NULL,
			aggregate_owner_logical_fact_id TEXT,
			aggregate_owner_semantic_revision INTEGER,
			source_event_id TEXT NOT NULL,
			source_admission_ordinal INTEGER NOT NULL,
			wal_integrity_identity TEXT NOT NULL,
			payload_checksum TEXT NOT NULL,
			source_delivery_identity TEXT NOT NULL,
			source_instance_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			configuration_revision INTEGER,
			physical_configuration_fingerprint TEXT NOT NULL,
			authorization_revision INTEGER NOT NULL,
			authorization_fingerprint TEXT NOT NULL,
			purpose_eligibility_mask INTEGER NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			ambient_consent_epoch INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			clock_domain_id TEXT NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			structural_day_start_time_ms INTEGER NOT NULL,
			structural_day_end_time_ms INTEGER NOT NULL,
			observed_interval_start_nanos INTEGER NOT NULL,
			observed_elapsed_nanos INTEGER NOT NULL,
			received_elapsed_nanos INTEGER NOT NULL,
			coverage_start_time_ms INTEGER NOT NULL,
			observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL,
			coverage_completeness TEXT NOT NULL,
			observation_count INTEGER,
			two_point_four_ghz_count INTEGER,
			five_ghz_count INTEGER,
			six_ghz_count INTEGER,
			other_band_count INTEGER,
			strongest_signal_dbm INTEGER,
			weakest_signal_dbm INTEGER,
			signal_sum_dbm INTEGER,
			quality_flags INTEGER NOT NULL,
			quality_confidence REAL,
			effect_checksum TEXT NOT NULL,
			applied_at_ms INTEGER NOT NULL,
			PRIMARY KEY(writer_id, writer_version, logical_fact_id, semantic_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_ambient_wifi_fact_mutation " +
			"ON ambient_wifi_fact_revision(writer_id, writer_version, mutation_id)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_fact_admission " +
			"ON ambient_wifi_fact_revision(source_admission_ordinal)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_fact_day " +
			"ON ambient_wifi_fact_revision(" +
			"structural_epoch_day, stored_zone_id, observed_wall_time_ms)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_fact_window " +
			"ON ambient_wifi_fact_revision(observed_wall_time_ms, logical_fact_id)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_fact_aggregate_owner " +
			"ON ambient_wifi_fact_revision(" +
			"aggregate_owner_logical_fact_id, aggregate_owner_semantic_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_fact_cursor (
			writer_id TEXT NOT NULL,
			writer_version INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL,
			latest_semantic_revision INTEGER NOT NULL,
			latest_mutation_id TEXT NOT NULL,
			latest_effect_checksum TEXT NOT NULL,
			latest_source_admission_ordinal INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			cursor_revision INTEGER NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(writer_id, writer_version, logical_fact_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_gap (
			gap_id TEXT NOT NULL,
			reason TEXT NOT NULL,
			gap_start_time_ms INTEGER NOT NULL,
			gap_end_time_ms INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			ambient_consent_epoch INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			created_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(gap_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_gap_day " +
			"ON ambient_wifi_gap(structural_epoch_day, stored_zone_id, gap_start_time_ms)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_wifi_gap_window " +
			"ON ambient_wifi_gap(gap_start_time_ms, gap_end_time_ms)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_deletion_marker (
			collected_data_epoch INTEGER NOT NULL,
			deletion_generation INTEGER NOT NULL,
			through_consent_epoch INTEGER NOT NULL,
			reason TEXT NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(collected_data_epoch, deletion_generation)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_wifi_fact (
			archive_id TEXT NOT NULL,
			fact_id TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			supersedes_semantic_revision INTEGER,
			content_checksum TEXT NOT NULL,
			portable_effect_checksum TEXT NOT NULL,
			portable_origin TEXT NOT NULL,
			coverage_start_time_ms INTEGER NOT NULL,
			observed_time_ms INTEGER NOT NULL,
			latest_possible_time_ms INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			coverage_completeness TEXT NOT NULL,
			observation_count INTEGER NOT NULL,
			two_point_four_ghz_count INTEGER NOT NULL,
			five_ghz_count INTEGER NOT NULL,
			six_ghz_count INTEGER NOT NULL,
			other_band_count INTEGER NOT NULL,
			strongest_signal_dbm INTEGER NOT NULL,
			weakest_signal_dbm INTEGER NOT NULL,
			mean_signal_dbm REAL NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			import_deletion_generation INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(archive_id, fact_id, semantic_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_wifi_day " +
			"ON imported_ambient_wifi_fact(structural_epoch_day, stored_zone_id, observed_time_ms)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_wifi_fact_revision " +
			"ON imported_ambient_wifi_fact(fact_id, semantic_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_wifi_gap (
			archive_id TEXT NOT NULL,
			gap_id TEXT NOT NULL,
			content_checksum TEXT NOT NULL,
			portable_effect_checksum TEXT NOT NULL,
			portable_origin TEXT NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			reason TEXT NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(archive_id, gap_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_wifi_gap_window " +
			"ON imported_ambient_wifi_gap(start_time_ms, end_time_ms)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_wifi_gap_identity " +
			"ON imported_ambient_wifi_gap(gap_id)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_wifi_receipt (
			import_job_id TEXT NOT NULL,
			import_entry_key TEXT NOT NULL,
			source_name TEXT NOT NULL,
			archive_id TEXT NOT NULL,
			archive_checksum TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(import_job_id, import_entry_key)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_wifi_receipt_archive " +
			"ON imported_ambient_wifi_receipt(archive_id)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_wifi_tombstone (
			archive_id TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			deletion_generation INTEGER NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(archive_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_wifi_replay_footprint (
			footprint_kind TEXT NOT NULL,
			identity_digest TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			deletion_generation INTEGER NOT NULL,
			recorded_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(footprint_kind, identity_digest, semantic_revision)
		)
		""".trimIndent(),
	)
}

@Suppress("LongMethod")
private fun createAmbientCellTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_authority (
			authority_revision INTEGER NOT NULL,
			state TEXT NOT NULL,
			writer_id TEXT NOT NULL,
			writer_version INTEGER NOT NULL,
			writer_owner_generation INTEGER NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			ambient_consent_epoch INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			effective_boot_id TEXT NOT NULL,
			effective_elapsed_realtime_nanos INTEGER NOT NULL,
			effective_wall_time_ms INTEGER NOT NULL,
			rollout_revision INTEGER NOT NULL,
			owner_cas_token TEXT NOT NULL,
			reconciliation_attempt INTEGER NOT NULL,
			demand_id TEXT,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(authority_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_authority_effective " +
			"ON ambient_cell_authority(" +
			"effective_boot_id, effective_elapsed_realtime_nanos, authority_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_retention_authority (
			scope TEXT NOT NULL,
			approval_revision INTEGER NOT NULL,
			state TEXT NOT NULL,
			opaque_policy_id TEXT NOT NULL,
			source_policy_revision INTEGER,
			ambient_consent_epoch INTEGER,
			collected_data_epoch INTEGER NOT NULL,
			retained_from_ms INTEGER,
			effective_boot_id TEXT NOT NULL,
			effective_elapsed_realtime_nanos INTEGER NOT NULL,
			effective_wall_time_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(scope, approval_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_retention_effective " +
			"ON ambient_cell_retention_authority(" +
			"scope, effective_boot_id, effective_elapsed_realtime_nanos, approval_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_fact_revision (
			writer_id TEXT NOT NULL,
			writer_version INTEGER NOT NULL,
			writer_owner_generation INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			supersedes_semantic_revision INTEGER,
			mutation_id TEXT NOT NULL,
			fact_kind TEXT NOT NULL,
			aggregate_owner_logical_fact_id TEXT,
			aggregate_owner_semantic_revision INTEGER,
			source_event_id TEXT NOT NULL,
			source_admission_ordinal INTEGER NOT NULL,
			wal_integrity_identity TEXT NOT NULL,
			payload_checksum TEXT NOT NULL,
			source_delivery_identity TEXT NOT NULL,
			source_instance_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			configuration_revision INTEGER,
			physical_configuration_fingerprint TEXT NOT NULL,
			authorization_revision INTEGER NOT NULL,
			authorization_fingerprint TEXT NOT NULL,
			purpose_eligibility_mask INTEGER NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			ambient_consent_epoch INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			clock_domain_id TEXT NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			structural_day_start_time_ms INTEGER NOT NULL,
			structural_day_end_time_ms INTEGER NOT NULL,
			observed_interval_start_nanos INTEGER NOT NULL,
			observed_elapsed_nanos INTEGER NOT NULL,
			received_elapsed_nanos INTEGER NOT NULL,
			coverage_start_time_ms INTEGER NOT NULL,
			observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL,
			coverage_completeness TEXT NOT NULL,
			subscription_completeness TEXT NOT NULL,
			observation_count INTEGER,
			registered_observation_count INTEGER,
			gsm_count INTEGER,
			cdma_count INTEGER,
			wcdma_count INTEGER,
			tdscdma_count INTEGER,
			lte_count INTEGER,
			nr_count INTEGER,
			quality_unknown_count INTEGER,
			quality_none_or_unknown_count INTEGER,
			quality_poor_count INTEGER,
			quality_moderate_count INTEGER,
			quality_good_count INTEGER,
			quality_great_count INTEGER,
			quality_flags INTEGER NOT NULL,
			quality_confidence REAL,
			effect_checksum TEXT NOT NULL,
			applied_at_ms INTEGER NOT NULL,
			PRIMARY KEY(writer_id, writer_version, logical_fact_id, semantic_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_ambient_cell_fact_mutation " +
			"ON ambient_cell_fact_revision(writer_id, writer_version, mutation_id)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_fact_admission " +
			"ON ambient_cell_fact_revision(source_admission_ordinal)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_fact_day " +
			"ON ambient_cell_fact_revision(" +
			"structural_epoch_day, stored_zone_id, observed_wall_time_ms)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_fact_window " +
			"ON ambient_cell_fact_revision(observed_wall_time_ms, logical_fact_id)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_fact_aggregate_owner " +
			"ON ambient_cell_fact_revision(" +
			"aggregate_owner_logical_fact_id, aggregate_owner_semantic_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_fact_cursor (
			writer_id TEXT NOT NULL,
			writer_version INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL,
			latest_semantic_revision INTEGER NOT NULL,
			latest_mutation_id TEXT NOT NULL,
			latest_effect_checksum TEXT NOT NULL,
			latest_source_admission_ordinal INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			cursor_revision INTEGER NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(writer_id, writer_version, logical_fact_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_gap (
			gap_id TEXT NOT NULL,
			reason TEXT NOT NULL,
			gap_start_time_ms INTEGER NOT NULL,
			gap_end_time_ms INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			source_policy_revision INTEGER NOT NULL,
			ambient_consent_epoch INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			scope_deletion_generation INTEGER NOT NULL,
			created_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(gap_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_gap_day " +
			"ON ambient_cell_gap(structural_epoch_day, stored_zone_id, gap_start_time_ms)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_cell_gap_window " +
			"ON ambient_cell_gap(gap_start_time_ms, gap_end_time_ms)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_deletion_marker (
			collected_data_epoch INTEGER NOT NULL,
			deletion_generation INTEGER NOT NULL,
			through_consent_epoch INTEGER NOT NULL,
			reason TEXT NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(collected_data_epoch, deletion_generation)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_cell_fact (
			archive_id TEXT NOT NULL,
			fact_id TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			supersedes_semantic_revision INTEGER,
			content_checksum TEXT NOT NULL,
			portable_effect_checksum TEXT NOT NULL,
			portable_origin TEXT NOT NULL,
			coverage_start_time_ms INTEGER NOT NULL,
			observed_time_ms INTEGER NOT NULL,
			latest_possible_time_ms INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			coverage_completeness TEXT NOT NULL,
			subscription_completeness TEXT NOT NULL,
			observation_count INTEGER NOT NULL,
			registered_observation_count INTEGER NOT NULL,
			gsm_count INTEGER NOT NULL,
			cdma_count INTEGER NOT NULL,
			wcdma_count INTEGER NOT NULL,
			tdscdma_count INTEGER NOT NULL,
			lte_count INTEGER NOT NULL,
			nr_count INTEGER NOT NULL,
			quality_unknown_count INTEGER NOT NULL,
			quality_none_or_unknown_count INTEGER NOT NULL,
			quality_poor_count INTEGER NOT NULL,
			quality_moderate_count INTEGER NOT NULL,
			quality_good_count INTEGER NOT NULL,
			quality_great_count INTEGER NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			import_deletion_generation INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(archive_id, fact_id, semantic_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_cell_day " +
			"ON imported_ambient_cell_fact(structural_epoch_day, stored_zone_id, observed_time_ms)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_cell_fact_revision " +
			"ON imported_ambient_cell_fact(fact_id, semantic_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_cell_gap (
			archive_id TEXT NOT NULL,
			gap_id TEXT NOT NULL,
			content_checksum TEXT NOT NULL,
			portable_effect_checksum TEXT NOT NULL,
			portable_origin TEXT NOT NULL,
			start_time_ms INTEGER NOT NULL,
			end_time_ms INTEGER NOT NULL,
			stored_zone_id TEXT NOT NULL,
			structural_epoch_day INTEGER NOT NULL,
			reason TEXT NOT NULL,
			retention_policy_id TEXT NOT NULL,
			retention_approval_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(archive_id, gap_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_cell_gap_window " +
			"ON imported_ambient_cell_gap(start_time_ms, end_time_ms)",
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_ambient_cell_gap_identity " +
			"ON imported_ambient_cell_gap(gap_id)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_cell_receipt (
			import_job_id TEXT NOT NULL,
			import_entry_key TEXT NOT NULL,
			source_name TEXT NOT NULL,
			archive_id TEXT NOT NULL,
			archive_checksum TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			received_at_ms INTEGER NOT NULL,
			PRIMARY KEY(import_job_id, import_entry_key)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_ambient_cell_receipt_archive " +
			"ON imported_ambient_cell_receipt(archive_id)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_ambient_cell_tombstone (
			archive_id TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			deletion_generation INTEGER NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(archive_id)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_cell_replay_footprint (
			footprint_kind TEXT NOT NULL,
			identity_digest TEXT NOT NULL,
			semantic_revision INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			deletion_generation INTEGER NOT NULL,
			recorded_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(footprint_kind, identity_digest, semantic_revision)
		)
		""".trimIndent(),
	)
}

private fun createRuntimeSettlementTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_capture_admission_barrier (
			source_kind INTEGER NOT NULL,
			registration_generation INTEGER NOT NULL,
			source_instance_id TEXT NOT NULL,
			through_authorization_revision INTEGER NOT NULL,
			last_admission_ordinal INTEGER NOT NULL,
			last_source_sequence INTEGER NOT NULL,
			sealed_elapsed_realtime_nanos INTEGER NOT NULL,
			sealed_at_ms INTEGER NOT NULL,
			PRIMARY KEY(source_kind, registration_generation)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS source_run_retirement (
			logical_tracking_id TEXT NOT NULL,
			service_run_id TEXT NOT NULL,
			source_kind INTEGER NOT NULL,
			source_instance_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL,
			action_id TEXT NOT NULL,
			attempt_count INTEGER NOT NULL,
			lease_generation INTEGER NOT NULL,
			cutoff_elapsed_realtime_nanos INTEGER NOT NULL,
			cutoff_wall_time_ms INTEGER NOT NULL,
			state TEXT NOT NULL,
			applied_revision INTEGER,
			callback_entry_barrier_sequence INTEGER,
			last_source_sequence INTEGER,
			last_admission_ordinal INTEGER,
			failed_admission_count INTEGER,
			unresolved_sequence_start INTEGER,
			unresolved_sequence_end INTEGER,
			registration_removal_outcome TEXT,
			provider_flush_outcome TEXT,
			provider_coverage TEXT,
			app_drain_complete INTEGER,
			stop_status TEXT,
			updated_at_ms INTEGER NOT NULL,
			PRIMARY KEY(
				logical_tracking_id,
				service_run_id,
				source_kind,
				source_instance_id,
				registration_generation
			)
		)
		""".trimIndent(),
	)
}

internal fun createLocationPurposeTables(database: SupportSQLiteDatabase) {
	database.execSQL("""
		CREATE TABLE IF NOT EXISTS location_registration_plan (
			registration_generation INTEGER NOT NULL, source_instance_id TEXT NOT NULL,
			configuration_revision INTEGER NOT NULL, authorization_revision INTEGER NOT NULL, physical_configuration_fingerprint TEXT NOT NULL,
			payload_version INTEGER NOT NULL, payload_checksum TEXT NOT NULL, payload BLOB NOT NULL,
			effect_checksum TEXT NOT NULL, PRIMARY KEY(registration_generation, configuration_revision, authorization_revision)
		)
	""".trimIndent())

	database.execSQL("""
		CREATE TABLE IF NOT EXISTS location_accepted_receipt (
			sample_id INTEGER NOT NULL, source_event_id TEXT NOT NULL, source_signal_id TEXT NOT NULL,
			source_admission_ordinal INTEGER NOT NULL, wal_integrity_identity TEXT NOT NULL,
			canonical_content_checksum TEXT NOT NULL, source_instance_id TEXT NOT NULL,
			registration_generation INTEGER NOT NULL, source_sequence INTEGER NOT NULL,
			delivery_identity TEXT NOT NULL, delivery_unit_index INTEGER NOT NULL, delivery_unit_count INTEGER NOT NULL,
			writer_id TEXT NOT NULL, writer_version INTEGER NOT NULL, configuration_revision INTEGER NOT NULL,
			physical_configuration_fingerprint TEXT NOT NULL, authorization_revision INTEGER NOT NULL,
			authorization_fingerprint TEXT NOT NULL, clock_domain_id TEXT NOT NULL, boot_id TEXT NOT NULL,
			observed_elapsed_realtime_nanos INTEGER NOT NULL, received_elapsed_realtime_nanos INTEGER NOT NULL,
			observed_wall_time_ms INTEGER NOT NULL, received_wall_time_ms INTEGER, wall_time_uncertainty_ms INTEGER NOT NULL,
			collected_data_epoch INTEGER NOT NULL, PRIMARY KEY(sample_id),
			FOREIGN KEY(sample_id) REFERENCES location_sample(id) ON UPDATE NO ACTION ON DELETE CASCADE
		)
	""".trimIndent())
	database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_location_receipt_event ON location_accepted_receipt(source_event_id)")
	database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_location_receipt_sequence ON location_accepted_receipt(source_instance_id, source_sequence)")
	database.execSQL("CREATE INDEX IF NOT EXISTS idx_location_receipt_time ON location_accepted_receipt(observed_wall_time_ms, sample_id)")
	database.execSQL("""
		CREATE TABLE IF NOT EXISTS location_sample_purpose_authority (
			sample_id INTEGER NOT NULL, purpose TEXT NOT NULL, authority_id TEXT NOT NULL,
			source_policy_revision INTEGER NOT NULL, consent_epoch INTEGER NOT NULL,
			effective_from_elapsed_nanos INTEGER NOT NULL, effective_until_elapsed_nanos INTEGER NOT NULL,
			logical_tracking_id TEXT, service_run_id TEXT, session_segment_id INTEGER,
			session_manifest_revision INTEGER, lifecycle_lease_generation INTEGER,
			ambient_intent_id TEXT, retention_policy_id TEXT, retention_approval_revision INTEGER,
			scope_deletion_generation INTEGER NOT NULL, zone_id TEXT,
			PRIMARY KEY(sample_id, purpose, authority_id),
			FOREIGN KEY(sample_id) REFERENCES location_accepted_receipt(sample_id) ON UPDATE NO ACTION ON DELETE CASCADE
		)
	""".trimIndent())
	database.execSQL("CREATE INDEX IF NOT EXISTS idx_location_purpose_authority ON location_sample_purpose_authority(purpose, authority_id, sample_id)")
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS ambient_location_retention_authority (
			scope TEXT NOT NULL,
			approval_revision INTEGER NOT NULL,
			state TEXT NOT NULL,
			opaque_policy_id TEXT NOT NULL,
			source_policy_revision INTEGER,
			ambient_consent_epoch INTEGER,
			collected_data_epoch INTEGER NOT NULL,
			retained_from_ms INTEGER,
			effective_boot_id TEXT NOT NULL,
			effective_elapsed_realtime_nanos INTEGER NOT NULL,
			effective_wall_time_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(scope, approval_revision)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_ambient_location_retention_effective " +
			"ON ambient_location_retention_authority(" +
			"scope, effective_boot_id, effective_elapsed_realtime_nanos, approval_revision)",
	)
}
