package com.adsamcik.tracker.shared.base.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import java.io.File

/**
 * Pre-open classification for the stable active database file.
 *
 * Version 28 is an unshipped development schema. A same-version database is admitted only when it
 * carries this assembly's explicit marker and a small set of indispensable final schema sentinels.
 */
sealed interface ActiveDatabasePreflightResult {
	/**
	 * No existing database file requires schema containment before creating the current Room
	 * database.
	 */
	data object Fresh : ActiveDatabasePreflightResult
	/**
	 * The saved schema matches the recognized development v27 upgrade input.
	 */
	data object KnownDevelopmentV27 : ActiveDatabasePreflightResult
	/**
	 * The saved schema and final marker match the recognized complete v28 database.
	 */
	data object FinalV28 : ActiveDatabasePreflightResult
	/**
	 * The saved schema matches the recognized complete v29 database.
	 */
	data object FinalV29 : ActiveDatabasePreflightResult
	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class Retryable(val reason: ActiveDatabaseRetryableReason) : ActiveDatabasePreflightResult
	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(val reason: ActiveDatabaseBlockReason) : ActiveDatabasePreflightResult
}

/**
 * Lock contention or temporary operational failure that permits retry without classifying the
 * saved database as corrupt.
 */
enum class ActiveDatabaseRetryableReason(val failureCode: String) {
	CONTENDED("ACTIVE_DATABASE_CONTENDED"),
	OPERATIONALLY_UNAVAILABLE("ACTIVE_DATABASE_OPERATIONALLY_UNAVAILABLE"),
}

/**
 * Unsupported or incomplete schema, invalid final marker or unreadable/failed upgrade state
 * requiring preserved recovery.
 */
enum class ActiveDatabaseBlockReason(val failureCode: String) {
	STALE_DEVELOPMENT_V28("STALE_DEVELOPMENT_V28"),
	UNSUPPORTED_DATABASE_VERSION("UNSUPPORTED_DATABASE_VERSION"),
	UNRECOGNIZED_DATABASE_SCHEMA("UNRECOGNIZED_DATABASE_SCHEMA"),
	INVALID_FINAL_V28_MARKER("INVALID_FINAL_V28_MARKER"),
	INCOMPLETE_FINAL_V28_SCHEMA("INCOMPLETE_FINAL_V28_SCHEMA"),
	INCOMPLETE_FINAL_V29_SCHEMA("INCOMPLETE_FINAL_V29_SCHEMA"),
	UNREADABLE_DATABASE("UNREADABLE_DATABASE"),
	DEVELOPMENT_V27_MIGRATION_VALIDATION_FAILED("DEVELOPMENT_V27_MIGRATION_VALIDATION_FAILED"),
	FINAL_V28_OPEN_VALIDATION_FAILED("FINAL_V28_OPEN_VALIDATION_FAILED"),
	FINAL_V29_OPEN_VALIDATION_FAILED("FINAL_V29_OPEN_VALIDATION_FAILED"),
}

/**
 * Carries the precise permanent database preflight/open block reason while preserving the original
 * cause.
 */
class ActiveDatabaseOpenBlockedException(
	val reason: ActiveDatabaseBlockReason,
	cause: Throwable? = null,
) : IllegalStateException(reason.failureCode, cause)

/**
 * Carries a transient database contention/operational reason and optional original cause.
 */
class ActiveDatabaseOpenRetryableException(
	val reason: ActiveDatabaseRetryableReason,
	cause: Throwable? = null,
) : IllegalStateException(reason.failureCode, cause)

/**
 * Inspects the existing database file before Room opens it, distinguishing supported schemas from
 * blocked and retryable states.
 */
class ActiveDatabasePreflight internal constructor(
	private val databaseFile: File,
) {
	constructor(
		context: Context,
		databaseName: String = ACTIVE_DATABASE_NAME,
	) : this(context.applicationContext.getDatabasePath(databaseName))

	/**
	 * Inspects the existing file before Room mutation and returns supported schema, transient
	 * contention or a preserved-data block reason.
	 */
	fun inspect(): ActiveDatabasePreflightResult = try {
		when {
			!databaseFile.exists() -> ActiveDatabasePreflightResult.Fresh
			!databaseFile.isFile -> ActiveDatabasePreflightResult.Retryable(
				ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE,
			)
			else -> openDatabasePreservingFiles(
				databaseFile,
				SQLiteDatabase.OPEN_READONLY,
			).use(::classify)
		}
	} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
		// Classify framework/open failures without touching the preserved file; unknown failures still escape.
		val retryableReason = failure.activeDatabaseRetryableReason()
		when {
			failure.isActiveDatabaseCorruption() -> ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
			)
			retryableReason != null -> ActiveDatabasePreflightResult.Retryable(retryableReason)
			failure is SQLiteException -> ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
			)
			else -> throw failure
		}
	}

	private fun classify(database: SQLiteDatabase): ActiveDatabasePreflightResult {
		if (!database.passesQuickIntegrityCheck()) {
			return ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
			)
		}
		return classifyVersion(database, database.version)
	}

	private fun classifyVersion(database: SQLiteDatabase, version: Int): ActiveDatabasePreflightResult = when {
		version == 0 && !database.hasApplicationTables() -> ActiveDatabasePreflightResult.Fresh
		version != INITIAL_ACTIVE_DATABASE_VERSION && version != DEVELOPMENT_DATABASE_VERSION_V28 &&
			version != DEVELOPMENT_DATABASE_VERSION_V29 && version != CURRENT_DATABASE_VERSION ->
			ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.UNSUPPORTED_DATABASE_VERSION)
		!BASELINE_TABLES.all(database::hasTable) ->
			ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.UNRECOGNIZED_DATABASE_SCHEMA)
		version == INITIAL_ACTIVE_DATABASE_VERSION -> ActiveDatabasePreflightResult.KnownDevelopmentV27
		else -> classifyFinalVersion(database, version)
	}

	private fun classifyFinalVersion(database: SQLiteDatabase, version: Int): ActiveDatabasePreflightResult {
		finalMarkerFailure(database)?.let { return it }
		return when {
			!FinalDatabaseSchemaChecks.hasV28(database, version) ->
				ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA)
			version == DEVELOPMENT_DATABASE_VERSION_V28 -> ActiveDatabasePreflightResult.FinalV28
			!FinalDatabaseSchemaChecks.hasV29(database) ->
				ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA)
			else -> ActiveDatabasePreflightResult.FinalV29
		}
	}

	private fun finalMarkerFailure(database: SQLiteDatabase): ActiveDatabasePreflightResult? {
		val markerState = try {
			database.finalV28MarkerState()
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Marker reads distinguish corruption, contention and invalid schema; unknown failures still escape.
			if (failure.isActiveDatabaseCorruption()) {
				return ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.UNREADABLE_DATABASE)
			}
			failure.activeDatabaseRetryableReason()?.let {
				return ActiveDatabasePreflightResult.Retryable(it)
			}
			if (failure is SQLiteException) {
				return ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INVALID_FINAL_V28_MARKER)
			}
			throw failure
		}
		return when (markerState) {
			FinalV28MarkerState.MISSING, FinalV28MarkerState.STALE ->
				ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28)
			FinalV28MarkerState.INVALID ->
				ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INVALID_FINAL_V28_MARKER)
			FinalV28MarkerState.VALID -> null
		}
	}
}

/** The existing final schema sentinels stay ordered by the data authority they protect. */
private object FinalDatabaseSchemaChecks {
	private val retainedFactTableColumns = listOf(
		FINAL_V28_SOURCE_EVENT_RETENTION_PROOF_TABLE to FINAL_V28_SOURCE_EVENT_RETENTION_PROOF_COLUMNS,
		FINAL_V28_SOURCE_EVENT_RETENTION_SCOPE_PROOF_TABLE to FINAL_V28_SOURCE_EVENT_RETENTION_SCOPE_PROOF_COLUMNS,
		FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE to FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_COLUMNS,
		FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE to FINAL_V28_PRESSURE_RETENTION_AUTHORITY_COLUMNS,
		FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_TABLE to FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_COLUMNS,
	)

