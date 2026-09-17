package com.adsamcik.tracker.shared.base.database

/**
 * Exact additive DDL handed to the serialized AppDatabase/schema owner.
 *
 * This source slice deliberately does not mutate AppDatabase or MIGRATION_27_28. Tests may install
 * these statements in an isolated database to exercise the producer and query contracts. The
 * serialized owner must register all four entities and the DAO, execute these statements after
 * ambient_steps_fact_revision exists for both fresh and migrated v28 databases, and call
 * clearStepsCountDomainEvidenceInTransaction from the existing collected-data clear transaction.
 */
object StepsCountDomainSchema {
	const val RECEIPT_TABLE = "steps_count_domain_receipt"
	const val OWNER_TABLE = "steps_count_domain_owner_revision"
	const val COMPLETENESS_MARKER_TABLE = "steps_count_domain_completeness_marker"
	const val SCHEMA_MARKER_TABLE = "steps_count_domain_schema_marker"
	const val AMBIENT_NO_RESURRECTION_TRIGGER =
		"trg_steps_count_domain_ambient_no_resurrection"
	const val AMBIENT_RETRACTION_TRIGGER =
		"trg_steps_count_domain_ambient_retraction"
	const val TERMINAL_OWNER_TRIGGER =
		"trg_steps_count_domain_owner_terminal"

