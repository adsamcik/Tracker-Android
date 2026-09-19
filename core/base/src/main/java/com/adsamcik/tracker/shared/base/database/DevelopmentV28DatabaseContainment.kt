package com.adsamcik.tracker.shared.base.database

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import java.io.File
import java.io.IOException

/**
 * Pre-open classification for the stable active database file.
 *
 * Version 28 is an unshipped development schema. A same-version database is admitted only when it
 * carries this assembly's explicit marker and a small set of indispensable final schema sentinels.
 */
sealed interface ActiveDatabasePreflightResult {
	data object Fresh : ActiveDatabasePreflightResult
	data object ReleasedV27 : ActiveDatabasePreflightResult
	data object FinalV28 : ActiveDatabasePreflightResult
	data class Retryable(val reason: ActiveDatabaseRetryableReason) : ActiveDatabasePreflightResult
	data class Blocked(val reason: ActiveDatabaseBlockReason) : ActiveDatabasePreflightResult
}

enum class ActiveDatabaseRetryableReason(val failureCode: String) {
	CONTENDED("ACTIVE_DATABASE_CONTENDED"),
	OPERATIONALLY_UNAVAILABLE("ACTIVE_DATABASE_OPERATIONALLY_UNAVAILABLE"),
}

enum class ActiveDatabaseBlockReason(val failureCode: String) {
	STALE_DEVELOPMENT_V28("STALE_DEVELOPMENT_V28"),
	UNSUPPORTED_DATABASE_VERSION("UNSUPPORTED_DATABASE_VERSION"),
	UNRECOGNIZED_DATABASE_SCHEMA("UNRECOGNIZED_DATABASE_SCHEMA"),
	INVALID_FINAL_V28_MARKER("INVALID_FINAL_V28_MARKER"),
	INCOMPLETE_FINAL_V28_SCHEMA("INCOMPLETE_FINAL_V28_SCHEMA"),
	UNREADABLE_DATABASE("UNREADABLE_DATABASE"),
	RELEASED_V27_MIGRATION_VALIDATION_FAILED("RELEASED_V27_MIGRATION_VALIDATION_FAILED"),
	FINAL_V28_OPEN_VALIDATION_FAILED("FINAL_V28_OPEN_VALIDATION_FAILED"),
}

class ActiveDatabaseOpenBlockedException(
	val reason: ActiveDatabaseBlockReason,
	cause: Throwable? = null,
) : IllegalStateException(reason.failureCode, cause)

class ActiveDatabaseOpenRetryableException(
	val reason: ActiveDatabaseRetryableReason,
	cause: Throwable? = null,
) : IllegalStateException(reason.failureCode, cause)

