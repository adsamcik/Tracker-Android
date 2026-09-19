package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

/** Manual v27->v28 DDL for imported-only portable count-domain evidence. */
internal fun createImportedPortableStepsCountDomainTables(database: SupportSQLiteDatabase) {
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_graph (
			graph_identity TEXT NOT NULL,
			content_checksum TEXT NOT NULL,
			source_format TEXT NOT NULL,
			source_schema_version INTEGER NOT NULL,
			receipt_count INTEGER NOT NULL,
			owner_revision_count INTEGER NOT NULL,
			completeness_marker_count INTEGER NOT NULL,
			root_count INTEGER NOT NULL,
			PRIMARY KEY(graph_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_receipt (
			graph_identity TEXT NOT NULL,
			receipt_identity TEXT NOT NULL,
			domain_identity TEXT NOT NULL,
			owner_kind TEXT NOT NULL,
			scope_identity TEXT NOT NULL,
			owner_identity TEXT NOT NULL,
			owner_revision INTEGER NOT NULL,
			registration_generation INTEGER NOT NULL,
			source_collected_data_epoch INTEGER NOT NULL,
			authority_revision INTEGER NOT NULL,
			authority_fingerprint TEXT NOT NULL,
			coverage TEXT NOT NULL,
			coverage_version INTEGER NOT NULL,
			count_domain_version INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			completeness_evidence_checksum TEXT,
			PRIMARY KEY(graph_identity, receipt_identity),
			FOREIGN KEY(graph_identity) REFERENCES imported_steps_count_domain_graph(graph_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_receipt_graph " +
			"ON imported_steps_count_domain_receipt(graph_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_receipt_identity " +
			"ON imported_steps_count_domain_receipt(receipt_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_owner_revision (
			graph_identity TEXT NOT NULL,
			owner_kind TEXT NOT NULL,
			scope_identity TEXT NOT NULL,
			owner_identity TEXT NOT NULL,
			owner_revision INTEGER NOT NULL,
			operation TEXT NOT NULL,
			receipt_identity TEXT,
			owner_effect_checksum TEXT NOT NULL,
			source_linked_at_ms INTEGER NOT NULL,
			PRIMARY KEY(graph_identity, owner_kind, owner_identity, owner_revision),
			FOREIGN KEY(graph_identity) REFERENCES imported_steps_count_domain_graph(graph_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_owner_graph " +
			"ON imported_steps_count_domain_owner_revision(graph_identity, owner_kind, owner_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_owner_identity " +
			"ON imported_steps_count_domain_owner_revision(owner_kind, owner_identity, owner_revision)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_owner_receipt " +
			"ON imported_steps_count_domain_owner_revision(receipt_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_completeness (
			graph_identity TEXT NOT NULL,
			owner_identity TEXT NOT NULL,
			owner_revision INTEGER NOT NULL,
			terminal_state TEXT NOT NULL,
			last_admission_ordinal INTEGER,
			last_source_sequence INTEGER,
			provider_flush_outcome TEXT NOT NULL,
			registration_removal_outcome TEXT NOT NULL,
			registration_timeline_checksum TEXT NOT NULL,
			evidence_checksum TEXT NOT NULL,
			PRIMARY KEY(graph_identity, owner_identity, owner_revision),
			FOREIGN KEY(graph_identity) REFERENCES imported_steps_count_domain_graph(graph_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_completeness_graph " +
			"ON imported_steps_count_domain_completeness(graph_identity, owner_identity, owner_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_root (
			graph_identity TEXT NOT NULL,
			container_identity TEXT NOT NULL,
			product_identity TEXT NOT NULL,
			owner_kind TEXT NOT NULL,
			owner_identity TEXT NOT NULL,
			owner_revision INTEGER NOT NULL,
			PRIMARY KEY(graph_identity, container_identity, product_identity, owner_kind, owner_identity),
			FOREIGN KEY(graph_identity) REFERENCES imported_steps_count_domain_graph(graph_identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_root_graph " +
			"ON imported_steps_count_domain_root(graph_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_root_product " +
			"ON imported_steps_count_domain_root(product_identity, owner_kind)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_root_owner " +
			"ON imported_steps_count_domain_root(owner_kind, owner_identity, owner_revision)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_binding (
			product_kind TEXT NOT NULL,
			product_identity TEXT NOT NULL,
			product_revision INTEGER NOT NULL,
			graph_identity TEXT NOT NULL,
			source_schema_version INTEGER NOT NULL,
			PRIMARY KEY(product_kind, product_identity, product_revision),
			FOREIGN KEY(graph_identity) REFERENCES imported_steps_count_domain_graph(graph_identity)
				ON UPDATE NO ACTION ON DELETE RESTRICT
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_binding_graph " +
			"ON imported_steps_count_domain_binding(graph_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_file_receipt (
			import_job_id TEXT NOT NULL,
			entry_key TEXT NOT NULL,
			receipt_identity TEXT NOT NULL,
			source_name TEXT NOT NULL,
			received_at_ms INTEGER NOT NULL,
			archive_content_checksum TEXT NOT NULL,
			entry_ordinal INTEGER NOT NULL,
			entry_identity TEXT NOT NULL,
			graph_identity TEXT NOT NULL,
			PRIMARY KEY(import_job_id, entry_key),
			FOREIGN KEY(entry_identity) REFERENCES imported_steps_entry(identity)
				ON UPDATE NO ACTION ON DELETE CASCADE
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_steps_file_receipt_id " +
			"ON imported_steps_file_receipt(receipt_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_file_receipt_entry " +
			"ON imported_steps_file_receipt(entry_identity)",
	)
	database.execSQL(
		"""
		CREATE TABLE IF NOT EXISTS imported_steps_count_domain_owner_fence (
			owner_kind TEXT NOT NULL,
			owner_identity TEXT NOT NULL,
			scope_identity TEXT NOT NULL,
			latest_source_revision INTEGER NOT NULL,
			latest_owner_effect_checksum TEXT NOT NULL,
			product_kind TEXT NOT NULL,
			product_identity TEXT NOT NULL,
			graph_identity TEXT NOT NULL,
			fence_kind TEXT NOT NULL,
			collected_data_epoch INTEGER NOT NULL,
			fenced_at_ms INTEGER NOT NULL,
			effect_checksum TEXT NOT NULL,
			PRIMARY KEY(owner_kind, owner_identity)
		)
		""".trimIndent(),
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_fence_product " +
			"ON imported_steps_count_domain_owner_fence(product_kind, product_identity)",
	)
	database.execSQL(
		"CREATE INDEX IF NOT EXISTS idx_imported_steps_count_fence_graph " +
			"ON imported_steps_count_domain_owner_fence(graph_identity)",
	)
}