	fun hasV28(database: SQLiteDatabase, version: Int): Boolean {
		val lifecycleIndexes = if (version == DEVELOPMENT_DATABASE_VERSION_V28) {
			FINAL_V28_LIFECYCLE_PROOF_INDEXES
		} else {
			FINAL_V28_LIFECYCLE_PROOF_INDEXES.filterNot {
				it.name == FINAL_V29_CAPTURED_RADIO_RETENTION_EVENT_INDEX.name
			}
		}
		return hasPayload(database) && hasFinalSchemaSourceBindings(database) && hasFinalSchemaLocation(database) &&
			hasExecutionAndProducts(database, lifecycleIndexes) && hasAuthorityAndOrganization(database)
	}

	private fun hasPayload(database: SQLiteDatabase): Boolean =
		FINAL_V28_REQUIRED_TABLES.all(database::hasTable) &&
			retainedFactTableColumns.all { (table, columns) -> database.hasExactColumns(table, columns) }

	private fun hasExecutionAndProducts(
		database: SQLiteDatabase,
		lifecycleIndexes: List<FinalV28IndexSentinel>,
	): Boolean = hasExecutionColumns(database) && hasLifecycleAndStepsTables(database, lifecycleIndexes) &&
		hasFinalSchemaStepsCountDomain(database)

	private fun hasExecutionColumns(database: SQLiteDatabase): Boolean =
		FINAL_V28_RETENTION_JOURNAL_COLUMNS.all { database.hasColumn(FINAL_V28_RETENTION_JOURNAL_TABLE, it) } &&
			FINAL_V28_EXECUTION_RECEIPT_COLUMNS.all { database.hasColumn(FINAL_V28_EXECUTION_RECEIPT_TABLE, it) } &&
			FINAL_V28_CALLER_AUTHORITY_COLUMNS.all { database.hasColumn(FINAL_V28_CALLER_AUTHORITY_TABLE, it) } &&
			FINAL_V28_LOCATION_DECISION_COLUMNS.all { database.hasColumn(FINAL_V28_LOCATION_DECISION_TABLE, it) }

	private fun hasLifecycleAndStepsTables(
		database: SQLiteDatabase,
		lifecycleIndexes: List<FinalV28IndexSentinel>,
	): Boolean = FINAL_V28_STEPS_TABLE_COLUMNS.all { (table, columns) -> database.hasExactColumns(table, columns) } &&
		lifecycleIndexes.all(database::hasExactIndex) && database.hasTrigger(FINAL_V28_PRESSURE_RETENTION_TRIGGER)

	private fun hasAuthorityAndOrganization(database: SQLiteDatabase): Boolean =
		FINAL_V28_AUTHORITY_INDEXES.all(database::hasExactIndex) &&
			FINAL_V28_ORGANIZATION_COLUMNS.all(database::hasExactColumn) &&
			FINAL_V28_ORGANIZATION_INDEXES.all(database::hasExactIndex) &&
			database.hasTrigger(FinalV28TriggerSentinel("clear_organization_activity_label", "activity"))

	fun hasV29(database: SQLiteDatabase): Boolean =
		FINAL_V29_ADDITIONAL_COLUMNS.all(database::hasExactColumn) && hasLegacyDetails(database) &&
			hasPortableMembership(database) && hasLocationArchives(database) && hasRadioDetails(database)

	private fun hasLegacyDetails(database: SQLiteDatabase): Boolean =
		FINAL_V29_LEGACY_PRESERVED_COLUMNS.all(database::hasExactColumn) &&
			FINAL_V29_LEGACY_PRESERVED_TABLE_COLUMNS.all { (table, names) -> database.hasExactColumns(table, names) } &&
			FINAL_V29_LEGACY_PRESERVED_INDEXES.all(database::hasExactIndex) &&
			database.hasExactIndex(FINAL_V29_CAPTURED_RADIO_RETENTION_EVENT_INDEX)

	private fun hasPortableMembership(database: SQLiteDatabase): Boolean =
		FINAL_V29_NATIVE_PORTABLE_COLUMNS.all(database::hasExactColumn) &&
			database.hasExactColumns(
				"native_portable_ownership",
				listOf("source_kind", "identity_kind", "opaque_identity", "raw_identity", "logical_tracking_id", "service_run_id"),
			) && FINAL_V29_NATIVE_PORTABLE_INDEXES.all(database::hasExactIndex) &&
			FINAL_V29_NATIVE_PORTABLE_TRIGGERS.all(database::hasTrigger)

	private fun hasLocationArchives(database: SQLiteDatabase): Boolean =
		FINAL_V29_LOCATION_ARCHIVE_COLUMNS.all { (table, columns) -> database.hasExactColumns(table, columns) } &&
			FINAL_V29_LOCATION_ARCHIVE_INDEXES.all(database::hasExactIndex)

	private fun hasRadioDetails(database: SQLiteDatabase): Boolean =
		FINAL_V29_RADIO_CHILD_COLUMNS.all { (table, columns) -> database.hasExactColumns(table, columns) } &&
			FINAL_V29_RADIO_CHILD_INDEXES.all(database::hasExactIndex) &&
			FINAL_V29_RADIO_MIRROR_TRIGGERS.all(database::hasTrigger)
}

private fun hasFinalSchemaSourceBindings(database: SQLiteDatabase): Boolean =
	database.hasColumn(FINAL_V28_REQUIRED_COLUMN_TABLE, FINAL_V28_REQUIRED_COLUMN) &&
		database.hasColumn(FINAL_V28_RADIO_RECEIPT_TABLE, FINAL_V28_RADIO_RECEIPT_COLUMN) &&
		FINAL_V28_APPLIED_PLAN_COLUMNS.all(database::hasExactColumn)

private fun hasFinalSchemaLocation(database: SQLiteDatabase): Boolean =
	FINAL_V28_LOCATION_COLUMNS.all(database::hasExactColumn) &&
		FINAL_V28_LOCATION_INDEXES.all(database::hasExactIndex)

private fun hasFinalSchemaStepsCountDomain(database: SQLiteDatabase): Boolean =
	FINAL_V28_STEPS_INDEXES.all(database::hasExactIndex) &&
		FINAL_V28_STEPS_TRIGGERS.all(database::hasTrigger) && database.hasExactStepsCountDomainMarker()

private const val DEVELOPMENT_DATABASE_VERSION_V28 = 28
private const val DEVELOPMENT_DATABASE_VERSION_V29 = 29

internal class DevelopmentV28ContainmentOpenHelperFactory(
	private val delegate: SupportSQLiteOpenHelper.Factory,
	private val inspect: () -> ActiveDatabasePreflightResult,
) : SupportSQLiteOpenHelper.Factory {
	constructor(
		context: Context,
		databaseName: String,
		delegate: SupportSQLiteOpenHelper.Factory,
	) : this(
		delegate = delegate,
		inspect = { ActiveDatabasePreflight(context, databaseName).inspect() },
	)

	override fun create(
		configuration: SupportSQLiteOpenHelper.Configuration,
	): SupportSQLiteOpenHelper = GuardedOpenHelper(delegate.create(configuration))

	private inner class GuardedOpenHelper(
		private val delegate: SupportSQLiteOpenHelper,
	) : SupportSQLiteOpenHelper by delegate {
		@Volatile
		private var admittedState: ActiveDatabasePreflightResult? = null

		override val writableDatabase: SupportSQLiteDatabase
			get() = open { delegate.writableDatabase }

		override val readableDatabase: SupportSQLiteDatabase
			get() = open { delegate.readableDatabase }

		@Synchronized
		private fun open(block: () -> SupportSQLiteDatabase): SupportSQLiteDatabase {
			val state = admittedState ?: inspect().requireAdmittedDatabaseState()
			return try {
				block().also { admittedState = state }
			} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
				// The Room open boundary publishes the existing retry/block reason and preserves unclassified failures.
				throw failure.classifiedActiveDatabaseOpenFailure(state)
			}
		}
	}
}

private fun ActiveDatabasePreflightResult.requireAdmittedDatabaseState(): ActiveDatabasePreflightResult {
	if (this is ActiveDatabasePreflightResult.Blocked) {
		throw ActiveDatabaseOpenBlockedException(reason)
	}
	if (this is ActiveDatabasePreflightResult.Retryable) {
		throw ActiveDatabaseOpenRetryableException(reason)
	}
	return this
}

