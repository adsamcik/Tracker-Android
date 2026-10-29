package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLite
import androidx.annotation.Keep
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

/**
 * Exports the explicitly scoped durable data stores and required imported files as one ZIP.
 * Preferences, schedules, caches and diagnostics are not part of a data backup.
 *
 * Every database is checkpointed and locked before copying begins, so the complete database set
 * represents one saved-data instant. Imported data files are then copied with their own checksums.
 */
@Keep // Export activity reconstructs the selected class through its public no-argument constructor.
class DatabaseExporter private constructor(
	private val copyLockProvider: DatabaseCopyLockProvider,
) : Exporter {
	constructor() : this(SQLiteDatabaseCopyLockProvider())

	internal constructor(openDatabase: (File) -> SupportSQLiteDatabase) :
		this(SQLiteDatabaseCopyLockProvider(openDatabase))

	override val requiresLocationData: Boolean = false
	override val canSelectDateRange: Boolean = false
	override val mimeType: String = "application/zip"
	override val extension: String = "zip"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?
	): ExportResult {
		return try {
			DatabaseBackupArchive.writeData(
				DatabaseBackupScope.discover(context),
				outputStream,
				copyLockProvider,
			)
			ExportResult.Success
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			ExportResult.Error(LocalizedString(R.string.export_error_unknown))
		}
	}

}

/**
 * A transaction lock starts immediately after the WAL checkpoint and remains held until the raw
 * file copy finishes. In WAL mode, later writes cannot enter the WAL while the lock is held; in
 * rollback-journal mode, writers cannot modify the database file. The lock is intentionally
 * per-file: coordinating every independent Room database would require a global write-quiesce
 * protocol that this module does not own.
 */
internal class SQLiteDatabaseCopyLockProvider(
	private val openDatabase: (File) -> SupportSQLiteDatabase = {
		SQLiteXSupportSQLite.openExistingReadWrite(it.path)
	},
) : DatabaseCopyLockProvider {
	override fun lock(databaseFile: File): Closeable {
		val database = openDatabase(databaseFile)
		try {
			var locked = false
			repeat(CHECKPOINT_LOCK_ATTEMPTS) {
				if (!locked) {
					locked = checkpointAndLock(database)
				}
			}
			check(locked) { "Database changed while preparing its backup snapshot" }
		} catch (exception: Exception) {
			try { if (database.inTransaction()) {
				database.endTransaction()
			} }
			catch (
				// Transaction cleanup failures remain attached to the original snapshot failure.
				@Suppress("TooGenericExceptionCaught") cleanup: Exception
			) { exception.addSuppressed(cleanup) }
			finally { database.close() }
			throw exception
		}
		return Closeable {
			try {
				database.endTransaction()
			} finally {
				database.close()
			}
		}
	}

	private fun checkpointAndLock(database: SupportSQLiteDatabase): Boolean {
		// Compare on the same primary connection. A pooled read connection has its
		// own data_version counter and cannot prove the checkpoint-to-lock gap.
		database.beginTransaction()
		val before = try {
			database.query("PRAGMA data_version").use { cursor ->
	                            cursor.moveToFirst(); cursor.getLong(0) }
		} finally { database.endTransaction() }
		val checkpointed = database.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
			cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getLong(1) == cursor.getLong(2)
		}
		var locked = false
		if (checkpointed) {
			database.beginTransaction()
			val after = database.query("PRAGMA data_version").use { cursor ->
	                            cursor.moveToFirst(); cursor.getLong(0) }
			locked = before == after
			if (!locked) {
				database.endTransaction()
			}
		}
		return locked
	}

}

private const val CHECKPOINT_LOCK_ATTEMPTS = 3
