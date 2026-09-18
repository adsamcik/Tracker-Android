package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainSchemaMarkerEntity

sealed interface StepsCountDomainSchemaState {
	data object Absent : StepsCountDomainSchemaState
	data object FreshRoomScaffold : StepsCountDomainSchemaState
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
 * transaction. Only the exact empty Room-created scaffold is accepted without a marker; partial or
 * markerless e500-era objects are incompatible and are never repaired or activated in place.
 * Counter-epoch generation is persisted in Steps source payload/checkpoint version 7 and does not
 * add another Room column here.
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
	private const val AMBIENT_FACT_TABLE = "ambient_steps_fact_revision"

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
	private val tableStatements =
		creationStatements.filter { it.trimStart().startsWith("CREATE TABLE") }
	private val indexStatements =
		creationStatements.filter { statement ->
			statement.trimStart().startsWith("CREATE INDEX") ||
				statement.trimStart().startsWith("CREATE UNIQUE INDEX")
		}
	private val triggerStatements =
		creationStatements.filter { it.trimStart().startsWith("CREATE TRIGGER") }

	private const val INSERT_MARKER_STATEMENT =
		"INSERT INTO `$SCHEMA_MARKER_TABLE` " +
			"(`id`, `contract_version`, `token_semantics`, `terminal_unproven`) " +
			"VALUES (1, 2, 'PROVIDER_COUNTER_EPOCH_V1', 1)"

	/**
	 * Installs v2 only into a completely absent namespace or the exact empty Room scaffold.
	 *
	 * Room creates registered entities and indexes before its callback runs, so that exact scaffold
	 * receives only the custom triggers and sentinel. The marker is written last, after the tables,
	 * keys, indexes, and triggers have been inspected. Any partial, non-empty, markerless, legacy,
	 * or otherwise inexact namespace remains
	 * [StepsCountDomainSchemaState.Incompatible].
	 */
	fun installIfAbsent(database: SupportSQLiteDatabase): StepsCountDomainSchemaState =
		when (inspect(database)) {
			StepsCountDomainSchemaState.Absent ->
				database.installAtomically(creationStatements)
			StepsCountDomainSchemaState.FreshRoomScaffold ->
				database.installAtomically(triggerStatements)
			StepsCountDomainSchemaState.ValidV2 -> StepsCountDomainSchemaState.ValidV2
			StepsCountDomainSchemaState.Incompatible -> StepsCountDomainSchemaState.Incompatible
		}

	/**
	 * Distinguishes a truly absent schema from one that contains untrusted or stale evidence.
	 */
	fun inspect(database: SupportSQLiteDatabase): StepsCountDomainSchemaState = try {
		when {
			database.authorityNamespaceObjects().isEmpty() -> StepsCountDomainSchemaState.Absent
			database.hasExactV2Structure(requireMarker = true) ->
				StepsCountDomainSchemaState.ValidV2
			database.hasExactFreshRoomScaffold() ->
				StepsCountDomainSchemaState.FreshRoomScaffold
			else -> StepsCountDomainSchemaState.Incompatible
		}
	} catch (_: SQLiteException) {
		StepsCountDomainSchemaState.Incompatible
	} catch (_: IllegalArgumentException) {
		StepsCountDomainSchemaState.Incompatible
	} catch (_: IllegalStateException) {
		StepsCountDomainSchemaState.Incompatible
	}

	private fun SupportSQLiteDatabase.installAtomically(
		statements: List<String>,
	): StepsCountDomainSchemaState {
		return try {
			execSQL("SAVEPOINT steps_count_domain_v2_install")
			statements.forEach { statement -> execSQL(statement) }
			check(hasExactV2Structure(requireMarker = false))
			execSQL(INSERT_MARKER_STATEMENT)
			check(inspect(this) == StepsCountDomainSchemaState.ValidV2)
			execSQL("RELEASE SAVEPOINT steps_count_domain_v2_install")
			StepsCountDomainSchemaState.ValidV2
		} catch (_: SQLiteException) {
			rollbackInstallation()
		} catch (_: IllegalArgumentException) {
			rollbackInstallation()
		} catch (_: IllegalStateException) {
			rollbackInstallation()
		}
	}