internal object FinalV28SchemaAssemblyRoomCallback : RoomDatabase.Callback() {
	override fun onCreate(db: SupportSQLiteDatabase) {
		check(
			StepsCountDomainSchema.inspect(db) ==
				StepsCountDomainSchemaState.FreshRoomScaffold,
		) { "Fresh v28 database did not create the exact Steps count-domain scaffold" }
		check(
			StepsCountDomainSchema.installIfAbsent(db) ==
				StepsCountDomainSchemaState.ValidV2,
		) { "Fresh v28 database could not authenticate the Steps count-domain schema" }
		createFinalV28SchemaAssemblyMarker(db)
	}

	override fun onOpen(db: SupportSQLiteDatabase) {
		check(
			StepsCountDomainSchema.inspect(db) == StepsCountDomainSchemaState.ValidV2,
		) { "AppDatabase opened without the exact Steps count-domain schema" }
		if (db.inTransaction()) {
			db.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction()
		} else {
			db.beginTransaction()
			try {
				db.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction()
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}
		}
	}
}

internal fun createFinalV28SchemaAssemblyMarker(database: SupportSQLiteDatabase) {
	database.execSQL(
		"INSERT INTO room_master_table(id, identity_hash) VALUES (?, ?)",
		arrayOf<Any?>(FINAL_V28_MARKER_ID, FINAL_V28_ASSEMBLY_ID),
	)
}

private fun SQLiteDatabase.hasApplicationTables(): Boolean = rawQuery(
	"""
	SELECT 1
	FROM sqlite_master
	WHERE type = 'table'
	  AND name NOT LIKE 'sqlite_%'
	  AND name != 'android_metadata'
	LIMIT 1
	""".trimIndent(),
	null,
).use { it.moveToFirst() }

private fun SQLiteDatabase.passesQuickIntegrityCheck(): Boolean =
	rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
		cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
	}

private fun SQLiteDatabase.hasTable(name: String): Boolean =
	hasSchemaObject(type = "table", name = name)

private fun SQLiteDatabase.hasSchemaObject(type: String, name: String): Boolean = rawQuery(
	"SELECT 1 FROM sqlite_master WHERE type = ? AND name = ? LIMIT 1",
	arrayOf(type, name),
).use { it.moveToFirst() }

private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
	rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
		val nameColumn = cursor.getColumnIndexOrThrow("name")
		while (cursor.moveToNext()) {
			if (cursor.getString(nameColumn) == column) {
				return@use true
			}
		}
		false
	}

private fun SQLiteDatabase.hasExactColumn(column: FinalV28ColumnSentinel): Boolean =
	rawQuery("PRAGMA table_info(`${column.table}`)", null).use { cursor ->
		val nameColumn = cursor.getColumnIndexOrThrow("name")
		val typeColumn = cursor.getColumnIndexOrThrow("type")
		val notNullColumn = cursor.getColumnIndexOrThrow("notnull")
		val primaryKeyColumn = cursor.getColumnIndexOrThrow("pk")
		val defaultValueColumn = cursor.getColumnIndexOrThrow("dflt_value")
		var found = false
		while (cursor.moveToNext()) {
			if (cursor.getString(nameColumn) == column.name) {
				if (found || !cursor.matchesFinalSchemaColumn(
						column, typeColumn, notNullColumn, primaryKeyColumn, defaultValueColumn,
					)
				) {
					return@use false
				}
				found = true
			}
		}
		found
	}

private fun Cursor.matchesFinalSchemaColumn(
	column: FinalV28ColumnSentinel,
	typeColumn: Int,
	notNullColumn: Int,
	primaryKeyColumn: Int,
	defaultValueColumn: Int,
): Boolean = getString(typeColumn).equals(column.type, ignoreCase = true) &&
	(getInt(notNullColumn) != 0) == column.notNull && getInt(primaryKeyColumn) == column.primaryKeyPosition &&
	(column.defaultValue == null || getString(defaultValueColumn) == column.defaultValue)

private fun SQLiteDatabase.hasExactColumns(
	table: String,
	expectedColumns: List<String>,
): Boolean = rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
	val nameColumn = cursor.getColumnIndexOrThrow("name")
	val actualColumns = buildList {
		while (cursor.moveToNext()) add(cursor.getString(nameColumn))
	}
	actualColumns == expectedColumns
}

private fun SQLiteDatabase.hasExactIndex(index: FinalV28IndexSentinel): Boolean {
	val exactDefinition = hasExactIndexDefinition(index)
	if (!exactDefinition) {
		return false
	}
	val actualColumns = rawQuery("PRAGMA index_xinfo(`${index.name}`)", null).use { cursor ->
		val nameColumn = cursor.getColumnIndexOrThrow("name")
		val descendingColumn = cursor.getColumnIndexOrThrow("desc")
		val keyColumn = cursor.getColumnIndexOrThrow("key")
		val collationColumn = cursor.getColumnIndexOrThrow("coll")
		buildList {
			while (cursor.moveToNext()) {
				if (cursor.getInt(keyColumn) != 0) {
					// Owned sentinel DDL uses default BINARY comparison, including identity keys.
					if (cursor.getString(collationColumn) != "BINARY") {
						return false
					}
					add(
						FinalV28IndexedColumn(
							name = cursor.getString(nameColumn),
							descending = cursor.getInt(descendingColumn) != 0,
						),
					)
				}
			}
		}
	}
	return actualColumns == index.columns.mapIndexed { position, name ->
		FinalV28IndexedColumn(name, position in index.descendingColumns)
	}
}

private fun SQLiteDatabase.hasExactIndexDefinition(index: FinalV28IndexSentinel): Boolean =
	rawQuery("PRAGMA index_list(`${index.table}`)", null).use { cursor ->
		val nameColumn = cursor.getColumnIndexOrThrow("name")
		val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
		val partialColumn = cursor.getColumnIndexOrThrow("partial")
		var found = false
		while (cursor.moveToNext()) {
			if (cursor.getString(nameColumn) == index.name) {
				if (found || (cursor.getInt(uniqueColumn) != 0) != index.unique ||
					cursor.getInt(partialColumn) != 0
				) {
					return@use false
				}
				found = true
			}
		}
		found
	}

private fun SQLiteDatabase.hasTrigger(trigger: FinalV28TriggerSentinel): Boolean = rawQuery(
	"SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ? AND tbl_name = ? LIMIT 1",
	arrayOf(trigger.name, trigger.table),
).use { it.moveToFirst() }

private fun SQLiteDatabase.hasExactStepsCountDomainMarker(): Boolean = rawQuery(
	"SELECT id, contract_version, token_semantics, terminal_unproven " +
		"FROM `${StepsCountDomainSchema.SCHEMA_MARKER_TABLE}` ORDER BY id LIMIT 2",
	null,
).use { cursor ->
	cursor.moveToFirst() &&
		cursor.getLong(0) == 1L &&
		cursor.getLong(1) == 2L &&
		cursor.getString(2) == "PROVIDER_COUNTER_EPOCH_V1" &&
		cursor.getLong(STEPS_MARKER_TERMINAL_UNPROVEN_COLUMN) == 1L &&
		!cursor.moveToNext()
}

private fun SQLiteDatabase.finalV28MarkerState(): FinalV28MarkerState = rawQuery(
	"SELECT identity_hash FROM room_master_table WHERE id = ?",
	arrayOf(FINAL_V28_MARKER_ID.toString()),
).use { cursor ->
	if (!cursor.moveToFirst()) {
		return@use FinalV28MarkerState.MISSING
	}
	when (cursor.getString(0)) {
		FINAL_V28_ASSEMBLY_ID -> FinalV28MarkerState.VALID
		in STALE_FINAL_V28_ASSEMBLY_IDS -> FinalV28MarkerState.STALE
		else -> FinalV28MarkerState.INVALID
	}
}

private enum class FinalV28MarkerState {
	MISSING,
	STALE,
	INVALID,
	VALID,
}

private const val STEPS_MARKER_TERMINAL_UNPROVEN_COLUMN = 3

