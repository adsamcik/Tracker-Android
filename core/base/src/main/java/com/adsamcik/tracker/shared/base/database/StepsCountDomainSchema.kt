package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainSchemaMarkerEntity

sealed interface StepsCountDomainSchemaState {
	data object Absent : StepsCountDomainSchemaState
	data object ValidV2 : StepsCountDomainSchemaState
	data object Incompatible : StepsCountDomainSchemaState
}

/**
 * Exact additive DDL handed to the serialized AppDatabase/schema owner.
 *
 * This source slice deliberately does not mutate AppDatabase or MIGRATION_27_28. Tests may install
 * the schema in an isolated database to exercise the producer and query contracts. The serialized
 * owner must register all four entities and the DAO, call [installIfAbsent] after
 * ambient_steps_fact_revision exists for both fresh and migrated v28 databases, and call
 * clearStepsCountDomainEvidenceInCurrentTransaction from the existing collected-data clear
 * transaction. Partial or markerless e500-era objects are incompatible and are never repaired or
 * activated in place. Counter-epoch generation is persisted in Steps source payload/checkpoint
 * version 7 and does not add another Room column here.
 */
@Suppress("LargeClass", "TooManyFunctions")
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

	private val creationStatements: List<String> = listOf(
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
					(
					  terminal.`operation` = 'UNPROVEN' AND
					  (
						NEW.`operation` NOT IN ('RETRACT', 'UNPROVEN') OR
						(
						  NEW.`operation` = 'UNPROVEN' AND (
							terminal.`owner_kind` != 'AMBIENT_FACT' OR
							terminal.`scope_identity` != NEW.`scope_identity` OR
							(
							  NOT EXISTS (
								SELECT 1
								FROM `$OWNER_TABLE` AS exact
								WHERE exact.`owner_kind` = NEW.`owner_kind`
								  AND exact.`owner_identity` = NEW.`owner_identity`
								  AND exact.`owner_revision` = NEW.`owner_revision`
							  ) AND
							  NEW.`owner_revision` != (
								SELECT MAX(previous.`owner_revision`) + 1
								FROM `$OWNER_TABLE` AS previous
								WHERE previous.`owner_kind` = NEW.`owner_kind`
								  AND previous.`owner_identity` = NEW.`owner_identity`
							  )
							)
						  )
						)
					  )
					)
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

	private const val INSERT_MARKER_STATEMENT =
		"INSERT INTO `$SCHEMA_MARKER_TABLE` " +
			"(`id`, `contract_version`, `token_semantics`, `terminal_unproven`) " +
			"VALUES (1, 2, 'PROVIDER_COUNTER_EPOCH_V1', 1)"

	/**
	 * Installs v2 only into a completely absent namespace.
	 *
	 * The marker is written last, after the tables, keys, indexes, and triggers have been inspected.
	 * Any partial, markerless, legacy, or otherwise inexact namespace remains
	 * [StepsCountDomainSchemaState.Incompatible].
	 */
	fun installIfAbsent(database: SupportSQLiteDatabase): StepsCountDomainSchemaState =
		when (inspect(database)) {
			StepsCountDomainSchemaState.Absent -> try {
				creationStatements.forEach { database.execSQL(it) }
				if (!database.hasExactV2Structure(requireMarker = false)) {
					StepsCountDomainSchemaState.Incompatible
				} else {
					database.execSQL(INSERT_MARKER_STATEMENT)
					inspect(database)
				}
			} catch (_: SQLiteException) {
				StepsCountDomainSchemaState.Incompatible
			} catch (_: IllegalArgumentException) {
				StepsCountDomainSchemaState.Incompatible
			} catch (_: IllegalStateException) {
				StepsCountDomainSchemaState.Incompatible
			}
			StepsCountDomainSchemaState.ValidV2 -> StepsCountDomainSchemaState.ValidV2
			StepsCountDomainSchemaState.Incompatible -> StepsCountDomainSchemaState.Incompatible
		}

	/**
	 * Distinguishes a truly absent schema from one that contains untrusted or stale evidence.
	 */
	fun inspect(database: SupportSQLiteDatabase): StepsCountDomainSchemaState = try {
		when {
			database.namedObjectCount() == 0 -> StepsCountDomainSchemaState.Absent
			database.hasExactV2Structure(requireMarker = true) ->
				StepsCountDomainSchemaState.ValidV2
			else -> StepsCountDomainSchemaState.Incompatible
		}
	} catch (_: SQLiteException) {
		StepsCountDomainSchemaState.Incompatible
	} catch (_: IllegalArgumentException) {
		StepsCountDomainSchemaState.Incompatible
	} catch (_: IllegalStateException) {
		StepsCountDomainSchemaState.Incompatible
	}

	private fun SupportSQLiteDatabase.namedObjectCount(): Int {
		return query(
			"SELECT COUNT(*) FROM sqlite_master " +
				"WHERE lower(name) LIKE 'steps_count_domain_%' " +
				"OR lower(name) LIKE 'idx_steps_count_domain_%' " +
				"OR lower(name) LIKE 'trg_steps_count_domain_%'",
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getInt(0)
		}
	}

	private fun SupportSQLiteDatabase.hasExactV2Structure(requireMarker: Boolean): Boolean {
		if (namedObjectCount() != EXPECTED_NAMED_OBJECT_COUNT) return false
		if (EXPECTED_TABLES.any { (table, expected) -> tableColumns(table) != expected }) return false
		if (foreignKeys(OWNER_TABLE) != EXPECTED_OWNER_FOREIGN_KEYS) return false
		if (foreignKeys(COMPLETENESS_MARKER_TABLE) != EXPECTED_COMPLETENESS_FOREIGN_KEYS) return false
		if (!tableSql(OWNER_TABLE).hasDeferredForeignKey()) return false
		if (!tableSql(COMPLETENESS_MARKER_TABLE).hasDeferredForeignKey()) return false

		val indexes = expectedIndexNames()
		if (indexes != EXPECTED_INDEXES.keys) return false
		if (EXPECTED_INDEXES.any { (name, expected) -> index(name) != expected }) return false
		if (EXPECTED_TRIGGERS.any { (name, expectedSql) ->
				triggerSql(name)?.normalizedSql() != expectedSql.normalizedSql()
			}
		) return false

		val markerRows = query(
			"SELECT id, contract_version, token_semantics, terminal_unproven " +
				"FROM `$SCHEMA_MARKER_TABLE` ORDER BY id",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						SchemaMarker(
							id = cursor.getInt(0),
							contractVersion = cursor.getInt(1),
							tokenSemantics = cursor.getString(2),
							terminalUnproven = cursor.getInt(3),
						),
					)
				}
			}
		}
		return if (requireMarker) {
			markerRows == listOf(EXPECTED_MARKER)
		} else {
			markerRows.isEmpty()
		}
	}

	private fun SupportSQLiteDatabase.tableColumns(table: String): List<SchemaColumn> =
		query("PRAGMA table_info(`$table`)").use { cursor ->
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			val typeIndex = cursor.getColumnIndexOrThrow("type")
			val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
			val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
			val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
			buildList {
				while (cursor.moveToNext()) {
					add(
						SchemaColumn(
							name = cursor.getString(nameIndex),
							type = cursor.getString(typeIndex).uppercase(),
							notNull = cursor.getInt(notNullIndex) == 1,
							primaryKeyPosition = cursor.getInt(primaryKeyIndex),
							defaultValue = if (cursor.isNull(defaultValueIndex)) {
								null
							} else {
								cursor.getString(defaultValueIndex)
							},
						),
					)
				}
			}
		}

	private fun SupportSQLiteDatabase.foreignKeys(table: String): List<SchemaForeignKeyColumn> =
		query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
			val idIndex = cursor.getColumnIndexOrThrow("id")
			val sequenceIndex = cursor.getColumnIndexOrThrow("seq")
			val targetTableIndex = cursor.getColumnIndexOrThrow("table")
			val fromIndex = cursor.getColumnIndexOrThrow("from")
			val toIndex = cursor.getColumnIndexOrThrow("to")
			val onUpdateIndex = cursor.getColumnIndexOrThrow("on_update")
			val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
			val matchIndex = cursor.getColumnIndexOrThrow("match")
			buildList {
				while (cursor.moveToNext()) {
					add(
						SchemaForeignKeyColumn(
							id = cursor.getInt(idIndex),
							sequence = cursor.getInt(sequenceIndex),
							targetTable = cursor.getString(targetTableIndex),
							from = cursor.getString(fromIndex),
							to = cursor.getString(toIndex),
							onUpdate = cursor.getString(onUpdateIndex).uppercase(),
							onDelete = cursor.getString(onDeleteIndex).uppercase(),
							match = cursor.getString(matchIndex).uppercase(),
						),
					)
				}
			}.sortedWith(
				compareBy(
					SchemaForeignKeyColumn::id,
					SchemaForeignKeyColumn::sequence,
				),
			)
		}

	private fun SupportSQLiteDatabase.expectedIndexNames(): Set<String> = buildSet {
		EXPECTED_TABLES.keys.forEach { table ->
			query("PRAGMA index_list(`$table`)").use { cursor ->
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) {
					cursor.getString(nameIndex)
						.takeIf { it.startsWith("idx_steps_count_domain_") }
						?.let(::add)
				}
			}
		}
	}

	private fun SupportSQLiteDatabase.index(name: String): SchemaIndex? {
		val metadata = query(
			"SELECT tbl_name, sql FROM sqlite_master WHERE type = 'index' AND name = ? LIMIT 1",
			arrayOf(name),
		).use { cursor ->
			if (!cursor.moveToFirst()) return null
			cursor.getString(0) to cursor.getString(1)
		}
		val unique = metadata.second.normalizedSql().startsWith("CREATE UNIQUE INDEX ")
		val partial = query("PRAGMA index_list(`${metadata.first}`)").use { cursor ->
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			val partialIndex = cursor.getColumnIndexOrThrow("partial")
			var result: Boolean? = null
			while (cursor.moveToNext()) {
				if (cursor.getString(nameIndex) == name) {
					result = cursor.getInt(partialIndex) == 1
					break
				}
			}
			result ?: return null
		}
		val columns = query("PRAGMA index_info(`$name`)").use { cursor ->
			val sequenceIndex = cursor.getColumnIndexOrThrow("seqno")
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			buildList {
				while (cursor.moveToNext()) {
					add(cursor.getInt(sequenceIndex) to cursor.getString(nameIndex))
				}
			}.sortedBy { it.first }.map { it.second }
		}
		return SchemaIndex(metadata.first, unique, partial, columns)
	}

	private fun SupportSQLiteDatabase.tableSql(name: String): String =
		query(
			"SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
			arrayOf(name),
		).use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else ""
		}

	private fun SupportSQLiteDatabase.triggerSql(name: String): String? =
		query(
			"SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ? LIMIT 1",
			arrayOf(name),
		).use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		}

	private fun String.hasDeferredForeignKey(): Boolean =
		normalizedSql().contains("DEFERRABLE INITIALLY DEFERRED")

	private fun String.normalizedSql(): String = replace("`", "")
		.replace("\"", "")
		.replace(Regex("\\s+"), " ")
		.trim()
		.uppercase()
		.replace(" IF NOT EXISTS ", " ")

	private data class SchemaColumn(
		val name: String,
		val type: String,
		val notNull: Boolean,
		val primaryKeyPosition: Int,
		val defaultValue: String? = null,
	)

	private data class SchemaForeignKeyColumn(
		val id: Int,
		val sequence: Int,
		val targetTable: String,
		val from: String,
		val to: String,
		val onUpdate: String,
		val onDelete: String,
		val match: String,
	)

	private data class SchemaIndex(
		val table: String,
		val unique: Boolean,
		val partial: Boolean,
		val columns: List<String>,
	)

	private data class SchemaMarker(
		val id: Int,
		val contractVersion: Int,
		val tokenSemantics: String,
		val terminalUnproven: Int,
	)

	private val EXPECTED_TABLES = linkedMapOf(
		RECEIPT_TABLE to listOf(
			SchemaColumn("receipt_identity", "TEXT", true, 1),
			SchemaColumn("domain_identity", "TEXT", true, 0),
			SchemaColumn("owner_kind", "TEXT", true, 0),
			SchemaColumn("scope_identity", "TEXT", true, 0),
			SchemaColumn("owner_identity", "TEXT", true, 0),
			SchemaColumn("owner_revision", "INTEGER", true, 0),
			SchemaColumn("registration_generation", "INTEGER", true, 0),
			SchemaColumn("collected_data_epoch", "INTEGER", true, 0),
			SchemaColumn("authority_revision", "INTEGER", true, 0),
			SchemaColumn("authority_fingerprint", "TEXT", true, 0),
			SchemaColumn("coverage_kind", "TEXT", true, 0),
			SchemaColumn("coverage_version", "INTEGER", true, 0),
			SchemaColumn("count_domain_version", "INTEGER", true, 0),
			SchemaColumn("effect_checksum", "TEXT", true, 0),
			SchemaColumn("completion_evidence_checksum", "TEXT", false, 0),
		),
		OWNER_TABLE to listOf(
			SchemaColumn("owner_kind", "TEXT", true, 1),
			SchemaColumn("scope_identity", "TEXT", true, 0),
			SchemaColumn("owner_identity", "TEXT", true, 2),
			SchemaColumn("owner_revision", "INTEGER", true, 3),
			SchemaColumn("operation", "TEXT", true, 0),
			SchemaColumn("receipt_identity", "TEXT", false, 0),
			SchemaColumn("owner_effect_checksum", "TEXT", true, 0),
			SchemaColumn("linked_at_ms", "INTEGER", true, 0),
		),
		COMPLETENESS_MARKER_TABLE to listOf(
			SchemaColumn("owner_kind", "TEXT", true, 0),
			SchemaColumn("owner_identity", "TEXT", true, 1),
			SchemaColumn("owner_revision", "INTEGER", true, 2),
			SchemaColumn("terminal_state", "TEXT", true, 0),
			SchemaColumn("last_admission_ordinal", "INTEGER", false, 0),
			SchemaColumn("last_source_sequence", "INTEGER", false, 0),
			SchemaColumn("provider_flush_outcome", "TEXT", true, 0),
			SchemaColumn("registration_removal_outcome", "TEXT", true, 0),
			SchemaColumn("registration_timeline_checksum", "TEXT", true, 0),
			SchemaColumn("evidence_checksum", "TEXT", true, 0),
		),
		SCHEMA_MARKER_TABLE to listOf(
			SchemaColumn("id", "INTEGER", true, 1),
			SchemaColumn("contract_version", "INTEGER", true, 0),
			SchemaColumn("token_semantics", "TEXT", true, 0),
			SchemaColumn("terminal_unproven", "INTEGER", true, 0),
		),
	)

	private val EXPECTED_INDEXES = linkedMapOf(
		"idx_steps_count_domain_receipt_owner" to SchemaIndex(
			RECEIPT_TABLE,
			true,
			false,
			listOf("owner_kind", "owner_identity", "owner_revision"),
		),
		"idx_steps_count_domain_receipt_compatibility" to SchemaIndex(
			RECEIPT_TABLE,
			false,
			false,
			listOf("domain_identity", "collected_data_epoch", "count_domain_version"),
		),
		"idx_steps_count_domain_owner_scope" to SchemaIndex(
			OWNER_TABLE,
			false,
			false,
			listOf("owner_kind", "scope_identity", "owner_identity", "owner_revision"),
		),
		"idx_steps_count_domain_owner_receipt" to SchemaIndex(
			OWNER_TABLE,
			false,
			false,
			listOf("receipt_identity"),
		),
		"idx_steps_count_domain_owner_terminal_age" to SchemaIndex(
			OWNER_TABLE,
			false,
			false,
			listOf("operation", "linked_at_ms", "owner_kind", "owner_identity"),
		),
		"idx_steps_count_domain_completeness_owner" to SchemaIndex(
			COMPLETENESS_MARKER_TABLE,
			true,
			false,
			listOf("owner_kind", "owner_identity", "owner_revision"),
		),
	)

	private val EXPECTED_OWNER_FOREIGN_KEYS = listOf(
		SchemaForeignKeyColumn(
			id = 0,
			sequence = 0,
			targetTable = RECEIPT_TABLE,
			from = "receipt_identity",
			to = "receipt_identity",
			onUpdate = "NO ACTION",
			onDelete = "RESTRICT",
			match = "NONE",
		),
	)

	private val EXPECTED_COMPLETENESS_FOREIGN_KEYS = listOf(
		SchemaForeignKeyColumn(
			id = 0,
			sequence = 0,
			targetTable = OWNER_TABLE,
			from = "owner_kind",
			to = "owner_kind",
			onUpdate = "NO ACTION",
			onDelete = "CASCADE",
			match = "NONE",
		),
		SchemaForeignKeyColumn(
			id = 0,
			sequence = 1,
			targetTable = OWNER_TABLE,
			from = "owner_identity",
			to = "owner_identity",
			onUpdate = "NO ACTION",
			onDelete = "CASCADE",
			match = "NONE",
		),
		SchemaForeignKeyColumn(
			id = 0,
			sequence = 2,
			targetTable = OWNER_TABLE,
			from = "owner_revision",
			to = "owner_revision",
			onUpdate = "NO ACTION",
			onDelete = "CASCADE",
			match = "NONE",
		),
	)

	private val EXPECTED_TRIGGERS: Map<String, String> = creationStatements
		.filter { statement -> statement.trimStart().startsWith("CREATE TRIGGER") }
		.associateBy { statement ->
			when {
				statement.contains(TERMINAL_OWNER_TRIGGER) -> TERMINAL_OWNER_TRIGGER
				statement.contains(AMBIENT_NO_RESURRECTION_TRIGGER) ->
					AMBIENT_NO_RESURRECTION_TRIGGER
				else -> AMBIENT_RETRACTION_TRIGGER
			}
		}

	private val EXPECTED_MARKER = SchemaMarker(
		id = StepsCountDomainSchemaMarkerEntity.REQUIRED_ID,
		contractVersion = StepsCountDomainSchemaMarkerEntity.REQUIRED_CONTRACT_VERSION,
		tokenSemantics = StepsCountDomainSchemaMarkerEntity.REQUIRED_TOKEN_SEMANTICS,
		terminalUnproven = 1,
	)

	private val EXPECTED_NAMED_OBJECT_COUNT =
		EXPECTED_TABLES.size + EXPECTED_INDEXES.size + EXPECTED_TRIGGERS.size
}