class ActiveDatabasePreflight internal constructor(
	private val databaseFile: File,
) {
	constructor(
		context: Context,
		databaseName: String = ACTIVE_DATABASE_NAME,
	) : this(context.applicationContext.getDatabasePath(databaseName))

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
	} catch (failure: Exception) {
		val retryableReason = failure.activeDatabaseRetryableReason()
		when {
			failure.isDatabaseCorruption() -> ActiveDatabasePreflightResult.Blocked(
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
		val version = database.version
		if (version == 0 && !database.hasApplicationTables()) {
			return ActiveDatabasePreflightResult.Fresh
		}
		if (version != LAST_RELEASED_ACTIVE_DATABASE_VERSION &&
			version != CURRENT_DATABASE_VERSION
		) {
			return ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.UNSUPPORTED_DATABASE_VERSION,
			)
		}
		if (!BASELINE_TABLES.all(database::hasTable)) {
			return ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.UNRECOGNIZED_DATABASE_SCHEMA,
			)
		}
		if (version == LAST_RELEASED_ACTIVE_DATABASE_VERSION) {
			return ActiveDatabasePreflightResult.ReleasedV27
		}
		val markerState = try {
			database.finalV28MarkerState()
		} catch (failure: Exception) {
			if (failure.isDatabaseCorruption()) {
				return ActiveDatabasePreflightResult.Blocked(
					ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
				)
			}
			failure.activeDatabaseRetryableReason()?.let {
				return ActiveDatabasePreflightResult.Retryable(it)
			}
			if (failure is SQLiteException) {
				return ActiveDatabasePreflightResult.Blocked(
					ActiveDatabaseBlockReason.INVALID_FINAL_V28_MARKER,
				)
			}
			throw failure
		}
		when (markerState) {
			FinalV28MarkerState.MISSING ->
				return ActiveDatabasePreflightResult.Blocked(
					ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
				)
			FinalV28MarkerState.STALE ->
				return ActiveDatabasePreflightResult.Blocked(
					ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
				)
			FinalV28MarkerState.INVALID ->
				return ActiveDatabasePreflightResult.Blocked(
					ActiveDatabaseBlockReason.INVALID_FINAL_V28_MARKER,
				)
			FinalV28MarkerState.VALID -> Unit
		}
		if (!FINAL_V28_REQUIRED_TABLES.all(database::hasTable) ||
			!database.hasColumn(FINAL_V28_REQUIRED_COLUMN_TABLE, FINAL_V28_REQUIRED_COLUMN) ||
			!database.hasColumn(
				FINAL_V28_RADIO_RECEIPT_TABLE,
				FINAL_V28_RADIO_RECEIPT_COLUMN,
			) ||
			!FINAL_V28_RETENTION_JOURNAL_COLUMNS.all { column ->
				database.hasColumn(FINAL_V28_RETENTION_JOURNAL_TABLE, column)
			} ||
			!FINAL_V28_EXECUTION_RECEIPT_COLUMNS.all { column ->
				database.hasColumn(FINAL_V28_EXECUTION_RECEIPT_TABLE, column)
			} ||
			!FINAL_V28_CALLER_AUTHORITY_COLUMNS.all { column ->
				database.hasColumn(FINAL_V28_CALLER_AUTHORITY_TABLE, column)
			} ||
			!database.hasSingleColumnIndex(
				table = FINAL_V28_INDEX_TABLE,
				index = FINAL_V28_REQUIRED_INDEX,
				column = FINAL_V28_REQUIRED_INDEX_COLUMN,
			) ||
			!database.hasSingleColumnIndex(
				table = FINAL_V28_RETENTION_TABLE,
				index = FINAL_V28_RETENTION_INDEX,
				column = FINAL_V28_RETENTION_INDEX_COLUMN,
			) ||
			!database.hasSingleColumnIndex(
				table = FINAL_V28_CALLER_AUTHORITY_TABLE,
				index = FINAL_V28_CALLER_AUTHORITY_INDEX,
				column = FINAL_V28_CALLER_AUTHORITY_INDEX_COLUMN,
			)
		) {
			return ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
			)
		}
		return ActiveDatabasePreflightResult.FinalV28
	}
}

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
			val state = admittedState ?: inspect().also { result ->
				if (result is ActiveDatabasePreflightResult.Blocked) {
					throw ActiveDatabaseOpenBlockedException(result.reason)
				}
				if (result is ActiveDatabasePreflightResult.Retryable) {
					throw ActiveDatabaseOpenRetryableException(result.reason)
				}
			}
			return try {
				block().also { admittedState = state }
			} catch (blocked: ActiveDatabaseOpenBlockedException) {
				throw blocked
			} catch (retryable: ActiveDatabaseOpenRetryableException) {
				throw retryable
			} catch (failure: Exception) {
				if (failure.isDatabaseCorruption()) {
					throw ActiveDatabaseOpenBlockedException(
						ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
						failure,
					)
				}
				failure.activeDatabaseRetryableReason()?.let { reason ->
					throw ActiveDatabaseOpenRetryableException(reason, failure)
				}
				if (failure is DatabaseMigrationBackupException) throw failure
				val reason = when (state) {
					ActiveDatabasePreflightResult.ReleasedV27 ->
						ActiveDatabaseBlockReason.RELEASED_V27_MIGRATION_VALIDATION_FAILED
					ActiveDatabasePreflightResult.FinalV28 ->
						ActiveDatabaseBlockReason.FINAL_V28_OPEN_VALIDATION_FAILED
					else -> null
				}
				if (reason != null) throw ActiveDatabaseOpenBlockedException(reason, failure)
				throw failure
			}
		}
	}
}

internal fun Throwable.activeDatabaseRetryableReason(): ActiveDatabaseRetryableReason? {
	var current: Throwable? = this
	while (current != null) {
		if (current is SQLiteDatabaseLockedException) {
			return ActiveDatabaseRetryableReason.CONTENDED
		}
		val className = current.javaClass.name
		if (className in LOCK_EXCEPTION_CLASS_NAMES ||
			className.endsWith("SQLiteDatabaseLockedException")
		) {
			return ActiveDatabaseRetryableReason.CONTENDED
		}
		val message = current.message?.lowercase().orEmpty()
		val sqliteFailure = current is SQLiteException ||
			(className.startsWith("org.sqlite.database.sqlite.SQLite") &&
				className.endsWith("Exception"))
		if (sqliteFailure && LOCK_MESSAGE_MARKERS.any(message::contains)) {
			return ActiveDatabaseRetryableReason.CONTENDED
		}
		if (current is SQLiteCantOpenDatabaseException ||
			current is SQLiteDiskIOException ||
			current is IOException ||
			current is SecurityException ||
			className in OPERATIONAL_EXCEPTION_CLASS_NAMES ||
			(sqliteFailure && OPERATIONAL_MESSAGE_MARKERS.any(message::contains))
		) {
			return ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE
		}
		current = current.cause
	}
	return null
}

private fun Throwable.isDatabaseCorruption(): Boolean {
	var current: Throwable? = this
	while (current != null) {
		val className = current.javaClass.name
		if (className in CORRUPTION_EXCEPTION_CLASS_NAMES ||
			className.endsWith("SQLiteDatabaseCorruptException")
		) {
			return true
		}
		val message = current.message?.lowercase().orEmpty()
		val sqliteFailure = current is SQLiteException ||
			(className.startsWith("org.sqlite.database.sqlite.SQLite") &&
				className.endsWith("Exception"))
		if (sqliteFailure && CORRUPTION_MESSAGE_MARKERS.any(message::contains)) return true
		current = current.cause
	}
	return false
}