internal const val FINAL_V28_MARKER_ID = -280_917
internal const val FINAL_V28_ASSEMBLY_ID =
	"tracker-v28-location-file-identity-20260928"
private val STALE_FINAL_V28_ASSEMBLY_IDS = setOf(
	"tracker-v28-session-organization-20260927",
	"tracker-v28-reconciled-source-inputs-20260927",
	"tracker-v28-adaptive-location-authority-20260926",
	"tracker-v28-authenticated-retention-scope-proof-20260921",
	"tracker-v28-applied-source-plan-state-20260920",
	"tracker-v28-retention-scope-proof-20260921",
	"tracker-v28-radio-source-erase-proof-20260921",
	"tracker-v28-radio-fact-retention-proof-20260921",
	"tracker-v28-final-20260917",
	"tracker-v28-retention-final-20260917",
	"tracker-v28-retention-integrity-20260918",
	"tracker-v28-retention-journal-20260919",
	"tracker-v28-retention-execution-20260919",
	"tracker-v28-retention-caller-authority-20260919",
	"tracker-v28-retention-caller-steps-count-domain-20260919",
	"tracker-v28-portable-steps-count-domain-20260920",
	"tracker-v28-portable-ambient-graph-revision-20260920",
	"tracker-v28-portable-ambient-graph-provenance-20260920",
	"tracker-v28-portable-steps-disk-staging-20260920",
	"tracker-v28-lifecycle-source-proof-20260920",
	"tracker-v28-lifecycle-retention-proof-20260920",
	"tracker-v28-lifecycle-retention-authority-proof-20260920",
	"tracker-v28-pressure-retention-revision-authority-20260921",
	"tracker-v28-pressure-lineage-retention-authority-20260921",
)

private val BASELINE_TABLES = setOf(
	"room_master_table",
	"activity",
	"tracker_run",
	"pending_signal",
)
private val FINAL_V28_REQUIRED_TABLES = setOf(
	"location_file_identity",
	"imported_wifi_deletion_generation",
	"ambient_steps_retention_authority",
	"ambient_steps_native_replay_footprint",
	"collected_data_deletion_operation",
	"retention_work_execution_receipt",
	FINAL_V28_SOURCE_EVENT_RETENTION_PROOF_TABLE,
	FINAL_V28_SOURCE_EVENT_RETENTION_SCOPE_PROOF_TABLE,
	FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE,
	FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE,
	FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_TABLE,
	FINAL_V28_CALLER_AUTHORITY_TABLE,
	StepsCountDomainSchema.RECEIPT_TABLE,
	StepsCountDomainSchema.OWNER_TABLE,
	StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
	StepsCountDomainSchema.SCHEMA_MARKER_TABLE,
	"imported_steps_count_domain_graph",
	"imported_steps_count_domain_receipt",
	"imported_steps_count_domain_owner_revision",
	"imported_steps_count_domain_completeness",
	"imported_steps_count_domain_root",
	"imported_steps_count_domain_binding",
	"imported_steps_file_receipt",
	"imported_steps_count_domain_owner_fence",
	"imported_steps_full_clear_binding_stage",
	"imported_steps_full_clear_session_product_stage",
	"imported_steps_full_clear_owner_stage",
	"imported_steps_full_clear_owner_revision_stage",
	"imported_steps_full_clear_explicit_lineage_stage",
	"source_applied_plan_state",
	"location_registration_plan",
	"location_accepted_receipt",
	"location_sample_purpose_authority",
	"ambient_location_retention_authority",
)

private val FINAL_V28_LOCATION_COLUMNS = listOf(
	FinalV28ColumnSentinel("location_file_identity", "portable_id", "TEXT", true, 1),
	FinalV28ColumnSentinel("location_file_identity", "sample_id", "INTEGER", true),
	FinalV28ColumnSentinel("location_file_identity", "imported", "INTEGER", true),
	FinalV28ColumnSentinel("location_file_identity", "reported_origin", "TEXT", true),
	FinalV28ColumnSentinel("acquisition_plan_revision", "balance_profile_version", "INTEGER", false),
	FinalV28ColumnSentinel("source_desired_plan", "requested_balance_level", "INTEGER", false),
	FinalV28ColumnSentinel("source_desired_plan", "focus_requested", "INTEGER", true, defaultValue = "0"),
	FinalV28ColumnSentinel("source_policy", "balance_profile_version", "INTEGER", false),
	FinalV28ColumnSentinel("source_policy", "balance_level", "INTEGER", false),
	FinalV28ColumnSentinel("source_policy", "overall_balance_level", "INTEGER", false),
	FinalV28ColumnSentinel("source_policy", "source_balance_override", "INTEGER", false),
	FinalV28ColumnSentinel("source_policy", "location_background_mode", "INTEGER", false),
	FinalV28ColumnSentinel("source_policy", "location_motion_assistance_enabled", "INTEGER", true, defaultValue = "0"),
	FinalV28ColumnSentinel("location_registration_plan", "registration_generation", "INTEGER", true, 1),
	FinalV28ColumnSentinel("location_registration_plan", "configuration_revision", "INTEGER", true, 2),
	FinalV28ColumnSentinel("location_registration_plan", "authorization_revision", "INTEGER", true, 3),
	FinalV28ColumnSentinel("location_registration_plan", "payload", "BLOB", true),
	FinalV28ColumnSentinel("location_registration_plan", "effect_checksum", "TEXT", true),
	FinalV28ColumnSentinel("location_accepted_receipt", "sample_id", "INTEGER", true, 1),
	FinalV28ColumnSentinel("location_accepted_receipt", "wal_integrity_identity", "TEXT", true),
	FinalV28ColumnSentinel("location_accepted_receipt", "canonical_content_checksum", "TEXT", true),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "sample_id", "INTEGER", true, 1),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "purpose", "TEXT", true, 2),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "authority_id", "TEXT", true, 3),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "logical_tracking_id", "TEXT", false),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "ambient_intent_id", "TEXT", false),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "zone_id", "TEXT", false),
	FinalV28ColumnSentinel("ambient_location_retention_authority", "scope", "TEXT", true, 1),
	FinalV28ColumnSentinel("ambient_location_retention_authority", "approval_revision", "INTEGER", true, 2),
	FinalV28ColumnSentinel("ambient_location_retention_authority", "effect_checksum", "TEXT", true),
)
private val FINAL_V28_LOCATION_INDEXES = listOf(
	FinalV28IndexSentinel("location_file_identity", "idx_location_file_sample", listOf("sample_id"), true),
	FinalV28IndexSentinel("location_accepted_receipt", "idx_location_receipt_event", listOf("source_event_id"), true),
	FinalV28IndexSentinel("location_accepted_receipt", "idx_location_receipt_sequence", listOf("source_instance_id",
        "source_sequence"), true),
	FinalV28IndexSentinel("location_sample_purpose_authority", "idx_location_purpose_authority", listOf("purpose",
        "authority_id", "sample_id"), false),
	FinalV28IndexSentinel("ambient_location_retention_authority", "idx_ambient_location_retention_effective",
		listOf("scope", "effective_boot_id", "effective_elapsed_realtime_nanos", "approval_revision"), false),
)
private const val FINAL_V28_REQUIRED_COLUMN_TABLE = "pending_signal"
private const val FINAL_V28_REQUIRED_COLUMN = "pressure_writer_owner_generation"
private const val FINAL_V28_RADIO_RECEIPT_TABLE = "cell_captured_entry_deletion_receipt"
private const val FINAL_V28_RADIO_RECEIPT_COLUMN = "retained_from_ms"
private const val FINAL_V28_SOURCE_EVENT_RETENTION_PROOF_TABLE =
	"source_event_retention_proof"
private val FINAL_V28_SOURCE_EVENT_RETENTION_PROOF_COLUMNS = listOf(
	"source_kind",
	"event_id",
	"source_admission_ordinal",
	"purpose_eligibility_mask",
	"source_policy_revision",
	"capture_consent_epoch",
	"collected_data_epoch",
	"logical_tracking_id",
	"service_run_id",
	"scope_deletion_generation",
	"logical_run_scope_digest",
	"wal_payload_checksum",
	"wal_integrity_identity",
	"proof_authority_fingerprint",
	"reason",
	"effect_checksum",
)
private const val FINAL_V28_SOURCE_EVENT_RETENTION_SCOPE_PROOF_TABLE =
	"source_event_retention_scope_proof"
