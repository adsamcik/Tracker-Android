package com.adsamcik.tracker.shared.base.database.legacy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val LEGACY_DATABASE_NAME = "main_database"
const val ACTIVE_DATABASE_NAME = "main_database_v27"

enum class LegacyImportStatus {
	NOT_STARTED,
	RUNNING,
	COMPLETE,
	FAILED,
}

data class LegacyDatabaseInfo(
	val file: File,
	val sourceVersion: Int,
	val sizeBytes: Long,
)

data class LegacyImportReport(
	val sourceVersion: Int,
	val importedRows: Map<String, Long>,
	val skippedRows: Map<String, Long>,
	val completedAtMs: Long? = null,
)

data class LegacyDatabaseState(
	val database: LegacyDatabaseInfo?,
	val importStatus: LegacyImportStatus,
	val report: LegacyImportReport?,
	val lastError: String?,
	val externallyExported: Boolean,
) {
	val canDelete: Boolean
		get() = database != null &&
			(importStatus == LegacyImportStatus.COMPLETE || externallyExported)
}

class LegacyDatabaseException(
	message: String,
	cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Owns the released database file after v27 moves the active Room database to a new filename.
 *
 * The legacy file is never passed to the current Room schema. The only write performed before
 * reading/exporting it is SQLite's own WAL checkpoint/journal normalization, which changes no
 * logical rows or schema.
 */
class LegacyDatabaseRepository(
	context: Context,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) {
	private val appContext = context.applicationContext
	private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val databaseFile: File
		get() = appContext.getDatabasePath(LEGACY_DATABASE_NAME)

	val states: Flow<LegacyDatabaseState> = generation
		.map { currentState() }
		.distinctUntilChanged()

	fun currentState(): LegacyDatabaseState = PROCESS_LOCK.withLock {
		LegacyDatabaseState(
			// State observation must remain available for recovery UI even when SQLite cannot
			// inspect a corrupt source. Strict callers still use inspect()/prepareForRead().
			database = try {
				inspect()
			} catch (_: LegacyDatabaseException) {
				databaseFile.takeIf(File::isFile)?.let { file ->
					LegacyDatabaseInfo(
						file = file,
						sourceVersion = preferences.getInt(KEY_SOURCE_VERSION, 0),
						sizeBytes = databaseFamily(file)
							.sumOf { it.takeIf(File::isFile)?.length() ?: 0L },
					)
				}
			},
			importStatus = preferences.getString(KEY_STATUS, null)
				?.let { runCatching { LegacyImportStatus.valueOf(it) }.getOrNull() }
				?: LegacyImportStatus.NOT_STARTED,
			report = readReport(),
			lastError = preferences.getString(KEY_LAST_ERROR, null),
			externallyExported = preferences.getBoolean(KEY_EXPORTED, false),
		)
	}

	fun inspect(): LegacyDatabaseInfo? = PROCESS_LOCK.withLock {
		val file = databaseFile
		if (!file.isFile) return@withLock null
		try {
			LegacyDatabaseInfo(
				file = file,
				sourceVersion = readVersion(file),
				sizeBytes = databaseFamily(file).sumOf { it.takeIf(File::isFile)?.length() ?: 0L },
			)
		} catch (error: android.database.sqlite.SQLiteException) {
			throw LegacyDatabaseException("The legacy database could not be opened", error)
		}
	}

	/** Checkpoints WAL and validates that the legacy file is a portable, healthy SQLite database. */
	fun prepareForRead(): LegacyDatabaseInfo = PROCESS_LOCK.withLock {
		val file = databaseFile
		if (!file.isFile) throw LegacyDatabaseException("No legacy database exists")
		try {
			SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
				val version = database.version
				if (version <= 0) throw LegacyDatabaseException("The legacy database has no schema version")
				database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
					if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
						throw LegacyDatabaseException("The legacy database WAL could not be checkpointed")
					}
				}
				database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
					if (!cursor.moveToFirst() ||
						!cursor.getString(0).equals("delete", ignoreCase = true)
					) {
						throw LegacyDatabaseException("The legacy database could not be made portable")
					}
				}
				checkIntegrity(database)
			}
		} catch (error: LegacyDatabaseException) {
			throw error
		} catch (error: android.database.sqlite.SQLiteException) {
			throw LegacyDatabaseException("The legacy database failed validation", error)
		}
		checkNotNull(inspect())
	}

	/**
	 * Copies a validated portable vault to [output]. This method owns and closes the destination so
	 * deletion is authorized only after the destination reports a successful close.
	 */
	fun export(output: OutputStream): LegacyDatabaseInfo {
		return PROCESS_LOCK.withLock {
			val info = prepareForRead()
			try {
				output.use { destination ->
					info.file.inputStream().use { input -> input.copyTo(destination) }
				}
			} catch (error: IOException) {
				throw LegacyDatabaseException("The legacy database could not be exported", error)
			}
			preferences.edit { putBoolean(KEY_EXPORTED, true) }
			notifyChanged()
			info
		}
	}

	/** Deletes only the legacy database family, and only after import or external export. */
	fun delete(): Long = PROCESS_LOCK.withLock {
		val state = currentState()
		if (!state.canDelete) {
			throw LegacyDatabaseException(
				"The legacy database must be imported or exported before it can be deleted",
			)
		}
		val files = databaseFamily(databaseFile)
		val bytes = files.sumOf { it.takeIf(File::isFile)?.length() ?: 0L }
		// Remove expendable sidecars first so a failure cannot strand only WAL bytes after the
		// authoritative main file has already disappeared.
		(files.drop(1) + files.first()).forEach { file ->
			if (file.exists() && !file.delete()) {
				throw LegacyDatabaseException("Could not delete legacy database file ${file.name}")
			}
		}
		preferences.edit { clear() }
		notifyChanged()
		bytes
	}

	internal fun markRunning(sourceVersion: Int) = PROCESS_LOCK.withLock {
		preferences.edit {
			putString(KEY_STATUS, LegacyImportStatus.RUNNING.name)
			putInt(KEY_SOURCE_VERSION, sourceVersion)
			remove(KEY_LAST_ERROR)
		}
		notifyChanged()
	}

	internal fun markCopied(report: LegacyImportReport) = PROCESS_LOCK.withLock {
		writeReport(report)
		preferences.edit {
			putString(KEY_STATUS, LegacyImportStatus.RUNNING.name)
			remove(KEY_LAST_ERROR)
		}
		notifyChanged()
	}

	fun markComplete() = PROCESS_LOCK.withLock {
		val report = readReport()?.copy(completedAtMs = nowMillis())
		if (report != null) writeReport(report)
		preferences.edit {
			putString(KEY_STATUS, LegacyImportStatus.COMPLETE.name)
			remove(KEY_LAST_ERROR)
		}
		notifyChanged()
	}

	fun markFailed(error: Throwable) = PROCESS_LOCK.withLock {
		preferences.edit {
			putString(KEY_STATUS, LegacyImportStatus.FAILED.name)
			putString(KEY_LAST_ERROR, error.message ?: error::class.java.simpleName)
		}
		notifyChanged()
	}

	fun resetForRetry() = PROCESS_LOCK.withLock {
		preferences.edit {
			putString(KEY_STATUS, LegacyImportStatus.NOT_STARTED.name)
			remove(KEY_LAST_ERROR)
		}
		notifyChanged()
	}

	/** Keeps every operation touching the legacy database family mutually exclusive in-process. */
	internal fun <T> withFileFamilyLock(operation: () -> T): T = PROCESS_LOCK.withLock(operation)

	private fun readReport(): LegacyImportReport? {
		val sourceVersion = preferences.getInt(KEY_SOURCE_VERSION, 0)
		if (sourceVersion <= 0) return null
		return LegacyImportReport(
			sourceVersion = sourceVersion,
			importedRows = decodeCounts(preferences.getString(KEY_IMPORTED_COUNTS, null)),
			skippedRows = decodeCounts(preferences.getString(KEY_SKIPPED_COUNTS, null)),
			completedAtMs = preferences.getLong(KEY_COMPLETED_AT, 0L).takeIf { it > 0L },
		)
	}

	private fun writeReport(report: LegacyImportReport) {
		preferences.edit {
			putInt(KEY_SOURCE_VERSION, report.sourceVersion)
			putString(KEY_IMPORTED_COUNTS, encodeCounts(report.importedRows))
			putString(KEY_SKIPPED_COUNTS, encodeCounts(report.skippedRows))
			if (report.completedAtMs == null) remove(KEY_COMPLETED_AT)
			else putLong(KEY_COMPLETED_AT, report.completedAtMs)
		}
	}

	private fun readVersion(file: File): Int = SQLiteDatabase.openDatabase(
		file.path,
		null,
		SQLiteDatabase.OPEN_READONLY,
	).use(SQLiteDatabase::getVersion)

	private fun checkIntegrity(database: SQLiteDatabase) {
		database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
			if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
				throw LegacyDatabaseException("The legacy database integrity check failed")
			}
	}
	}

	private fun notifyChanged() {
		generation.value += 1L
	}

	private companion object {
		const val PREFERENCES_NAME = "legacy_database_v27"
		const val KEY_STATUS = "status"
		const val KEY_SOURCE_VERSION = "source_version"
		const val KEY_IMPORTED_COUNTS = "imported_counts"
		const val KEY_SKIPPED_COUNTS = "skipped_counts"
		const val KEY_COMPLETED_AT = "completed_at"
		const val KEY_LAST_ERROR = "last_error"
		const val KEY_EXPORTED = "exported"
		val PROCESS_LOCK = ReentrantLock()
		val generation = MutableStateFlow(0L)

		fun databaseFamily(file: File): List<File> = listOf(
			file,
			File(file.path + "-wal"),
			File(file.path + "-shm"),
			File(file.path + "-journal"),
		)

		fun encodeCounts(counts: Map<String, Long>): String = counts.entries
			.sortedBy(Map.Entry<String, Long>::key)
			.joinToString(separator = ";") { (table, count) -> "$table=$count" }

		fun decodeCounts(encoded: String?): Map<String, Long> = encoded
			.orEmpty()
			.split(';')
			.mapNotNull { entry ->
				val separator = entry.lastIndexOf('=')
				if (separator <= 0) return@mapNotNull null
				val count = entry.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
				entry.substring(0, separator) to count
			}
			.toMap()
	}
}
