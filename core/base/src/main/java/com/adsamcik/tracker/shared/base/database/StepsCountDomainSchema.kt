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
					  terminal.`owner_kind` = 'SESSION_COMPLETENESS' AND
					  terminal.`operation` = 'BIND'
					) OR
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
		val authorityNamespace = database.authorityNamespace()
		if (authorityNamespace == null) {
			StepsCountDomainSchemaState.Incompatible
		} else {
			when {
				authorityNamespace.objects.isEmpty() -> StepsCountDomainSchemaState.Absent
				database.hasExactV2Structure(
					requireMarker = true,
					requireTriggers = true,
					authorityNamespace = authorityNamespace,
				) ->
					StepsCountDomainSchemaState.ValidV2
				database.hasExactFreshRoomScaffold(authorityNamespace) ->
					StepsCountDomainSchemaState.FreshRoomScaffold
				else -> StepsCountDomainSchemaState.Incompatible
			}
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

	private fun SupportSQLiteDatabase.hasExactFreshRoomScaffold(
		authorityNamespace: AuthorityNamespace,
	): Boolean =
		hasExactV2Structure(
			requireMarker = false,
			requireTriggers = false,
			authorityNamespace = authorityNamespace,
		) &&
			EXPECTED_TABLES.keys.all { table -> rowCount(table) == 0L }

	private fun SupportSQLiteDatabase.rowCount(table: String): Long =
		query("SELECT COUNT(*) FROM `$MAIN_CATALOG`.`$table`").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun SupportSQLiteDatabase.hasExactV2Structure(requireMarker: Boolean): Boolean {
		val authorityNamespace = authorityNamespace() ?: return false
		return hasExactV2Structure(
			requireMarker,
			requireTriggers = true,
			authorityNamespace = authorityNamespace,
		)
	}

	private fun SupportSQLiteDatabase.hasExactV2Structure(
		requireMarker: Boolean,
		requireTriggers: Boolean,
		authorityNamespace: AuthorityNamespace,
	): Boolean {
		val expectedPermanentTriggers = if (requireTriggers) {
			EXPECTED_TRIGGERS
		} else {
			emptySet()
		}
		if (!authorityNamespace.roomInvalidationTriggers.hasAuthenticRoomInvalidationSets(this)) {
			return false
		}
		val authorityRoomInvalidationTriggers =
			authorityNamespace.roomInvalidationTriggers.filter { room ->
				room.trigger.table in AUTHORITY_TABLE_NAMES
			}
		val expectedTriggers =
			expectedPermanentTriggers +
				authorityRoomInvalidationTriggers.map(RoomInvalidationTrigger::trigger)
		if (!authorityNamespace.triggers.hasExactDistinctElements(expectedTriggers)) return false
		val expectedObjects = if (requireTriggers) {
			EXPECTED_VALID_NAMED_OBJECTS
		} else {
			EXPECTED_SCAFFOLD_NAMED_OBJECTS
		} + authorityRoomInvalidationTriggers.map { room ->
			SchemaNamedObject(
				catalog = room.trigger.catalog,
				type = "trigger",
				name = room.trigger.name,
				table = room.trigger.table,
			)
		}
		if (!authorityNamespace.objects.hasExactDistinctElements(expectedObjects)) return false
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

		val markerRows = authenticatedSchemaMarkers() ?: return false
		return if (requireMarker) {
			markerRows == listOf(EXPECTED_MARKER)
		} else {
			markerRows.isEmpty()
		}
	}

	private fun SupportSQLiteDatabase.authenticatedSchemaMarkers(): List<SchemaMarker>? =
		query(
			"SELECT id, contract_version, token_semantics, terminal_unproven " +
				"FROM `$MAIN_CATALOG`.`$SCHEMA_MARKER_TABLE` ORDER BY id LIMIT 2",
		).use { cursor ->
			val markers = mutableListOf<SchemaMarker>()
			var valid = true
			while (cursor.moveToNext()) {
				if (
					cursor.getType(0) != android.database.Cursor.FIELD_TYPE_INTEGER ||
					cursor.getType(1) != android.database.Cursor.FIELD_TYPE_INTEGER ||
					cursor.getType(2) != android.database.Cursor.FIELD_TYPE_STRING ||
					cursor.getType(3) != android.database.Cursor.FIELD_TYPE_INTEGER
				) {
					valid = false
					break
				}
				val id = cursor.getLong(0)
				val contractVersion = cursor.getLong(1)
				val terminalUnproven = cursor.getLong(3)
				if (
					id !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
					contractVersion !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
					terminalUnproven !in 0L..1L
				) {
					valid = false
					break
				}
				val tokenSemantics = cursor.getString(2)
					?.takeIf(String::isNotBlank)
				if (tokenSemantics == null) {
					valid = false
					break
				}
				markers += SchemaMarker(
					id = id.toInt(),
					contractVersion = contractVersion.toInt(),
					tokenSemantics = tokenSemantics,
					terminalUnproven = terminalUnproven.toInt(),
				)
			}
			markers.takeIf { valid }
		}

	private fun SupportSQLiteDatabase.authorityNamespace(): AuthorityNamespace? {
		val triggerScan = authenticatedAuthorityTriggers() ?: return null
		val triggers = triggerScan.authorityTriggers
		val mainObjects = query(
			"SELECT type, name, tbl_name FROM sqlite_master " +
				"WHERE type != 'trigger' AND (" +
				"lower(name) LIKE 'steps_count_domain_%' " +
				"OR lower(name) LIKE 'idx_steps_count_domain_%' " +
				"OR lower(name) LIKE 'trg_steps_count_domain_%' " +
				"OR tbl_name COLLATE NOCASE IN (?, ?, ?, ?))",
			arrayOf(
				RECEIPT_TABLE,
				OWNER_TABLE,
				COMPLETENESS_MARKER_TABLE,
				SCHEMA_MARKER_TABLE,
			),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						SchemaNamedObject(
							catalog = MAIN_CATALOG,
							type = cursor.getString(0).lowercase(),
							name = cursor.getString(1),
							table = cursor.getString(2).canonicalAuthorityTableName(),
						),
					)
				}
			}
		}
		val tempShadows = query(
			"SELECT type, name, tbl_name FROM sqlite_temp_master " +
				"WHERE type != 'trigger' AND (" +
				"name COLLATE NOCASE IN (?, ?, ?, ?, ?) " +
				"OR tbl_name COLLATE NOCASE IN (?, ?, ?, ?, ?))",
			(AUTHORITY_TABLE_NAMES + AUTHORITY_TABLE_NAMES).toTypedArray(),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						SchemaNamedObject(
							catalog = TEMP_CATALOG,
							type = cursor.getString(0).lowercase(),
							name = cursor.getString(1),
							table = cursor.getString(2).canonicalAuthorityTableName(),
						),
					)
				}
			}
		}
		val objects = mainObjects + tempShadows + triggers.map { trigger ->
			SchemaNamedObject(
				catalog = trigger.catalog,
				type = "trigger",
				name = trigger.name,
				table = trigger.table,
			)
		}
		return AuthorityNamespace(
			objects = objects,
			triggers = triggers,
			roomInvalidationTriggers = triggerScan.roomInvalidationTriggers,
		)
	}

	private fun SupportSQLiteDatabase.tableColumns(table: String): List<SchemaColumn> =
		query("PRAGMA $MAIN_CATALOG.table_info(`$table`)").use { cursor ->
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
		query("PRAGMA $MAIN_CATALOG.foreign_key_list(`$table`)").use { cursor ->
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
			query("PRAGMA $MAIN_CATALOG.index_list(`$table`)").use { cursor ->
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
		query("PRAGMA $MAIN_CATALOG.index_info(`$name`)").use { cursor ->
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

	@Suppress("ReturnCount")
	private fun SupportSQLiteDatabase.authenticatedAuthorityTriggers(): AuthenticatedTriggerScan? {
		val authorityTriggers = mutableListOf<SchemaTrigger>()
		val roomInvalidationTriggers = mutableListOf<RoomInvalidationTrigger>()
		val catalogNames = mutableSetOf<Pair<String, String>>()
		val expectedIdentities = mutableSetOf<String>()
		// writable_schema can forge tbl_name, so every bounded complete definition authenticates it.
		query(
			"SELECT catalog, name, tbl_name, sql FROM (" +
				"SELECT '$MAIN_CATALOG' AS catalog, name, tbl_name, sql " +
				"FROM sqlite_master WHERE type = 'trigger' " +
				"UNION ALL " +
				"SELECT '$TEMP_CATALOG' AS catalog, name, tbl_name, sql " +
				"FROM sqlite_temp_master WHERE type = 'trigger') " +
				"LIMIT ${MAX_TRIGGER_COUNT + 1}",
		).use { cursor ->
			var triggerCount = 0
			while (cursor.moveToNext()) {
				triggerCount += 1
				if (triggerCount > MAX_TRIGGER_COUNT ||
					cursor.isNull(1) ||
					cursor.isNull(2) ||
					cursor.isNull(3)
				) return null
				val catalog = cursor.getString(0)
				val name = cursor.getString(1)
				val storedTable = cursor.getString(2)
				if (name.length > MAX_SQL_TOKEN_LENGTH ||
					storedTable.length > MAX_SQL_TOKEN_LENGTH
				) return null
				val sql = cursor.getString(3).normalizedSql() ?: return null
				val definition = sql.completeTriggerOrNull() ?: return null
				if (!name.equalsAsciiIgnoreCase(definition.name.table) ||
					!storedTable.equalsAsciiIgnoreCase(definition.target.table) ||
					definition.name.schema?.equalsAsciiIgnoreCase(catalog) == false
				) return null
				val resolvedTarget = resolveTriggerTarget(catalog, definition.target)
					?: return null
				val canonicalTarget = if (resolvedTarget.catalog == MAIN_CATALOG) {
					resolvedTarget.table.canonicalAuthorityTableName()
				} else {
					resolvedTarget.table
				}
				val trigger = SchemaTrigger(
					catalog = catalog,
					name = name,
					targetCatalog = resolvedTarget.catalog,
					table = canonicalTarget,
					sql = sql,
				)
				val roomInvalidation = if (
					name.startsWithAsciiIgnoreCase(ROOM_INVALIDATION_TRIGGER_PREFIX)
				) {
					trigger.authenticatedRoomInvalidationTriggerOrNull() ?: return null
				} else {
					null
				}
				if (
					resolvedTarget.catalog == MAIN_CATALOG &&
					canonicalTarget in AUTHORITY_TABLE_NAMES ||
					name.startsWithAsciiIgnoreCase(AUTHORITY_TRIGGER_PREFIX) ||
					roomInvalidation != null
				) {
					val catalogName = catalog.mapAsciiLowercaseToUppercase() to
						name.mapAsciiLowercaseToUppercase()
					if (!catalogNames.add(catalogName)) return null
					EXPECTED_TRIGGER_NAMES.singleOrNull(name::equalsAsciiIgnoreCase)?.let {
						if (!expectedIdentities.add(it)) return null
					}
					roomInvalidation?.let(roomInvalidationTriggers::add)
					if (roomInvalidation == null || canonicalTarget in AUTHORITY_TABLE_NAMES) {
						authorityTriggers += trigger
					}
				}
			}
		}
		return AuthenticatedTriggerScan(authorityTriggers, roomInvalidationTriggers)
	}

	private fun SupportSQLiteDatabase.resolveTriggerTarget(
		triggerCatalog: String,
		target: QualifiedSqlIdentifier,
	): ResolvedTriggerTarget? {
		if (triggerCatalog == MAIN_CATALOG) {
			if (target.schema != null &&
				!target.schema.equalsAsciiIgnoreCase(MAIN_CATALOG)
			) return null
			return when (schemaObjectExistsInCatalog(MAIN_CATALOG, target.table)) {
				true -> ResolvedTriggerTarget(MAIN_CATALOG, target.table)
				false, null -> null
			}
		}
		if (triggerCatalog != TEMP_CATALOG) return null
		val catalogs = databaseCatalogs() ?: return null
		target.schema?.let { requested ->
			val catalog = catalogs.singleOrNull { candidate ->
				requested.equalsAsciiIgnoreCase(candidate.name)
			} ?: return null
			return when (schemaObjectExistsInCatalog(catalog.name, target.table)) {
				true -> ResolvedTriggerTarget(catalog.name, target.table)
				false, null -> null
			}
		}
		for (catalog in catalogs.sqliteSchemaLookupOrder()) {
			when (schemaObjectExistsInCatalog(catalog.name, target.table)) {
				true -> return ResolvedTriggerTarget(catalog.name, target.table)
				false -> Unit
				null -> return null
			}
		}
		return null
	}

	private fun SchemaTrigger.authenticatedRoomInvalidationTriggerOrNull():
		RoomInvalidationTrigger? {
		if (catalog != TEMP_CATALOG ||
			targetCatalog != MAIN_CATALOG
		) {
			return null
		}
		val operation = ROOM_INVALIDATION_OPERATIONS.singleOrNull { candidate ->
			name == "$ROOM_INVALIDATION_TRIGGER_PREFIX${table}_$candidate"
		} ?: return null
		val tableId = sql.roomInvalidationTableIdOrNull() ?: return null
		val expectedBody =
			"TRIGGER IF NOT EXISTS `$name` AFTER $operation ON `$table` BEGIN UPDATE " +
				"$ROOM_INVALIDATION_LOG_TABLE SET invalidated = 1 WHERE table_id = $tableId " +
				"AND invalidated = 0; END"
		val expectedSql = listOf(
			"CREATE TEMP $expectedBody",
			"CREATE $expectedBody",
		).mapNotNull { candidate -> candidate.normalizedSql() }
		if (sql !in expectedSql) return null
		return RoomInvalidationTrigger(this, operation, tableId)
	}

	private fun List<RoomInvalidationTrigger>.hasAuthenticRoomInvalidationSets(
		database: SupportSQLiteDatabase,
	): Boolean {
		val logState = database.roomInvalidationLogState()
		if (isEmpty()) return logState != RoomInvalidationLogState.INCOMPATIBLE
		val groups = groupBy { trigger -> trigger.trigger.table }
		if (groups.any { (_, triggers) ->
				triggers.map(RoomInvalidationTrigger::operation).toSet() !=
					ROOM_INVALIDATION_OPERATIONS ||
					triggers.map(RoomInvalidationTrigger::tableId).distinct().size != 1
			}
		) {
			return false
		}
		val tableIds = groups.values.map { triggers -> triggers.first().tableId }
		if (tableIds.distinct().size != tableIds.size) return false
		if (logState != RoomInvalidationLogState.EXACT) return false
		val placeholders = List(tableIds.size) { "?" }.joinToString()
		var valid = true
		val storedRows = database.query(
			"SELECT table_id, invalidated FROM $TEMP_CATALOG.$ROOM_INVALIDATION_LOG_TABLE " +
				"WHERE table_id IN ($placeholders) ORDER BY table_id LIMIT ${tableIds.size + 1}",
			tableIds.toTypedArray(),
		).use { cursor ->
			mutableMapOf<Int, Int>().apply {
				while (cursor.moveToNext()) {
					if (
						cursor.getType(0) != android.database.Cursor.FIELD_TYPE_INTEGER ||
						cursor.getType(1) != android.database.Cursor.FIELD_TYPE_INTEGER
					) {
						valid = false
						break
					}
					val tableId = cursor.getLong(0)
					val invalidated = cursor.getLong(1)
					if (
						tableId !in 0L..Int.MAX_VALUE.toLong() ||
						invalidated !in 0L..1L ||
						put(tableId.toInt(), invalidated.toInt()) != null
					) {
						valid = false
						break
					}
				}
			}
		}
		return valid && storedRows.keys == tableIds.toSet()
	}

	private fun SupportSQLiteDatabase.roomInvalidationLogState(): RoomInvalidationLogState {
		val objects = query(
			"SELECT catalog, type, name, tbl_name, sql FROM (" +
				"SELECT '$MAIN_CATALOG' AS catalog, type, name, tbl_name, sql FROM sqlite_master " +
				"UNION ALL " +
				"SELECT '$TEMP_CATALOG' AS catalog, type, name, tbl_name, sql " +
				"FROM sqlite_temp_master) WHERE " +
				"name = ? COLLATE NOCASE OR tbl_name = ? COLLATE NOCASE LIMIT 3",
			arrayOf(ROOM_INVALIDATION_LOG_TABLE, ROOM_INVALIDATION_LOG_TABLE),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						RoomInvalidationLogObject(
							catalog = cursor.getString(0),
							type = cursor.getString(1),
							name = cursor.getString(2),
							table = cursor.getString(3),
							sql = if (cursor.isNull(4)) null else cursor.getString(4).normalizedSql(),
						),
					)
				}
			}
		}
		if (objects.isEmpty()) return RoomInvalidationLogState.ABSENT
		val exactTable = objects.singleOrNull()?.let { candidate ->
			candidate.catalog == TEMP_CATALOG &&
				candidate.type == "table" &&
				candidate.name == ROOM_INVALIDATION_LOG_TABLE &&
				candidate.table == ROOM_INVALIDATION_LOG_TABLE &&
				candidate.sql != null &&
				candidate.sql in ROOM_INVALIDATION_LOG_SQL
		} == true
		if (!exactTable) return RoomInvalidationLogState.INCOMPATIBLE
		val exactColumns =
			query("PRAGMA $TEMP_CATALOG.table_info(`$ROOM_INVALIDATION_LOG_TABLE`)").use { cursor ->
			val columns = buildList {
				while (cursor.moveToNext()) {
					add(
						SchemaColumn(
							name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
							type = cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase(),
							notNull =
								cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
							primaryKeyPosition =
								cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
							defaultValue = cursor.getColumnIndexOrThrow("dflt_value").let { index ->
								if (cursor.isNull(index)) null else cursor.getString(index)
							},
						),
					)
				}
			}
			columns == ROOM_INVALIDATION_LOG_COLUMNS
		}
		val exactRows = exactColumns && !query(
			"SELECT 1 FROM $TEMP_CATALOG.$ROOM_INVALIDATION_LOG_TABLE WHERE " +
				"typeof(table_id) != 'integer' OR table_id < 0 OR " +
				"typeof(invalidated) != 'integer' OR invalidated NOT IN (0, 1) LIMIT 1",
		).use { cursor -> cursor.moveToFirst() }
		return if (exactRows) {
			RoomInvalidationLogState.EXACT
		} else {
			RoomInvalidationLogState.INCOMPATIBLE
		}
	}

	private fun SupportSQLiteDatabase.databaseCatalogs(): List<DatabaseCatalog>? =
		query("PRAGMA database_list").use { cursor ->
			val sequenceIndex = cursor.getColumnIndexOrThrow("seq")
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			buildList {
				val sequences = mutableSetOf<Int>()
				while (cursor.moveToNext()) {
					val sequence = cursor.getInt(sequenceIndex)
					val name = cursor.getString(nameIndex)
					if (sequence < 0 || !sequences.add(sequence) ||
						name.isEmpty() || name.length > MAX_SQL_TOKEN_LENGTH ||
						any { existing -> name.equalsAsciiIgnoreCase(existing.name) }
					) return null
					add(DatabaseCatalog(sequence, name))
				}
			}
		}.takeIf { catalogs ->
			catalogs.count { it.name.equalsAsciiIgnoreCase(MAIN_CATALOG) } == 1 &&
				catalogs.single { it.name.equalsAsciiIgnoreCase(MAIN_CATALOG) }.sequence == 0
		}

	private fun List<DatabaseCatalog>.sqliteSchemaLookupOrder(): List<DatabaseCatalog> {
		val temporary = singleOrNull { it.name.equalsAsciiIgnoreCase(TEMP_CATALOG) }
		val main = singleOrNull { it.name.equalsAsciiIgnoreCase(MAIN_CATALOG) } ?: return emptyList()
		val attached = filterNot { catalog ->
			catalog.name.equalsAsciiIgnoreCase(TEMP_CATALOG) ||
				catalog.name.equalsAsciiIgnoreCase(MAIN_CATALOG)
		}.sortedBy(DatabaseCatalog::sequence)
		return listOfNotNull(temporary, main) + attached
	}

	private fun SupportSQLiteDatabase.schemaObjectExistsInCatalog(
		catalog: String,
		name: String,
	): Boolean? {
		val schemaTable = if (catalog.equalsAsciiIgnoreCase(TEMP_CATALOG)) {
			"sqlite_temp_master"
		} else {
			"${catalog.sqlQuotedIdentifier()}.sqlite_master"
		}
		return query(
			"SELECT name FROM $schemaTable WHERE type IN ('table', 'view') " +
				"AND name = ? COLLATE NOCASE LIMIT 2",
			arrayOf(name),
		).use { cursor ->
			if (!cursor.moveToFirst()) return@use false
			val storedName = cursor.getString(0)
			if (cursor.moveToNext() || !storedName.equalsAsciiIgnoreCase(name)) null else true
		}
	}

	private fun String.sqlQuotedIdentifier(): String = "\"${replace("\"", "\"\"")}\""

	private fun <T> List<T>.hasExactDistinctElements(expected: Set<T>): Boolean =
		size == expected.size && toSet() == expected

	private fun SupportSQLiteDatabase.tableSql(name: String): String? =
		query(
			"SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
			arrayOf(name),
		).use { cursor ->
			if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
		}

	private fun String?.hasDeferredForeignKey(): Boolean =
		normalizedSql()?.containsKeywordSequence("DEFERRABLE", "INITIALLY", "DEFERRED") == true

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun String?.normalizedSql(): SqlCanonical? {
		if (this == null || length > MAX_SQL_LENGTH) return null
		val tokens = mutableListOf<SqlToken>()
		var offset = 0
		var trailingComment = false
		fun appendToken(kind: SqlTokenKind, endExclusive: Int): Boolean {
			if (endExclusive <= offset || tokens.size >= MAX_SQL_TOKEN_COUNT) return false
			val text = substring(offset, endExclusive)
			if (text.length > MAX_SQL_TOKEN_LENGTH) return false
			tokens += SqlToken(kind, text)
			trailingComment = false
			offset = endExclusive
			return true
		}
		while (offset < length) {
			val current = this[offset]
			when {
				current.isSqliteWhitespace() -> offset += 1
				startsSqlLineComment(offset) -> {
					if (tokens.isNotEmpty()) trailingComment = true
					offset = sqlLineCommentEnd(offset)
				}
				startsSqlBlockComment(offset) -> {
					if (tokens.isNotEmpty()) trailingComment = true
					offset = sqlBlockCommentEnd(offset) ?: return null
				}
				(current == 'x' || current == 'X') && getOrNull(offset + 1) == '\'' -> {
					val endExclusive = sqlBlobLiteralEnd(offset) ?: return null
					if (!appendToken(SqlTokenKind.BLOB_LITERAL, endExclusive)) return null
				}
				current == '\'' -> {
					val endExclusive = quotedSqlTokenEnd(offset, current) ?: return null
					if (!appendToken(SqlTokenKind.STRING_LITERAL, endExclusive)) return null
				}
				current.isSqlIdentifierQuote() -> {
					val endExclusive = quotedSqlTokenEnd(offset, current) ?: return null
					if (!appendToken(SqlTokenKind.QUOTED_IDENTIFIER, endExclusive)) return null
				}
				current.isAsciiDigit() ||
					(current == '.' && getOrNull(offset + 1)?.isAsciiDigit() == true) -> {
					val endExclusive = sqlNumericLiteralEnd(offset) ?: return null
					if (!appendToken(SqlTokenKind.NUMERIC_LITERAL, endExclusive)) return null
				}
				current.isSqlBareIdentifierStart() -> {
					val start = offset
					while (offset < length && this[offset].isSqlBareIdentifierPart()) {
						offset += 1
					}
					val text = substring(start, offset)
					if (text.length > MAX_SQL_TOKEN_LENGTH ||
						tokens.size >= MAX_SQL_TOKEN_COUNT
					) return null
					val keyword = text.mapAsciiLowercaseToUppercase()
						.takeIf(SQLITE_DDL_KEYWORDS::contains)
					tokens += if (keyword == null) {
						SqlToken(SqlTokenKind.IDENTIFIER, text)
					} else {
						SqlToken(SqlTokenKind.KEYWORD, keyword)
					}
					trailingComment = false
				}
				else -> {
					val operator = sqlOperatorAt(offset)
					if (operator != null) {
						if (!appendToken(SqlTokenKind.OPERATOR, offset + operator.length)) return null
					} else if (current in SQLITE_PUNCTUATION) {
						if (!appendToken(SqlTokenKind.PUNCTUATION, offset + 1)) return null
					} else {
						return null
					}
				}
			}
		}
		return SqlCanonical(
			tokens.withoutCreateIfNotExists().withoutSingleTrailingSemicolon(),
			trailingComment,
		)
	}

	private fun String.quotedSqlTokenEnd(start: Int, opener: Char): Int? {
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
		return null
	}

	private fun String.sqlBlobLiteralEnd(start: Int): Int? {
		var offset = start + 2
		var digitCount = 0
		while (offset < length && this[offset] != '\'') {
			if (!this[offset].isAsciiHexDigit()) return null
			digitCount += 1
			offset += 1
		}
		if (offset >= length || digitCount % 2 != 0) return null
		return offset + 1
	}

	@Suppress("ReturnCount")
	private fun String.sqlNumericLiteralEnd(start: Int): Int? {
		var offset = start
		if (getOrNull(offset) == '0' &&
			(getOrNull(offset + 1) == 'x' || getOrNull(offset + 1) == 'X')
		) {
			offset += 2
			val digitsStart = offset
			while (getOrNull(offset)?.isAsciiHexDigit() == true) offset += 1
			return offset.takeIf { it > digitsStart }
		}
		var digitCount = 0
		while (getOrNull(offset)?.isAsciiDigit() == true) {
			digitCount += 1
			offset += 1
		}
		if (getOrNull(offset) == '.') {
			offset += 1
			while (getOrNull(offset)?.isAsciiDigit() == true) {
				digitCount += 1
				offset += 1
			}
		}
		if (digitCount == 0) return null
		if (getOrNull(offset) == 'e' || getOrNull(offset) == 'E') {
			offset += 1
			if (getOrNull(offset) == '+' || getOrNull(offset) == '-') offset += 1
			val exponentStart = offset
			while (getOrNull(offset)?.isAsciiDigit() == true) offset += 1
			if (offset == exponentStart) return null
		}
		return offset
	}

	private fun String.startsSqlLineComment(offset: Int): Boolean =
		getOrNull(offset) == '-' && getOrNull(offset + 1) == '-'

	private fun String.sqlLineCommentEnd(start: Int): Int {
		val newline = indexOf('\n', startIndex = start + 2)
		return if (newline < 0) length else newline
	}

	private fun String.startsSqlBlockComment(offset: Int): Boolean =
		getOrNull(offset) == '/' && getOrNull(offset + 1) == '*'

	private fun String.sqlBlockCommentEnd(start: Int): Int? {
		val closer = indexOf("*/", startIndex = start + 2)
		return if (closer < 0) null else closer + 2
	}

	private fun List<SqlToken>.withoutCreateIfNotExists(): List<SqlToken> {
		val objectKeywordIndex = when {
			getOrNull(0).keyword != "CREATE" -> return this
			getOrNull(1).keyword in setOf("TABLE", "INDEX", "TRIGGER") -> 1
			getOrNull(1).keyword == "UNIQUE" && getOrNull(2).keyword == "INDEX" -> 2
			getOrNull(1).keyword in setOf("TEMP", "TEMPORARY") &&
				getOrNull(2).keyword == "TRIGGER" -> 2
			else -> return this
		}
		val optionalClauseStart = objectKeywordIndex + 1
		if (getOrNull(optionalClauseStart).keyword != "IF" ||
			getOrNull(optionalClauseStart + 1).keyword != "NOT" ||
			getOrNull(optionalClauseStart + 2).keyword != "EXISTS"
		) return this
		return filterIndexed { index, _ ->
			index !in optionalClauseStart..optionalClauseStart + 2
		}
	}

	private fun List<SqlToken>.withoutSingleTrailingSemicolon(): List<SqlToken> =
		if (lastOrNull()?.isPunctuation(";") == true &&
			getOrNull(lastIndex - 1)?.isPunctuation(";") != true
		) {
			dropLast(1)
		} else {
			this
		}

	private fun String.sqlOperatorAt(offset: Int): String? =
		SQLITE_OPERATORS.firstOrNull { operator -> startsWith(operator, offset) }

	private fun String.canonicalAuthorityTableName(): String =
		AUTHORITY_TABLE_NAMES.singleOrNull { expected -> equalsAsciiIgnoreCase(expected) } ?: this

	private fun String.equalsAsciiIgnoreCase(other: String): Boolean =
		length == other.length && indices.all { index ->
			this[index].asciiUppercase() == other[index].asciiUppercase()
		}

	private fun String.startsWithAsciiIgnoreCase(prefix: String): Boolean =
		length >= prefix.length && substring(0, prefix.length).equalsAsciiIgnoreCase(prefix)

	private fun Char.asciiUppercase(): Char =
		if (this in 'a'..'z') (code - 32).toChar() else this

	private fun Char.isSqlIdentifierQuote(): Boolean = this == '"' || this == '`' || this == '['

	private fun Char.isSqliteWhitespace(): Boolean =
		this == ' ' || this == '\t' || this == '\n' || this == '\u000c' || this == '\r'

	private fun Char.isSqlBareIdentifierStart(): Boolean =
		this in 'a'..'z' || this in 'A'..'Z' || this == '_' || code >= 0x80

	private fun Char.isSqlBareIdentifierPart(): Boolean =
		isSqlBareIdentifierStart() || isAsciiDigit() || this == '$'

	private fun Char.isAsciiHexDigit(): Boolean =
		this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

	private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

	private fun String.mapAsciiLowercaseToUppercase(): String = buildString(length) {
		this@mapAsciiLowercaseToUppercase.forEach { character ->
			append(character.asciiUppercase())
		}
	}

	private data class SqlCanonical(
		val tokens: List<SqlToken>,
		val hasTrailingComment: Boolean,
	) {
		fun containsKeywordSequence(vararg keywords: String): Boolean =
			tokens.indices.any { start ->
				start + keywords.size <= tokens.size &&
					keywords.indices.all { offset ->
						tokens[start + offset].keyword == keywords[offset]
					}
			}

		fun roomInvalidationTableIdOrNull(): Int? {
			val tableIdOffsets = tokens.indices.filter { offset ->
				tokens[offset].identifierOrNull() == ROOM_INVALIDATION_TABLE_ID_COLUMN &&
					tokens.getOrNull(offset + 1)?.text == "="
			}
			if (tableIdOffsets.size != 1) return null
			val token = tokens.getOrNull(tableIdOffsets.single() + 2) ?: return null
			if (token.kind != SqlTokenKind.NUMERIC_LITERAL ||
				token.text.isEmpty() ||
				token.text.any { character -> !character.isAsciiDigit() } ||
				(token.text.length > 1 && token.text.startsWith('0'))
			) {
				return null
			}
			return token.text.toIntOrNull()?.takeIf { it >= 0 }
		}

		@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
		fun completeTriggerOrNull(): ParsedTriggerDefinition? {
			if (hasTrailingComment) return null
			var offset = 0
			fun consumeKeyword(keyword: String): Boolean {
				if (tokens.getOrNull(offset)?.keyword != keyword) return false
				offset += 1
				return true
			}
			if (!consumeKeyword("CREATE")) return null
			if (tokens.getOrNull(offset)?.keyword in setOf("TEMP", "TEMPORARY")) offset += 1
			if (!consumeKeyword("TRIGGER")) return null
			val triggerName = tokens.qualifiedIdentifierAt(offset) ?: return null
			offset = triggerName.nextOffset
			when (tokens.getOrNull(offset)?.keyword) {
				"BEFORE", "AFTER" -> offset += 1
				"INSTEAD" -> {
					offset += 1
					if (!consumeKeyword("OF")) return null
				}
			}
			when (tokens.getOrNull(offset)?.keyword) {
				"DELETE", "INSERT" -> offset += 1
				"UPDATE" -> {
					offset += 1
					if (consumeKeyword("OF")) {
						val firstColumn = tokens.sqlIdentifierAt(offset) ?: return null
						offset = firstColumn.nextOffset
						while (tokens.getOrNull(offset)?.isPunctuation(",") == true) {
							offset += 1
							val column = tokens.sqlIdentifierAt(offset) ?: return null
							offset = column.nextOffset
						}
					}
				}
				else -> return null
			}
			if (!consumeKeyword("ON")) return null
			val target = tokens.qualifiedIdentifierAt(offset) ?: return null
			offset = target.nextOffset
			if (tokens.getOrNull(offset)?.keyword == "FOR") {
				offset += 1
				if (!consumeKeyword("EACH") || !consumeKeyword("ROW")) return null
			}
			if (tokens.getOrNull(offset)?.keyword == "WHEN") {
				offset += 1
				val expressionStart = offset
				var parentheses = 0
				while (offset < tokens.size) {
					val token = tokens[offset]
					when {
						token.isPunctuation("(") -> parentheses += 1
						token.isPunctuation(")") -> {
							if (parentheses == 0) return null
							parentheses -= 1
						}
						token.isPunctuation(";") && parentheses == 0 -> return null
						token.keyword == "BEGIN" && parentheses == 0 -> break
					}
					offset += 1
				}
				if (offset == expressionStart || parentheses != 0) return null
			}
			if (!consumeKeyword("BEGIN")) return null

			val bodyStart = offset
			var parentheses = 0
			var blockDepth = 1
			var hasBodyToken = false
			var hasStatementTerminator = false
			while (offset < tokens.size) {
				val token = tokens[offset]
				when {
					token.isPunctuation("(") -> {
						parentheses += 1
						hasBodyToken = true
					}
					token.isPunctuation(")") -> {
						if (parentheses == 0) return null
						parentheses -= 1
						hasBodyToken = true
					}
					token.keyword in setOf("BEGIN", "CASE") -> {
						blockDepth += 1
						hasBodyToken = true
					}
					token.keyword == "END" -> {
						blockDepth -= 1
						if (blockDepth < 0) return null
						if (blockDepth == 0) {
							if (parentheses != 0 || offset == bodyStart ||
								!hasBodyToken || !hasStatementTerminator
							) return null
							offset += 1
							if (tokens.getOrNull(offset)?.isPunctuation(";") == true) {
								offset += 1
							}
							if (offset != tokens.size) return null
							return ParsedTriggerDefinition(
								name = triggerName.identifier,
								target = target.identifier,
							)
						}
						hasBodyToken = true
					}
					token.isPunctuation(";") && parentheses == 0 && blockDepth == 1 -> {
						hasStatementTerminator = true
					}
					else -> hasBodyToken = true
				}
				offset += 1
			}
			return null
		}
	}

	private data class SqlToken(
		val kind: SqlTokenKind,
		val text: String,
	) {
		val keyword: String? get() = text.takeIf { kind == SqlTokenKind.KEYWORD }

		fun isPunctuation(expected: String): Boolean =
			kind == SqlTokenKind.PUNCTUATION && text == expected

		fun identifierOrNull(): String? = when (kind) {
			SqlTokenKind.IDENTIFIER, SqlTokenKind.KEYWORD -> text
			SqlTokenKind.QUOTED_IDENTIFIER -> quotedIdentifierContentOrNull()
			else -> null
		}

		private fun quotedIdentifierContentOrNull(): String? {
			if (text.length < 2) return null
			val opener = text.first()
			val closer = if (opener == '[') ']' else opener
			if (text.last() != closer) return null
			return text.substring(1, text.lastIndex)
				.replace("$closer$closer", closer.toString())
		}
	}

	private fun List<SqlToken>.sqlIdentifierAt(offset: Int): ParsedSqlIdentifier? =
		getOrNull(offset)?.identifierOrNull()?.let { identifier ->
			ParsedSqlIdentifier(identifier, offset + 1)
		}

	private fun List<SqlToken>.qualifiedIdentifierAt(offset: Int): ParsedQualifiedSqlIdentifier? {
		val first = sqlIdentifierAt(offset) ?: return null
		if (getOrNull(first.nextOffset)?.isPunctuation(".") != true) {
			return ParsedQualifiedSqlIdentifier(
				QualifiedSqlIdentifier(schema = null, table = first.identifier),
				first.nextOffset,
			)
		}
		val second = sqlIdentifierAt(first.nextOffset + 1) ?: return null
		return ParsedQualifiedSqlIdentifier(
			QualifiedSqlIdentifier(schema = first.identifier, table = second.identifier),
			second.nextOffset,
		)
	}

	private enum class SqlTokenKind {
		KEYWORD,
		IDENTIFIER,
		QUOTED_IDENTIFIER,
		STRING_LITERAL,
		BLOB_LITERAL,
		NUMERIC_LITERAL,
		OPERATOR,
		PUNCTUATION,
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
		val sql: SqlCanonical?,
	)

	private data class SchemaTrigger(
		val catalog: String,
		val name: String,
		val targetCatalog: String,
		val table: String,
		val sql: SqlCanonical,
	)

	private data class RoomInvalidationTrigger(
		val trigger: SchemaTrigger,
		val operation: String,
		val tableId: Int,
	)

	private data class RoomInvalidationLogObject(
		val catalog: String,
		val type: String,
		val name: String,
		val table: String,
		val sql: SqlCanonical?,
	)

	private enum class RoomInvalidationLogState {
		ABSENT,
		EXACT,
		INCOMPATIBLE,
	}

	private data class SchemaNamedObject(
		val catalog: String,
		val type: String,
		val name: String,
		val table: String,
	)

	private data class AuthorityNamespace(
		val objects: List<SchemaNamedObject>,
		val triggers: List<SchemaTrigger>,
		val roomInvalidationTriggers: List<RoomInvalidationTrigger>,
	)

	private data class AuthenticatedTriggerScan(
		val authorityTriggers: List<SchemaTrigger>,
		val roomInvalidationTriggers: List<RoomInvalidationTrigger>,
	)

	private data class QualifiedSqlIdentifier(
		val schema: String?,
		val table: String,
	)

	private data class ParsedSqlIdentifier(
		val identifier: String,
		val nextOffset: Int,
	)

	private data class ParsedQualifiedSqlIdentifier(
		val identifier: QualifiedSqlIdentifier,
		val nextOffset: Int,
	)

	private data class ParsedTriggerDefinition(
		val name: QualifiedSqlIdentifier,
		val target: QualifiedSqlIdentifier,
	)

	private data class ResolvedTriggerTarget(
		val catalog: String,
		val table: String,
	)

	private data class DatabaseCatalog(
		val sequence: Int,
		val name: String,
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

	private val SQLITE_PUNCTUATION = setOf('(', ')', ',', '.', ';')
	private val SQLITE_OPERATORS = listOf(
		"->>",
		"||",
		"->",
		"<<",
		">>",
		"<=",
		">=",
		"==",
		"!=",
		"<>",
		"*",
		"/",
		"%",
		"+",
		"-",
		"~",
		"&",
		"|",
		"<",
		">",
		"=",
	)
	private val SQLITE_DDL_KEYWORDS = setOf(
		"ABORT",
		"ACTION",
		"AFTER",
		"AND",
		"AS",
		"BEFORE",
		"BEGIN",
		"CASCADE",
		"CASE",
		"CREATE",
		"DEFERRABLE",
		"DEFERRED",
		"DELETE",
		"EACH",
		"END",
		"EXISTS",
		"FOR",
		"FOREIGN",
		"FROM",
		"IF",
		"IN",
		"INDEX",
		"INITIALLY",
		"INSERT",
		"INSTEAD",
		"INTO",
		"KEY",
		"NO",
		"NOT",
		"NULL",
		"OF",
		"ON",
		"OR",
		"PRIMARY",
		"RAISE",
		"REFERENCES",
		"RESTRICT",
		"ROW",
		"SELECT",
		"TABLE",
		"TEMP",
		"TEMPORARY",
		"TRIGGER",
		"UNIQUE",
		"UPDATE",
		"VALUES",
		"WHEN",
		"WHERE",
	)

	private val EXPECTED_TABLE_SQL: Map<String, SqlCanonical> =
		EXPECTED_TABLES.keys.associateWith { table ->
			requireNotNull(
				requireNotNull(tableStatements.singleOrNull { statement ->
					statement.trimStart().startsWith("CREATE TABLE IF NOT EXISTS `$table`")
				}).normalizedSql(),
			)
		}

	private val EXPECTED_INDEX_SQL: Map<String, SqlCanonical> =
		EXPECTED_INDEXES.keys.associateWith { name ->
			requireNotNull(
				requireNotNull(indexStatements.singleOrNull { statement ->
					statement.contains("`$name`")
				}).normalizedSql(),
			)
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

	private val EXPECTED_TRIGGERS: Set<SchemaTrigger> = triggerStatements
		.mapTo(mutableSetOf()) { statement ->
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
			SchemaTrigger(
				catalog = MAIN_CATALOG,
				name = name,
				targetCatalog = MAIN_CATALOG,
				table = table,
				sql = requireNotNull(statement.normalizedSql()),
			)
		}
	private val EXPECTED_TRIGGER_NAMES = setOf(
		TERMINAL_OWNER_TRIGGER,
		AMBIENT_NO_RESURRECTION_TRIGGER,
		AMBIENT_RETRACTION_TRIGGER,
	)

	private val EXPECTED_MARKER = SchemaMarker(
		id = StepsCountDomainSchemaMarkerEntity.REQUIRED_ID,
		contractVersion = StepsCountDomainSchemaMarkerEntity.REQUIRED_CONTRACT_VERSION,
		tokenSemantics = StepsCountDomainSchemaMarkerEntity.REQUIRED_TOKEN_SEMANTICS,
		terminalUnproven = 1,
	)

	private val EXPECTED_SCAFFOLD_NAMED_OBJECTS =
		EXPECTED_TABLES.keys.mapTo(mutableSetOf()) { table ->
			SchemaNamedObject(MAIN_CATALOG, "table", table, table)
		} + EXPECTED_ALL_INDEXES.map { (name, index) ->
			SchemaNamedObject(MAIN_CATALOG, "index", name, index.table)
		}

	private val EXPECTED_VALID_NAMED_OBJECTS =
		EXPECTED_SCAFFOLD_NAMED_OBJECTS + EXPECTED_TRIGGERS.map { trigger ->
			SchemaNamedObject(trigger.catalog, "trigger", trigger.name, trigger.table)
		}

	private const val MAX_SQL_LENGTH = 65_536
	private const val MAX_SQL_TOKEN_COUNT = 4_096
	private const val MAX_SQL_TOKEN_LENGTH = 16_384
	private const val MAX_TRIGGER_COUNT = 512
	private const val MAIN_CATALOG = "main"
	private const val TEMP_CATALOG = "temp"
	private const val AUTHORITY_TRIGGER_PREFIX = "trg_steps_count_domain_"
	// Exact connection-local trigger contract emitted by Room 2.8.4 TriggerBasedInvalidationTracker.
	private const val ROOM_INVALIDATION_TRIGGER_PREFIX = "room_table_modification_trigger_"
	private const val ROOM_INVALIDATION_LOG_TABLE = "room_table_modification_log"
	private const val ROOM_INVALIDATION_TABLE_ID_COLUMN = "table_id"
	private val ROOM_INVALIDATION_OPERATIONS = setOf("INSERT", "UPDATE", "DELETE")
	// Room 2.8.4 CREATE_TRACKING_TABLE_SQL plus SQLite's persisted temp-schema normalizations.
	private val ROOM_INVALIDATION_LOG_SQL = listOf(
		"CREATE TEMP TABLE IF NOT EXISTS room_table_modification_log (" +
			"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)",
		"CREATE TABLE IF NOT EXISTS room_table_modification_log (" +
			"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)",
		"CREATE TABLE room_table_modification_log (" +
			"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)",
	).mapNotNull { sql -> sql.normalizedSql() }
	private val ROOM_INVALIDATION_LOG_COLUMNS = listOf(
		SchemaColumn("table_id", "INTEGER", false, 1),
		SchemaColumn("invalidated", "INTEGER", true, 0, "0"),
	)
	private val AUTHORITY_TABLE_NAMES = listOf(
		RECEIPT_TABLE,
		OWNER_TABLE,
		COMPLETENESS_MARKER_TABLE,
		SCHEMA_MARKER_TABLE,
		AMBIENT_FACT_TABLE,
	)
}