private val FINAL_V28_SOURCE_EVENT_RETENTION_SCOPE_PROOF_COLUMNS = listOf(
	"source_kind",
	"purpose",
	"logical_tracking_id",
	"service_run_id",
	"source_policy_revision",
	"capture_consent_epoch",
	"collected_data_epoch",
	"scope_deletion_generation",
	"logical_run_scope_digest",
	"retained_event_high_water_ordinal",
	"retained_event_authority_checksum",
	"effect_checksum",
)
private const val FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE =
	"captured_radio_fact_retention_proof"
private val FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_COLUMNS = listOf(
	"source_kind",
	"writer_projection_id",
	"writer_projection_version",
	"writer_binding_generation",
	"writer_owner_generation",
	"logical_fact_id",
	"semantic_revision",
	"mutation_id",
	"fact_effect_checksum",
	"source_event_id",
	"source_admission_ordinal",
	"wal_integrity_identity",
	"source_policy_revision",
	"capture_consent_epoch",
	"collected_data_epoch",
	"logical_tracking_id",
	"service_run_id",
	"scope_deletion_generation",
	"logical_run_scope_digest",
	"cursor_revision",
	"cursor_mutation_id",
	"cursor_effect_checksum",
	"cursor_source_admission_ordinal",
	"cursor_updated_at_ms",
	"aggregate_owner_logical_fact_id",
	"aggregate_owner_semantic_revision",
	"aggregate_owner_source_event_id",
	"aggregate_owner_source_admission_ordinal",
	"aggregate_owner_wal_integrity_identity",
	"aggregate_owner_effect_checksum",
	"aggregate_owner_cursor_revision",
	"retained_from_ms",
	"retained_at_ms",
	"reason",
	"effect_checksum",
)
private const val FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE =
	"pressure_fact_retention_authority"
private val FINAL_V28_PRESSURE_RETENTION_AUTHORITY_COLUMNS = listOf(
	"writer_projection_id",
	"writer_projection_version",
	"logical_fact_id",
	"current_semantic_revision",
	"current_source_event_id",
	"current_source_admission_ordinal",
	"current_source_policy_revision",
	"current_capture_consent_epoch",
	"trigger_semantic_revision",
	"trigger_source_event_id",
	"trigger_source_admission_ordinal",
	"trigger_source_policy_revision",
	"trigger_capture_consent_epoch",
	"logical_tracking_id",
	"service_run_id",
	"collected_data_epoch",
	"retained_from_ms",
	"lineage_interval_start_ms",
	"lineage_interval_end_ms",
	"lineage_earliest_possible_start_ms",
	"lineage_revision_count",
	"lineage_effect_checksum",
	"revision_authority_set_checksum",
	"retained_at_ms",
	"reason",
	"effect_checksum",
)
private const val FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_TABLE =
	"pressure_fact_retention_revision_authority"
