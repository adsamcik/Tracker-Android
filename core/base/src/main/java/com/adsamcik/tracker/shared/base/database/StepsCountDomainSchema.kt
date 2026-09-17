package com.adsamcik.tracker.shared.base.database

/**
 * Exact additive DDL handed to the serialized AppDatabase/schema owner.
 *
 * This source slice deliberately does not mutate AppDatabase or MIGRATION_27_28. Tests may install
 * these statements in an isolated database to exercise the producer and query contracts.
 */
object StepsCountDomainSchema {
	const val RECEIPT_TABLE = "steps_count_domain_receipt"
	const val OWNER_TABLE = "steps_count_domain_owner_revision"
	const val AMBIENT_NO_RESURRECTION_TRIGGER =
		"trg_steps_count_domain_ambient_no_resurrection"
	const val AMBIENT_RETRACTION_TRIGGER =
		"trg_steps_count_domain_ambient_retraction"

	val createStatements: List<String> = listOf(
		"""
		CREATE TABLE IF NOT EXISTS `$RECEIPT_TABLE` (
			`receipt_identity` TEXT NOT NULL,
			`domain_identity` TEXT NOT NULL,
			`provider_domain_identity` TEXT NOT NULL,
			`source_instance_identity` TEXT NOT NULL,
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