	val createStatements: List<String> = listOf(
		"""
		CREATE TABLE IF NOT EXISTS `$RECEIPT_TABLE` (
			`receipt_identity` TEXT NOT NULL,
			`domain_identity` TEXT NOT NULL,
			`owner_kind` TEXT NOT NULL,
			`scope_identity` TEXT NOT NULL,
			`owner_identity` TEXT NOT NULL,
			`owner_revision` INTEGER NOT NULL,
			`registration_generation` INTEGER NOT NULL,
			`collected_data_epoch` INTEGER NOT NULL,
			`authority_revision` INTEGER NOT NULL,
			`authority_fingerprint` TEXT NOT NULL,
			`coverage_kind` TEXT NOT NULL,
			`coverage_version` INTEGER NOT NULL,
			`count_domain_version` INTEGER NOT NULL,
			`effect_checksum` TEXT NOT NULL,
			`completion_evidence_checksum` TEXT,
			PRIMARY KEY(`receipt_identity`)
		)
		""".trimIndent(),
		"""
		CREATE UNIQUE INDEX IF NOT EXISTS `idx_steps_count_domain_receipt_owner`
		ON `$RECEIPT_TABLE` (`owner_kind`, `owner_identity`, `owner_revision`)
		""".trimIndent(),
		"""
		CREATE INDEX IF NOT EXISTS `idx_steps_count_domain_receipt_compatibility`
		ON `$RECEIPT_TABLE` (`domain_identity`, `collected_data_epoch`, `count_domain_version`)
		""".trimIndent(),
		"""
		CREATE TABLE IF NOT EXISTS `$OWNER_TABLE` (
			`owner_kind` TEXT NOT NULL,
			`scope_identity` TEXT NOT NULL,
			`owner_identity` TEXT NOT NULL,
			`owner_revision` INTEGER NOT NULL,
			`operation` TEXT NOT NULL,
			`receipt_identity` TEXT,
			`owner_effect_checksum` TEXT NOT NULL,
			`linked_at_ms` INTEGER NOT NULL,
			PRIMARY KEY(`owner_kind`, `owner_identity`, `owner_revision`),
			FOREIGN KEY(`receipt_identity`) REFERENCES `$RECEIPT_TABLE`(`receipt_identity`)
				ON UPDATE NO ACTION ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
		)
		""".trimIndent(),
		"""
		CREATE INDEX IF NOT EXISTS `idx_steps_count_domain_owner_scope`
		ON `$OWNER_TABLE` (`owner_kind`, `scope_identity`, `owner_identity`, `owner_revision`)
		""".trimIndent(),
		"""
		CREATE INDEX IF NOT EXISTS `idx_steps_count_domain_owner_receipt`
		ON `$OWNER_TABLE` (`receipt_identity`)
		""".trimIndent(),
		"""
		CREATE INDEX IF NOT EXISTS `idx_steps_count_domain_owner_terminal_age`
		ON `$OWNER_TABLE` (`operation`, `linked_at_ms`, `owner_kind`, `owner_identity`)
		""".trimIndent(),
		"""
		CREATE TABLE IF NOT EXISTS `$COMPLETENESS_MARKER_TABLE` (
			`owner_kind` TEXT NOT NULL,
			`owner_identity` TEXT NOT NULL,
			`owner_revision` INTEGER NOT NULL,
			`terminal_state` TEXT NOT NULL,
			`last_admission_ordinal` INTEGER,
			`last_source_sequence` INTEGER,
			`provider_flush_outcome` TEXT NOT NULL,
			`registration_removal_outcome` TEXT NOT NULL,
			`registration_timeline_checksum` TEXT NOT NULL,
			`evidence_checksum` TEXT NOT NULL,
			PRIMARY KEY(`owner_identity`, `owner_revision`),
			FOREIGN KEY(`owner_kind`, `owner_identity`, `owner_revision`)
				REFERENCES `$OWNER_TABLE`(`owner_kind`, `owner_identity`, `owner_revision`)
				ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
		)
		""".trimIndent(),
		"""
		CREATE UNIQUE INDEX IF NOT EXISTS `idx_steps_count_domain_completeness_owner`
		ON `$COMPLETENESS_MARKER_TABLE` (`owner_kind`, `owner_identity`, `owner_revision`)
		""".trimIndent(),
		"""
		CREATE TABLE IF NOT EXISTS `$SCHEMA_MARKER_TABLE` (
			`id` INTEGER NOT NULL,
			`contract_version` INTEGER NOT NULL,
			`token_semantics` TEXT NOT NULL,
			`terminal_unproven` INTEGER NOT NULL,
			PRIMARY KEY(`id`)
		)
		""".trimIndent(),
		"""
		INSERT OR IGNORE INTO `$SCHEMA_MARKER_TABLE` (
			`id`, `contract_version`, `token_semantics`, `terminal_unproven`
		) VALUES (1, 2, 'PROVIDER_COUNTER_EPOCH_V1', 1)
		""".trimIndent(),
		"""
		CREATE TRIGGER IF NOT EXISTS `$TERMINAL_OWNER_TRIGGER`
		BEFORE INSERT ON `$OWNER_TABLE`
		WHEN EXISTS (
			SELECT 1
			FROM `$OWNER_TABLE` AS terminal
			WHERE terminal.`owner_kind` = NEW.`owner_kind`
			  AND terminal.`owner_identity` = NEW.`owner_identity`
			  AND (
				(
				  terminal.`owner_revision` = NEW.`owner_revision` AND (
					terminal.`scope_identity` != NEW.`scope_identity` OR
					terminal.`operation` != NEW.`operation` OR
					COALESCE(terminal.`receipt_identity`, '') !=
						COALESCE(NEW.`receipt_identity`, '') OR
					terminal.`owner_effect_checksum` != NEW.`owner_effect_checksum` OR
					terminal.`linked_at_ms` != NEW.`linked_at_ms`
				  )
				) OR
				(
				  terminal.`owner_revision` < NEW.`owner_revision` AND (
					terminal.`operation` = 'RETRACT' OR
					(terminal.`operation` = 'UNPROVEN' AND NEW.`operation` != 'RETRACT')
				  )
				)
			  )
		)
		BEGIN
			SELECT RAISE(ABORT, 'Steps count-domain owner is terminal');
		END
		""".trimIndent(),
		"""
		CREATE TRIGGER IF NOT EXISTS `$AMBIENT_NO_RESURRECTION_TRIGGER`
		BEFORE INSERT ON `ambient_steps_fact_revision`
		WHEN NEW.`operation` = 'UPSERT' AND EXISTS (
			SELECT 1
			FROM `$OWNER_TABLE` AS owner
			WHERE owner.`owner_kind` = 'AMBIENT_FACT'
			  AND owner.`owner_identity` = NEW.`logical_fact_id`
			  AND owner.`operation` = 'RETRACT'
		)
		BEGIN
			SELECT RAISE(ABORT, 'Ambient Steps count-domain owner is terminally retracted');
		END
		""".trimIndent(),
		"""
		CREATE TRIGGER IF NOT EXISTS `$AMBIENT_RETRACTION_TRIGGER`
		AFTER INSERT ON `ambient_steps_fact_revision`
		WHEN NEW.`operation` = 'RETRACT'
		BEGIN
			INSERT OR ABORT INTO `$OWNER_TABLE` (
				`owner_kind`,
				`scope_identity`,
				`owner_identity`,
				`owner_revision`,
				`operation`,
				`receipt_identity`,
				`owner_effect_checksum`,
				`linked_at_ms`
			) VALUES (
				'AMBIENT_FACT',
				NEW.`logical_fact_id`,
				NEW.`logical_fact_id`,
				NEW.`semantic_revision`,
				'RETRACT',
				NULL,
				NEW.`effect_checksum`,
				NEW.`applied_at_ms`
			);
		END
		""".trimIndent(),
	)
}