private val FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_COLUMNS = listOf(
	"writer_projection_id",
	"writer_projection_version",
	"logical_fact_id",
	"semantic_revision",
	"source_event_id",
	"source_admission_ordinal",
	"source_policy_revision",
	"capture_consent_epoch",
	"revision_effect_checksum",
	"parent_authority_checksum",
	"effect_checksum",
)
private val FINAL_V28_APPLIED_PLAN_COLUMNS = listOf(
	FinalV28ColumnSentinel(
		table = "source_applied_plan_state",
		name = "applied_payload_version",
		type = "INTEGER",
		notNull = false,
	),
	FinalV28ColumnSentinel(
		table = "source_applied_plan_state",
		name = "applied_payload",
		type = "BLOB",
		notNull = false,
	),
	FinalV28ColumnSentinel(
		table = "source_applied_plan_state",
		name = "applied_payload_checksum",
		type = "TEXT",
		notNull = false,
	),
)
private const val FINAL_V28_RETENTION_JOURNAL_TABLE = "collected_data_deletion_operation"
private val FINAL_V28_RETENTION_JOURNAL_COLUMNS = setOf(
	"retention_work_execution_id",
	"retention_destructive_plan",
	"settled_retained_from_ms",
	"source_maintenance_at_ms",
	"retention_active_sources",
)
private const val FINAL_V28_EXECUTION_RECEIPT_TABLE = "retention_work_execution_receipt"
private val FINAL_V28_EXECUTION_RECEIPT_COLUMNS = setOf(
	"execution_id",
	"work_request_id",
	"execution_generation",
	"worker_kind",
	"started_at_ms",
	"state",
	"destructive_plan",
	"updated_at_ms",
)
private const val FINAL_V28_INDEX_TABLE = "imported_wifi_deletion_generation"
private const val FINAL_V28_REQUIRED_INDEX = "idx_imported_wifi_deletion_scope"
private const val FINAL_V28_REQUIRED_INDEX_COLUMN = "deletion_scope_digest"
private const val FINAL_V28_RETENTION_TABLE = "ambient_steps_retention_authority"
private const val FINAL_V28_RETENTION_INDEX = "idx_ambient_steps_retention_scope"
private const val FINAL_V28_RETENTION_INDEX_COLUMN = "scope"
private const val FINAL_V28_CALLER_AUTHORITY_TABLE = "source_caller_accepted_authority"
private val FINAL_V28_CALLER_AUTHORITY_COLUMNS = setOf(
	"reference",
	"format_version",
	"origin",
	"accepted_purpose",
	"source_kind",
	"purpose",
	"policy_revision",
	"consent_epoch",
	"collected_data_epoch",
	"retained_from_ms",
	"rollout_revision",
	"execution_revision",
	"owner_cas_token",
	"logical_tracking_id",
	"manifest_revision",
	"status",
	"effect_checksum",
	"created_at_ms",
	"retired_at_ms",
	"retire_reason",
)
private const val FINAL_V28_CALLER_AUTHORITY_INDEX = "idx_source_caller_authority_status"
private const val FINAL_V28_CALLER_AUTHORITY_INDEX_COLUMN = "status"
private const val FINAL_V28_LOCATION_DECISION_TABLE = "location_observation_decision"
private val FINAL_V28_LOCATION_DECISION_COLUMNS = setOf(
	"writer_projection_id",
	"writer_projection_version",
	"writer_binding_generation",
	"writer_owner_generation",
)
private val FINAL_V29_CAPTURED_RADIO_RETENTION_EVENT_INDEX = FinalV28IndexSentinel(
	FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE,
	"idx_captured_radio_retention_event",
	listOf("source_kind", "source_event_id"),
	unique = false,
)
private val FINAL_V28_LIFECYCLE_PROOF_INDEXES = listOf(
	FinalV28IndexSentinel(
		"source_event_retention_proof",
		"idx_source_event_retention_proof_admission",
		listOf("source_kind", "source_admission_ordinal"),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_SOURCE_EVENT_RETENTION_SCOPE_PROOF_TABLE,
		"idx_source_event_retention_scope_run",
		listOf("source_kind", "logical_tracking_id", "service_run_id"),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE,
		"idx_captured_radio_retention_event",
		listOf("source_kind", "source_event_id"),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE,
		"idx_captured_radio_retention_floor",
		listOf("source_kind", "retained_from_ms"),
		unique = false,
	),
	FinalV28IndexSentinel(
		FINAL_V28_CAPTURED_RADIO_RETENTION_PROOF_TABLE,
		"idx_captured_radio_retention_run_admission",
		listOf("source_kind", "logical_tracking_id", "service_run_id",
			"collected_data_epoch", "source_admission_ordinal"),
		unique = false,
	),
	FinalV28IndexSentinel(
		"activity_captured_evidence",
		"idx_activity_captured_evidence_writer_admission",
		listOf("writer_projection_id", "writer_projection_version", "source_admission_ordinal"),
		unique = false,
	),
	FinalV28IndexSentinel(
		"pressure_fact_revision",
		"idx_pressure_fact_revision_writer_admission",
		listOf("writer_projection_id", "writer_projection_version", "source_admission_ordinal"),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE,
		"idx_pressure_retention_current_event",
		listOf("writer_projection_id", "writer_projection_version", "current_source_event_id"),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE,
		"idx_pressure_retention_current_admission",
		listOf(
			"writer_projection_id",
			"writer_projection_version",
			"current_source_admission_ordinal",
		),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE,
		"idx_pressure_retention_trigger_evidence",
		listOf("trigger_source_event_id", "trigger_source_admission_ordinal"),
		unique = false,
	),
	FinalV28IndexSentinel(
		FINAL_V28_PRESSURE_RETENTION_AUTHORITY_TABLE,
		"idx_pressure_retention_run_scope",
		listOf("service_run_id", "logical_tracking_id", "logical_fact_id"),
		unique = false,
	),
	FinalV28IndexSentinel(
		FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_TABLE,
		"idx_pressure_retention_revision_event",
		listOf("writer_projection_id", "writer_projection_version", "source_event_id"),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_PRESSURE_RETENTION_REVISION_AUTHORITY_TABLE,
		"idx_pressure_retention_revision_admission",
		listOf(
			"writer_projection_id",
			"writer_projection_version",
			"source_admission_ordinal",
		),
		unique = true,
	),
	FinalV28IndexSentinel(
		"wifi_captured_fact_revision",
		"idx_wifi_captured_fact_admission",
		listOf("writer_projection_id", "writer_projection_version", "source_admission_ordinal"),
		unique = false,
	),
	FinalV28IndexSentinel(
		"cell_captured_fact_revision",
		"idx_cell_captured_fact_admission",
		listOf("writer_projection_id", "writer_projection_version", "source_admission_ordinal"),
		unique = false,
	),
)
private val FINAL_V28_ORGANIZATION_COLUMNS = listOf(
	FinalV28ColumnSentinel("organization_annotation", "local_id", "TEXT", true, 1),
	FinalV28ColumnSentinel("organization_annotation", "revision", "INTEGER", true),
	FinalV28ColumnSentinel("organization_annotation", "created_at_ms", "INTEGER", true),
	FinalV28ColumnSentinel("organization_annotation", "updated_at_ms", "INTEGER", true),
	FinalV28ColumnSentinel("organization_annotation", "recording_adapter_key", "TEXT", false),
	FinalV28ColumnSentinel("organization_annotation", "start_time_ms", "INTEGER", false),
	FinalV28ColumnSentinel("organization_annotation", "end_time_ms", "INTEGER", false),
	FinalV28ColumnSentinel("organization_annotation", "display_zone_id", "TEXT", false),
	FinalV28ColumnSentinel("organization_annotation", "name", "TEXT", false),
	FinalV28ColumnSentinel("organization_annotation", "activity_label_id", "INTEGER", false),
	FinalV28ColumnSentinel("organization_annotation", "hidden", "INTEGER", true),
)
private val FINAL_V28_ORGANIZATION_INDEXES = listOf(
	FinalV28IndexSentinel("organization_annotation", "idx_organization_adapter", listOf("recording_adapter_key"), true),
	FinalV28IndexSentinel("organization_annotation", "idx_organization_bounds", listOf("hidden", "start_time_ms",
        "end_time_ms"), false),
	FinalV28IndexSentinel("organization_annotation", "idx_organization_end", listOf("end_time_ms"), false),
)
private val FINAL_V28_PRESSURE_RETENTION_TRIGGER = FinalV28TriggerSentinel(
	name = "reject_pressure_fact_retention_resurrection",
	table = "pressure_fact_revision",
)
private val FINAL_V28_AUTHORITY_INDEXES = listOf(
	FinalV28IndexSentinel(
		FINAL_V28_INDEX_TABLE,
		FINAL_V28_REQUIRED_INDEX,
		listOf(FINAL_V28_REQUIRED_INDEX_COLUMN),
		unique = true,
	),
	FinalV28IndexSentinel(
		FINAL_V28_RETENTION_TABLE,
		FINAL_V28_RETENTION_INDEX,
		listOf(FINAL_V28_RETENTION_INDEX_COLUMN),
		unique = false,
	),
	FinalV28IndexSentinel(
		FINAL_V28_CALLER_AUTHORITY_TABLE,
		FINAL_V28_CALLER_AUTHORITY_INDEX,
		listOf(FINAL_V28_CALLER_AUTHORITY_INDEX_COLUMN),
		unique = false,
	),
)
private val FINAL_V28_STEPS_TABLE_COLUMNS = mapOf(
	StepsCountDomainSchema.RECEIPT_TABLE to listOf(
		"receipt_identity",
		"domain_identity",
		"owner_kind",
		"scope_identity",
		"owner_identity",
		"owner_revision",
		"registration_generation",
		"collected_data_epoch",
		"authority_revision",
		"authority_fingerprint",
		"coverage_kind",
		"coverage_version",
		"count_domain_version",
		"effect_checksum",
		"completion_evidence_checksum",
	),
	StepsCountDomainSchema.OWNER_TABLE to listOf(
		"owner_kind",
		"scope_identity",
		"owner_identity",
		"owner_revision",
		"operation",
		"receipt_identity",
		"owner_effect_checksum",
		"linked_at_ms",
	),
	StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE to listOf(
		"owner_kind",
		"owner_identity",
		"owner_revision",
		"terminal_state",
		"last_admission_ordinal",
		"last_source_sequence",
		"provider_flush_outcome",
		"registration_removal_outcome",
		"registration_timeline_checksum",
		"evidence_checksum",
	),
	StepsCountDomainSchema.SCHEMA_MARKER_TABLE to listOf(
		"id",
		"contract_version",
		"token_semantics",
		"terminal_unproven",
	),
	"imported_steps_count_domain_binding" to listOf(
		"product_kind",
		"product_identity",
		"product_revision",
		"graph_identity",
		"source_schema_version",
		"source_receipt_identity",
		"source_archive_identity",
		"source_archive_content_checksum",
	),
	"imported_steps_file_receipt" to listOf(
		"import_job_id",
		"entry_key",
		"receipt_identity",
		"source_name",
		"received_at_ms",
		"archive_content_checksum",
		"entry_ordinal",
		"entry_identity",
		"graph_identity",
	),
	"imported_steps_full_clear_binding_stage" to listOf(
		"operation_id",
		"product_kind",
		"product_identity",
		"product_revision",
		"graph_identity",
		"source_schema_version",
		"source_receipt_identity",
		"source_archive_identity",
		"source_archive_content_checksum",
		"consumed",
	),
	"imported_steps_full_clear_session_product_stage" to listOf(
		"operation_id",
		"product_identity",
		"content_checksum",
		"source_format",
		"source_schema_version",
		"graph_identity",
		"source_receipt_identity",
		"source_archive_content_checksum",
		"has_stored_binding",
		"source_receipt_observed",
	),
	"imported_steps_full_clear_owner_stage" to listOf(
		"operation_id",
		"owner_kind",
		"owner_identity",
		"scope_identity",
		"container_identity",
		"root_product_identity",
		"product_kind",
		"bound_product_identity",
		"latest_graph_identity",
		"latest_owner_revision",
		"latest_graph_revision",
		"latest_is_bound",
		"latest_compare_product_identity",
		"explicit_lineage_length",
	),
	"imported_steps_full_clear_owner_revision_stage" to listOf(
		"operation_id",
		"owner_kind",
		"owner_identity",
		"owner_revision",
		"scope_identity",
		"operation",
		"receipt_identity",
		"owner_effect_checksum",
		"source_linked_at_ms",
		"legacy_ambient",
	),
	"imported_steps_full_clear_explicit_lineage_stage" to listOf(
		"operation_id",
		"owner_kind",
		"owner_identity",
		"lineage_ordinal",
		"owner_revision",
	),
)
private val FINAL_V28_STEPS_INDEXES = listOf(
	FinalV28IndexSentinel(
		"imported_steps_entry",
		"idx_imported_steps_entry_cursor",
		listOf("start_time_ms", "identity"),
		unique = false,
		descendingColumns = setOf(0, 1),
	),
	FinalV28IndexSentinel(
		"imported_steps_full_clear_binding_stage",
		"idx_imported_steps_full_clear_binding_graph",
		listOf("operation_id", "graph_identity", "consumed"),
		unique = false,
	),
	FinalV28IndexSentinel(
		"imported_steps_full_clear_explicit_lineage_stage",
		"idx_imported_steps_full_clear_explicit_revision",
		listOf("operation_id", "owner_kind", "owner_identity", "owner_revision"),
		unique = true,
	),
	FinalV28IndexSentinel(
		StepsCountDomainSchema.RECEIPT_TABLE,
		"idx_steps_count_domain_receipt_owner",
		listOf("owner_kind", "owner_identity", "owner_revision"),
		unique = true,
	),
	FinalV28IndexSentinel(
		StepsCountDomainSchema.RECEIPT_TABLE,
		"idx_steps_count_domain_receipt_compatibility",
		listOf("domain_identity", "collected_data_epoch", "count_domain_version"),
		unique = false,
	),
	FinalV28IndexSentinel(
		StepsCountDomainSchema.OWNER_TABLE,
		"idx_steps_count_domain_owner_scope",
		listOf("owner_kind", "scope_identity", "owner_identity", "owner_revision"),
		unique = false,
	),
	FinalV28IndexSentinel(
		StepsCountDomainSchema.OWNER_TABLE,
		"idx_steps_count_domain_owner_receipt",
		listOf("receipt_identity"),
		unique = false,
	),
	FinalV28IndexSentinel(
		StepsCountDomainSchema.OWNER_TABLE,
		"idx_steps_count_domain_owner_terminal_age",
		listOf("operation", "linked_at_ms", "owner_kind", "owner_identity"),
		unique = false,
	),
	FinalV28IndexSentinel(
		StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
		"idx_steps_count_domain_completeness_owner",
		listOf("owner_kind", "owner_identity", "owner_revision"),
		unique = true,
	),
)
private val FINAL_V28_STEPS_TRIGGERS = listOf(
	FinalV28TriggerSentinel(
		StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER,
		StepsCountDomainSchema.OWNER_TABLE,
	),
	FinalV28TriggerSentinel(
		StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER,
		"ambient_steps_fact_revision",
	),
	FinalV28TriggerSentinel(
		StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
		"ambient_steps_fact_revision",
	),
)