	private fun SupportSQLiteDatabase.rollbackInstallation(): StepsCountDomainSchemaState {
		runCatching { execSQL("ROLLBACK TO SAVEPOINT steps_count_domain_v2_install") }
		runCatching { execSQL("RELEASE SAVEPOINT steps_count_domain_v2_install") }
		return StepsCountDomainSchemaState.Incompatible
	}

	private fun SupportSQLiteDatabase.hasExactFreshRoomScaffold(): Boolean =
		hasExactV2Structure(requireMarker = false, requireTriggers = false) &&
			EXPECTED_TABLES.keys.all { table -> rowCount(table) == 0L }

	private fun SupportSQLiteDatabase.rowCount(table: String): Long =
		query("SELECT COUNT(*) FROM `$table`").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun SupportSQLiteDatabase.hasExactV2Structure(requireMarker: Boolean): Boolean {
		return hasExactV2Structure(requireMarker, requireTriggers = true)
	}

	private fun SupportSQLiteDatabase.hasExactV2Structure(
		requireMarker: Boolean,
		requireTriggers: Boolean,
	): Boolean {
		val expectedObjects = if (requireTriggers) {
			EXPECTED_VALID_NAMED_OBJECTS
		} else {
			EXPECTED_SCAFFOLD_NAMED_OBJECTS
		}
		if (authorityNamespaceObjects() != expectedObjects) return false
		if (EXPECTED_TABLE_SQL.any { (table, expected) ->
				tableSql(table).normalizedSql() != expected
			}
		) return false
		if (EXPECTED_TABLES.any { (table, expected) -> tableColumns(table) != expected }) return false
		if (foreignKeys(OWNER_TABLE) != EXPECTED_OWNER_FOREIGN_KEYS) return false
		if (foreignKeys(COMPLETENESS_MARKER_TABLE) != EXPECTED_COMPLETENESS_FOREIGN_KEYS) return false
		if (!tableSql(OWNER_TABLE).hasDeferredForeignKey()) return false
		if (!tableSql(COMPLETENESS_MARKER_TABLE).hasDeferredForeignKey()) return false

		if (attachedIndexes() != EXPECTED_ALL_INDEXES) return false
		val triggers = authorityTriggers()
		if (requireTriggers) {
			if (triggers != EXPECTED_TRIGGERS) return false
		} else if (triggers.isNotEmpty()) return false

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

	private fun SupportSQLiteDatabase.authorityNamespaceObjects(): Set<SchemaNamedObject> =
		query(
			"SELECT type, name, tbl_name FROM sqlite_master " +
				"WHERE lower(name) LIKE 'steps_count_domain_%' " +
				"OR lower(name) LIKE 'idx_steps_count_domain_%' " +
				"OR lower(name) LIKE 'trg_steps_count_domain_%' " +
				"OR tbl_name IN (?, ?, ?, ?) " +
				"OR (type = 'trigger' AND tbl_name = ?)",
			arrayOf(
				RECEIPT_TABLE,
				OWNER_TABLE,
				COMPLETENESS_MARKER_TABLE,
				SCHEMA_MARKER_TABLE,
				AMBIENT_FACT_TABLE,
			),
		).use { cursor ->
			buildSet {
				while (cursor.moveToNext()) {
					add(
						SchemaNamedObject(
							type = cursor.getString(0).lowercase(),
							name = cursor.getString(1),
							table = cursor.getString(2),
						),
					)
				}
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

	private fun SupportSQLiteDatabase.attachedIndexes(): Map<String, SchemaIndex> = buildMap {
		EXPECTED_TABLES.keys.forEach { table ->
			query("PRAGMA index_list(`$table`)").use { cursor ->
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
				val originIndex = cursor.getColumnIndexOrThrow("origin")
				val partialIndex = cursor.getColumnIndexOrThrow("partial")
				while (cursor.moveToNext()) {
					val name = cursor.getString(nameIndex)
					put(
						name,
						SchemaIndex(
							table = table,
							unique = cursor.getInt(uniqueIndex) == 1,
							origin = cursor.getString(originIndex).lowercase(),
							partial = cursor.getInt(partialIndex) == 1,
							columns = indexColumns(name),
							sql = indexSql(name)?.normalizedSql(),
						),
					)
				}
			}
		}
	}

	private fun SupportSQLiteDatabase.indexColumns(name: String): List<String?> =
		query("PRAGMA index_info(`$name`)").use { cursor ->
			val sequenceIndex = cursor.getColumnIndexOrThrow("seqno")
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			buildList {
				while (cursor.moveToNext()) {
					add(cursor.getInt(sequenceIndex) to cursor.getString(nameIndex))
				}
			}.sortedBy { it.first }.map { it.second }
		}

	private fun SupportSQLiteDatabase.indexSql(name: String): String? =
		query(
			"SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ? LIMIT 1",
			arrayOf(name),
		).use { cursor ->
			if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
		}

	private fun SupportSQLiteDatabase.authorityTriggers(): Map<String, SchemaTrigger> =
		query(
			"SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'trigger' AND (" +
				"tbl_name IN (?, ?, ?, ?) OR tbl_name = ? " +
				"OR lower(name) LIKE 'trg_steps_count_domain_%')",
			arrayOf(
				RECEIPT_TABLE,
				OWNER_TABLE,
				COMPLETENESS_MARKER_TABLE,
				SCHEMA_MARKER_TABLE,
				AMBIENT_FACT_TABLE,
			),
		).use { cursor ->
			buildMap {
				while (cursor.moveToNext()) {
					put(
						cursor.getString(0),
						SchemaTrigger(
							table = cursor.getString(1),
							sql = cursor.getString(2).normalizedSql(),
						),
					)
				}
			}
		}

	private fun SupportSQLiteDatabase.tableSql(name: String): String =
		query(
			"SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
			arrayOf(name),
		).use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else ""
		}

	private fun String.hasDeferredForeignKey(): Boolean =
		normalizedSql().contains("DEFERRABLE INITIALLY DEFERRED")

	private fun String.normalizedSql(): String {
		val tokens = mutableListOf<SqlToken>()
		var offset = 0
		while (offset < length) {
			val current = this[offset]
			when {
				current.isSqliteWhitespace() -> offset += 1
				startsSqlLineComment(offset) -> {
					val endExclusive = sqlLineCommentEnd(offset)
					tokens += SqlToken(substring(offset, endExclusive), quoted = true)
					offset = endExclusive
				}
				startsSqlBlockComment(offset) -> {
					val endExclusive = sqlBlockCommentEnd(offset)
					tokens += SqlToken(substring(offset, endExclusive), quoted = true)
					offset = endExclusive
				}
				current.isSqlQuote() -> {
					val endExclusive = quotedSqlTokenEnd(offset, current)
					tokens += SqlToken(substring(offset, endExclusive), quoted = true)
					offset = endExclusive
				}
				current.isSqlBareTokenCharacter() -> {
					val start = offset
					while (offset < length && this[offset].isSqlBareTokenCharacter()) {
						offset += 1
					}
					tokens += SqlToken(
						substring(start, offset).mapAsciiLowercaseToUppercase(),
						quoted = false,
					)
				}
				else -> {
					tokens += SqlToken(current.toString(), quoted = false)
					offset += 1
				}
			}
		}
		val structuralTokens = tokens.withoutCreateIfNotExists()
		return buildString {
			var previous: SqlToken? = null
			structuralTokens.forEach { token ->
				if (previous?.isAtom == true && token.isAtom) append(' ')
				append(token.text)
				previous = token
			}
		}
	}

	private fun String.quotedSqlTokenEnd(start: Int, opener: Char): Int {
		val closer = if (opener == '[') ']' else opener
		var offset = start + 1
		while (offset < length) {
			if (this[offset] != closer) {
				offset += 1
				continue
			}
			if (opener != '[' && offset + 1 < length && this[offset + 1] == closer) {
				offset += 2
				continue
			}
			return offset + 1
		}
		throw IllegalArgumentException("Unclosed SQL quote")
	}

	private fun String.startsSqlLineComment(offset: Int): Boolean =
		getOrNull(offset) == '-' && getOrNull(offset + 1) == '-'

	private fun String.sqlLineCommentEnd(start: Int): Int {
		val newline = indexOf('\n', startIndex = start + 2)
		return if (newline < 0) length else newline
	}

	private fun String.startsSqlBlockComment(offset: Int): Boolean =
		getOrNull(offset) == '/' && getOrNull(offset + 1) == '*'

	private fun String.sqlBlockCommentEnd(start: Int): Int {
		val closer = indexOf("*/", startIndex = start + 2)
		if (closer < 0) throw IllegalArgumentException("Unclosed SQL block comment")
		return closer + 2
	}

	private fun List<SqlToken>.withoutCreateIfNotExists(): List<SqlToken> {
		val optionalClauseStart = indices.firstOrNull { index ->
			index > 0 &&
				this[index - 1].bareText in setOf("TABLE", "INDEX", "TRIGGER") &&
				getOrNull(index).bareText == "IF" &&
				getOrNull(index + 1).bareText == "NOT" &&
				getOrNull(index + 2).bareText == "EXISTS"
		} ?: return this
		return filterIndexed { index, _ ->
			index !in optionalClauseStart..optionalClauseStart + 2
		}
	}

	private fun Char.isSqlQuote(): Boolean = this == '\'' || this == '"' || this == '`' || this == '['

	private fun Char.isSqliteWhitespace(): Boolean =
		this == ' ' || this == '\t' || this == '\n' || this == '\u000c' || this == '\r'

	private fun Char.isSqlBareTokenCharacter(): Boolean =
		this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
			this == '_' || this == '$' || code >= 0x80

	private fun String.mapAsciiLowercaseToUppercase(): String = buildString(length) {
		this@mapAsciiLowercaseToUppercase.forEach { character ->
			append(if (character in 'a'..'z') (character.code - 32).toChar() else character)
		}
	}

	private data class SqlToken(
		val text: String,
		val quoted: Boolean,
	) {
		val bareText: String? get() = text.takeUnless { quoted }
		val isAtom: Boolean get() = quoted || text.first().isSqlBareTokenCharacter()
	}

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
		val origin: String,
		val partial: Boolean,
		val columns: List<String?>,
		val sql: String?,
	)

	private data class SchemaTrigger(
		val table: String,
		val sql: String,
	)

	private data class SchemaNamedObject(
		val type: String,
		val name: String,
		val table: String,
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
			"c",
			false,
			listOf("owner_kind", "owner_identity", "owner_revision"),
			null,
		),
		"idx_steps_count_domain_receipt_compatibility" to SchemaIndex(
			RECEIPT_TABLE,
			false,
			"c",
			false,
			listOf("domain_identity", "collected_data_epoch", "count_domain_version"),
			null,
		),
		"idx_steps_count_domain_owner_scope" to SchemaIndex(
			OWNER_TABLE,
			false,
			"c",
			false,
			listOf("owner_kind", "scope_identity", "owner_identity", "owner_revision"),
			null,
		),
		"idx_steps_count_domain_owner_receipt" to SchemaIndex(
			OWNER_TABLE,
			false,
			"c",
			false,
			listOf("receipt_identity"),
			null,
		),
		"idx_steps_count_domain_owner_terminal_age" to SchemaIndex(
			OWNER_TABLE,
			false,
			"c",
			false,
			listOf("operation", "linked_at_ms", "owner_kind", "owner_identity"),
			null,
		),
		"idx_steps_count_domain_completeness_owner" to SchemaIndex(
			COMPLETENESS_MARKER_TABLE,
			true,
			"c",
			false,
			listOf("owner_kind", "owner_identity", "owner_revision"),
			null,
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

	private val EXPECTED_TABLE_SQL = EXPECTED_TABLES.keys.associateWith { table ->
		requireNotNull(tableStatements.singleOrNull { statement ->
			statement.trimStart().startsWith("CREATE TABLE IF NOT EXISTS `$table`")
		}).normalizedSql()
	}

	private val EXPECTED_INDEX_SQL = EXPECTED_INDEXES.keys.associateWith { name ->
		requireNotNull(indexStatements.singleOrNull { statement ->
			statement.contains("`$name`")
		}).normalizedSql()
	}

	private val EXPECTED_ALL_INDEXES: Map<String, SchemaIndex> =
		EXPECTED_INDEXES.mapValues { (name, index) ->
			index.copy(sql = requireNotNull(EXPECTED_INDEX_SQL[name]))
		} + mapOf(
			"sqlite_autoindex_${RECEIPT_TABLE}_1" to SchemaIndex(
				RECEIPT_TABLE,
				true,
				"pk",
				false,
				listOf("receipt_identity"),
				null,
			),
			"sqlite_autoindex_${OWNER_TABLE}_1" to SchemaIndex(
				OWNER_TABLE,
				true,
				"pk",
				false,
				listOf("owner_kind", "owner_identity", "owner_revision"),
				null,
			),
			"sqlite_autoindex_${COMPLETENESS_MARKER_TABLE}_1" to SchemaIndex(
				COMPLETENESS_MARKER_TABLE,
				true,
				"pk",
				false,
				listOf("owner_identity", "owner_revision"),
				null,
			),
		)

	private val EXPECTED_TRIGGERS: Map<String, SchemaTrigger> = triggerStatements
		.associate { statement ->
			val name = when {
				statement.contains(TERMINAL_OWNER_TRIGGER) -> TERMINAL_OWNER_TRIGGER
				statement.contains(AMBIENT_NO_RESURRECTION_TRIGGER) ->
					AMBIENT_NO_RESURRECTION_TRIGGER
				else -> AMBIENT_RETRACTION_TRIGGER
			}
			val table = when (name) {
				TERMINAL_OWNER_TRIGGER -> OWNER_TABLE
				else -> AMBIENT_FACT_TABLE
			}
			name to SchemaTrigger(table, statement.normalizedSql())
		}

	private val EXPECTED_MARKER = SchemaMarker(
		id = StepsCountDomainSchemaMarkerEntity.REQUIRED_ID,
		contractVersion = StepsCountDomainSchemaMarkerEntity.REQUIRED_CONTRACT_VERSION,
		tokenSemantics = StepsCountDomainSchemaMarkerEntity.REQUIRED_TOKEN_SEMANTICS,
		terminalUnproven = 1,
	)

	private val EXPECTED_SCAFFOLD_NAMED_OBJECTS =
		EXPECTED_TABLES.keys.mapTo(mutableSetOf()) { table ->
			SchemaNamedObject("table", table, table)
		} + EXPECTED_ALL_INDEXES.map { (name, index) ->
			SchemaNamedObject("index", name, index.table)
		}

	private val EXPECTED_VALID_NAMED_OBJECTS =
		EXPECTED_SCAFFOLD_NAMED_OBJECTS + EXPECTED_TRIGGERS.map { (name, trigger) ->
			SchemaNamedObject("trigger", name, trigger.table)
		}
}