internal object FinalV28SchemaAssemblyRoomCallback : RoomDatabase.Callback() {
	override fun onCreate(db: SupportSQLiteDatabase) {
		createFinalV28SchemaAssemblyMarker(db)
	}
}

internal fun createFinalV28SchemaAssemblyMarker(database: SupportSQLiteDatabase) {
	database.execSQL(
		"INSERT INTO room_master_table(id, identity_hash) VALUES (?, ?)",
		arrayOf(FINAL_V28_MARKER_ID, FINAL_V28_ASSEMBLY_ID),
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
			if (cursor.getString(nameColumn) == column) return@use true
		}
		false
	}

private fun SQLiteDatabase.hasSingleColumnIndex(
	table: String,
	index: String,
	column: String,
): Boolean {
	val belongsToTable = rawQuery(
		"SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ? AND tbl_name = ? LIMIT 1",
		arrayOf(index, table),
	).use { it.moveToFirst() }
	if (!belongsToTable) return false
	return rawQuery("PRAGMA index_info(`$index`)", null).use { cursor ->
		val nameColumn = cursor.getColumnIndexOrThrow("name")
		cursor.moveToFirst() &&
			cursor.getString(nameColumn) == column &&
			!cursor.moveToNext()
	}
}

private fun SQLiteDatabase.finalV28MarkerState(): FinalV28MarkerState = rawQuery(
	"SELECT identity_hash FROM room_master_table WHERE id = ?",
	arrayOf(FINAL_V28_MARKER_ID.toString()),
).use { cursor ->
	if (!cursor.moveToFirst()) return@use FinalV28MarkerState.MISSING
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

internal const val FINAL_V28_MARKER_ID = -280_917
internal const val FINAL_V28_ASSEMBLY_ID = "tracker-v28-retention-caller-authority-20260919"
private val STALE_FINAL_V28_ASSEMBLY_IDS = setOf(
	"tracker-v28-final-20260917",
	"tracker-v28-retention-final-20260917",
	"tracker-v28-retention-integrity-20260918",
	"tracker-v28-retention-journal-20260919",
	"tracker-v28-retention-execution-20260919",
)

private val BASELINE_TABLES = setOf(
	"room_master_table",
	"activity",
	"tracker_run",
	"pending_signal",
)
private val FINAL_V28_REQUIRED_TABLES = setOf(
	"imported_wifi_deletion_generation",
	"ambient_steps_retention_authority",
	"ambient_steps_native_replay_footprint",
	"collected_data_deletion_operation",
	"retention_work_execution_receipt",
	FINAL_V28_CALLER_AUTHORITY_TABLE,
)
private const val FINAL_V28_REQUIRED_COLUMN_TABLE = "pending_signal"
private const val FINAL_V28_REQUIRED_COLUMN = "pressure_writer_owner_generation"
private const val FINAL_V28_RADIO_RECEIPT_TABLE = "cell_captured_entry_deletion_receipt"
private const val FINAL_V28_RADIO_RECEIPT_COLUMN = "retained_from_ms"
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

private val LOCK_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteBusyException",
	"android.database.sqlite.SQLiteDatabaseLockedException",
	"org.sqlite.database.sqlite.SQLiteDatabaseLockedException",
	"org.sqlite.database.sqlite.SQLiteBusyException",
)
private val LOCK_MESSAGE_MARKERS = listOf(
	"database is locked",
	"database table is locked",
	"sqlite_busy",
	"sqlite_locked",
)
private val OPERATIONAL_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteCantOpenDatabaseException",
	"android.database.sqlite.SQLiteDiskIOException",
	"android.database.sqlite.SQLiteFullException",
	"android.database.sqlite.SQLiteReadOnlyDatabaseException",
	"org.sqlite.database.sqlite.SQLiteCantOpenDatabaseException",
	"org.sqlite.database.sqlite.SQLiteDiskIOException",
	"org.sqlite.database.sqlite.SQLiteFullException",
	"org.sqlite.database.sqlite.SQLiteReadOnlyDatabaseException",
)
private val OPERATIONAL_MESSAGE_MARKERS = listOf(
	"unable to open database file",
	"disk i/o error",
	"permission denied",
	"readonly database",
	"read-only database",
	"temporarily unavailable",
)
private val CORRUPTION_EXCEPTION_CLASS_NAMES = setOf(
	"android.database.sqlite.SQLiteDatabaseCorruptException",
	"org.sqlite.database.sqlite.SQLiteDatabaseCorruptException",
)
private val CORRUPTION_MESSAGE_MARKERS = listOf(
	"database disk image is malformed",
	"file is not a database",
	"not a database",
	"malformed database schema",
	"database corrupt",
	"database corruption",
)