private val FINAL_V29_NATIVE_PORTABLE_COLUMNS = listOf(
	FinalV28ColumnSentinel("native_portable_ownership", "source_kind", "INTEGER", true, primaryKeyPosition = 1),
	FinalV28ColumnSentinel("native_portable_ownership", "identity_kind", "TEXT", true, primaryKeyPosition = 2),
	FinalV28ColumnSentinel("native_portable_ownership", "opaque_identity", "TEXT", true),
	FinalV28ColumnSentinel("native_portable_ownership", "raw_identity", "TEXT", true, primaryKeyPosition = 3),
	FinalV28ColumnSentinel("native_portable_ownership", "logical_tracking_id", "TEXT", true, primaryKeyPosition = 4),
	FinalV28ColumnSentinel("native_portable_ownership", "service_run_id", "TEXT", true, primaryKeyPosition = 5),
)
private val FINAL_V29_NATIVE_PORTABLE_INDEXES = listOf(
	FinalV28IndexSentinel("native_portable_ownership", "idx_native_portable_owner_identity", listOf("source_kind",
        "opaque_identity", "logical_tracking_id"), false),
	FinalV28IndexSentinel("native_portable_ownership", "idx_native_portable_owner_run", listOf("logical_tracking_id",
        "service_run_id"), false),
)
private val FINAL_V29_NATIVE_PORTABLE_TRIGGERS = listOf(
	FinalV28TriggerSentinel("prune_native_portable_2_window", "activity_captured_window_revision"),
	FinalV28TriggerSentinel("prune_native_portable_4_window", "pressure_fact_revision"),
	FinalV28TriggerSentinel("prune_native_portable_run_delete", "source_service_run"),
	FinalV28TriggerSentinel("prune_native_portable_run_update", "source_service_run"),
)

private val FINAL_V29_ADDITIONAL_COLUMNS = listOf(
	FinalV28ColumnSentinel("session_manifest_source", "steps_recording_manifest_revision", "INTEGER", false,
        defaultValue = "NULL"),
	FinalV28ColumnSentinel("wifi_captured_fact_revision", "payload_version", "INTEGER", false, defaultValue = "NULL"),
	FinalV28ColumnSentinel("ambient_wifi_fact_revision", "purpose_demand_id", "TEXT", false, defaultValue = "NULL"),
	FinalV28ColumnSentinel("ambient_cell_fact_revision", "purpose_demand_id", "TEXT", false, defaultValue = "NULL"),
	FinalV28ColumnSentinel("source_policy", "background_notification_host_enabled", "INTEGER", true, defaultValue =
        "0"),
	FinalV28ColumnSentinel("location_accepted_receipt", "retained_proof_version", "INTEGER", true, defaultValue = "0"),
	FinalV28ColumnSentinel("location_accepted_receipt", "retained_proof_checksum", "TEXT", true, defaultValue = "''"),
	FinalV28ColumnSentinel("location_sample_purpose_authority", "retained_proof_checksum", "TEXT", true,
        defaultValue = "''"),
	FinalV28ColumnSentinel("organization_annotation", "imported_source_selection", "TEXT", false),
	FinalV28ColumnSentinel("organization_annotation", "imported_activity_label", "TEXT", false),
	FinalV28ColumnSentinel("location_file_archive", "identity", "TEXT", true, primaryKeyPosition = 1),
	FinalV28ColumnSentinel("location_file_archive", "source_format", "TEXT", true),
	FinalV28ColumnSentinel("location_file_archive", "source_schema_version", "INTEGER", true),
	FinalV28ColumnSentinel("location_file_archive", "collected_data_epoch", "INTEGER", true),
	FinalV28ColumnSentinel("location_file_archive", "point_count", "INTEGER", true),
	FinalV28ColumnSentinel("location_file_archive_point", "archive_identity", "TEXT", true, primaryKeyPosition = 1),
	FinalV28ColumnSentinel("location_file_archive_point", "ordinal", "INTEGER", true, primaryKeyPosition = 2),
	FinalV28ColumnSentinel("location_file_archive_point", "portable_id", "TEXT", true),
	FinalV28ColumnSentinel("location_file_archive_point", "sample_id", "INTEGER", false),
)

private val FINAL_V29_LOCATION_ARCHIVE_COLUMNS = mapOf(
	"location_file_archive" to listOf("identity", "source_format", "source_schema_version", "collected_data_epoch",
        "point_count"),
	"location_file_archive_point" to listOf("archive_identity", "ordinal", "portable_id", "sample_id"),
)
private val FINAL_V29_LOCATION_ARCHIVE_INDEXES = listOf(
	FinalV28IndexSentinel("location_file_archive_point", "idx_location_archive_point_identity",
        listOf("archive_identity", "portable_id"), true),
	FinalV28IndexSentinel("location_file_archive_point", "idx_location_archive_point_sample", listOf("sample_id"),
        false),
)

private val FINAL_V29_LEGACY_STAMP_COLUMNS = listOf(
	"source_time_ms", "source_elapsed_realtime_nanos", "source_first_elapsed_realtime_nanos",
	"received_time_ms", "received_elapsed_realtime_nanos", "source_sequence",
	"source_first_sequence", "clock_domain_id", "boot_clock_domain_id", "source_age_ms",
	"time_uncertainty_ms", "capability_flags", "permission_precision",
)
private val FINAL_V29_SESSION_CHILD_KEY = listOf(
	"writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision", "item_index",
)
private val FINAL_V29_BACKGROUND_CHILD_KEY = listOf(
	"writer_id", "writer_version", "logical_fact_id", "semantic_revision", "item_index",
)
private val FINAL_V29_RADIO_CHILD_CLOCK = listOf(
	"provider_timestamp_nanos", "observed_wall_time_ms", "wall_time_uncertainty_ms",
)
private val FINAL_V29_RADIO_CHILD_COLUMNS = mapOf(
	"wifi_captured_child" to FINAL_V29_SESSION_CHILD_KEY + FINAL_V29_RADIO_CHILD_CLOCK +
		listOf("bssid", "ssid", "frequency_mhz", "signal_dbm", "content_checksum"),
	"cell_captured_child" to FINAL_V29_SESSION_CHILD_KEY + FINAL_V29_RADIO_CHILD_CLOCK +
		listOf("tower_identity", "radio_type", "registered", "signal_dbm", "content_checksum"),
	"ambient_wifi_child" to FINAL_V29_BACKGROUND_CHILD_KEY + FINAL_V29_RADIO_CHILD_CLOCK +
		listOf("bssid", "ssid", "frequency_mhz", "signal_dbm", "content_checksum"),
	"ambient_cell_child" to FINAL_V29_BACKGROUND_CHILD_KEY + FINAL_V29_RADIO_CHILD_CLOCK +
		listOf("tower_identity", "radio_type", "registered", "signal_dbm", "content_checksum"),
	"legacy_wifi_child" to listOf(
		"legacy_row_id", "time_ms", "bssid", "ssid", "capabilities", "frequency", "level",
		"lat_e7", "lon_e7", "provenance", "created_at", "source_signal_id", "source_item_index",
	) + FINAL_V29_LEGACY_STAMP_COLUMNS,
	"legacy_cell_child" to listOf(
		"legacy_row_id", "time_ms", "cell_id", "lac", "mcc", "mnc", "network_type",
		"signal_strength", "lat_e7", "lon_e7", "provenance", "created_at",
		"source_signal_id", "source_item_index",
	) + FINAL_V29_LEGACY_STAMP_COLUMNS,
)
private val FINAL_V29_RADIO_CHILD_INDEXES = listOf(
	FinalV28IndexSentinel("wifi_captured_child", "idx_wifi_captured_child_time", listOf("observed_wall_time_ms"),
        false),
	FinalV28IndexSentinel("wifi_captured_child", "idx_wifi_captured_child_identity_time", listOf("bssid",
        "observed_wall_time_ms"), false),
	FinalV28IndexSentinel("cell_captured_child", "idx_cell_captured_child_time", listOf("observed_wall_time_ms"),
        false),
	FinalV28IndexSentinel("cell_captured_child", "idx_cell_captured_child_identity_time", listOf("tower_identity",
        "observed_wall_time_ms"), false),
	FinalV28IndexSentinel("ambient_wifi_child", "idx_ambient_wifi_child_time", listOf("observed_wall_time_ms"), false),
	FinalV28IndexSentinel("ambient_wifi_child", "idx_ambient_wifi_child_identity_time", listOf("bssid",
        "observed_wall_time_ms"), false),
	FinalV28IndexSentinel("ambient_cell_child", "idx_ambient_cell_child_time", listOf("observed_wall_time_ms"), false),
	FinalV28IndexSentinel("ambient_cell_child", "idx_ambient_cell_child_identity_time", listOf("tower_identity",
        "observed_wall_time_ms"), false),
	FinalV28IndexSentinel("legacy_wifi_child", "idx_legacy_wifi_child_time", listOf("time_ms"), false),
	FinalV28IndexSentinel("legacy_cell_child", "idx_legacy_cell_child_time", listOf("time_ms"), false),
)
private val FINAL_V29_RADIO_MIRROR_TRIGGERS = listOf(
	FinalV28TriggerSentinel("mirror_legacy_wifi_child_insert", "wifi_observation"),
	FinalV28TriggerSentinel("mirror_legacy_wifi_child_update", "wifi_observation"),
	FinalV28TriggerSentinel("mirror_legacy_wifi_child_delete", "wifi_observation"),
	FinalV28TriggerSentinel("mirror_legacy_cell_child_insert", "cell_sample"),
	FinalV28TriggerSentinel("mirror_legacy_cell_child_update", "cell_sample"),
	FinalV28TriggerSentinel("mirror_legacy_cell_child_delete", "cell_sample"),
)

private data class FinalV28IndexSentinel(
	val table: String,
	val name: String,
	val columns: List<String>,
	val unique: Boolean,
	val descendingColumns: Set<Int> = emptySet(),
)

private data class FinalV28ColumnSentinel(
	val table: String,
	val name: String,
	val type: String,
	val notNull: Boolean,
	val primaryKeyPosition: Int = 0,
	val defaultValue: String? = null,
)

private data class FinalV28IndexedColumn(
	val name: String,
	val descending: Boolean,
)

private data class FinalV28TriggerSentinel(
	val name: String,
	val table: String,
)

private val FINAL_V29_LEGACY_PRESERVED_COLUMNS = listOf(
	FinalV28ColumnSentinel("location_sample", "legacy_lat", "REAL", false),
	FinalV28ColumnSentinel("location_sample", "legacy_lon", "REAL", false),
	FinalV28ColumnSentinel("location_sample", "legacy_alt_m", "REAL", false),
	FinalV28ColumnSentinel("wifi_observation", "legacy_first_seen_ms", "INTEGER", false),
	FinalV28ColumnSentinel("wifi_observation", "legacy_alt_m", "REAL", false),
	FinalV28ColumnSentinel("wifi_observation", "legacy_lat", "REAL", false),
	FinalV28ColumnSentinel("wifi_observation", "legacy_lon", "REAL", false),
	FinalV28ColumnSentinel("cell_sample", "legacy_source_id", "INTEGER", false),
	FinalV28ColumnSentinel("cell_sample", "legacy_mcc", "TEXT", false),
	FinalV28ColumnSentinel("cell_sample", "legacy_mnc", "TEXT", false),
	FinalV28ColumnSentinel("cell_sample", "legacy_alt_m", "REAL", false),
	FinalV28ColumnSentinel("cell_sample", "legacy_lat", "REAL", false),
	FinalV28ColumnSentinel("cell_sample", "legacy_lon", "REAL", false),
	FinalV28ColumnSentinel("session_segment", "legacy_user_initiated", "INTEGER", false),
	FinalV28ColumnSentinel("session_segment", "legacy_distance_on_foot_m", "REAL", false),
	FinalV28ColumnSentinel("session_segment", "legacy_distance_in_vehicle_m", "REAL", false),
	FinalV28ColumnSentinel("session_segment", "legacy_activity_id", "INTEGER", false),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "id", "INTEGER", true, 1),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "start", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "end", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "user_initiated", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "collections", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "distance", "REAL", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "distance_on_foot", "REAL", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "distance_in_vehicle", "REAL", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "steps", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_rejected_tracker_session", "session_activity_id", "INTEGER", false),
	FinalV28ColumnSentinel("legacy_location_wifi_count", "id", "INTEGER", true, 1),
	FinalV28ColumnSentinel("legacy_location_wifi_count", "time", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_location_wifi_count", "count", "INTEGER", true),
	FinalV28ColumnSentinel("legacy_location_wifi_count", "lat", "REAL", true),
	FinalV28ColumnSentinel("legacy_location_wifi_count", "lon", "REAL", true),
	FinalV28ColumnSentinel("legacy_location_wifi_count", "alt", "REAL", false),
	FinalV28ColumnSentinel("network_operator", "mcc", "TEXT", true, 1),
	FinalV28ColumnSentinel("network_operator", "mnc", "TEXT", true, 2),
	FinalV28ColumnSentinel("network_operator", "name", "TEXT", false),
)
private val FINAL_V29_LEGACY_PRESERVED_TABLE_COLUMNS = mapOf(
	"legacy_rejected_tracker_session" to listOf("id", "start", "end", "user_initiated", "collections", "distance",
        "distance_on_foot", "distance_in_vehicle", "steps", "session_activity_id"),
	"legacy_location_wifi_count" to listOf("id", "time", "count", "lat", "lon", "alt"),
	"network_operator" to listOf("mcc", "mnc", "name"),
)
private val FINAL_V29_LEGACY_PRESERVED_INDEXES = listOf(
	FinalV28IndexSentinel("legacy_location_wifi_count", "idx_legacy_location_wifi_count_time", listOf("time"), false),
	FinalV28IndexSentinel("legacy_location_wifi_count", "idx_legacy_location_wifi_count_coords", listOf("lat", "lon"),
        false),
)
