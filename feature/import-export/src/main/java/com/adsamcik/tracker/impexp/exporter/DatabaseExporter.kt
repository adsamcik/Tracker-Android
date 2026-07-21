package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/**
 * Exports every database owned by the application as a ZIP backup.
 *
 * Database discovery is deliberately based on [Context.databaseList] instead of a list of
 * module-specific database implementations. This includes independent feature databases without
 * making this module depend on their implementations, and automatically includes future ones.
 *
 * Each database is checkpointed and write-locked for its complete raw-file copy, preventing a
 * concurrent writer from tearing that individual snapshot. Files are still locked and copied one
 * at a time, so an archive containing multiple databases is not an atomic cross-database snapshot.
 */
class DatabaseExporter : Exporter {
	override val canSelectDateRange: Boolean = false
	override val mimeType: String = "application/zip"
	override val extension: String = "zip"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?
	): ExportResult {
		val databaseFiles = context.databaseList()
			.map(context::getDatabasePath)
			.filter { it.isFile && isDatabaseFileName(it.name) }

		if (databaseFiles.isEmpty()) {
			return ExportResult.Error(LocalizedString(R.string.export_error_no_databases))
		}

		return try {
			DatabaseBackupArchive.write(
				databaseFiles,
				outputStream,
				SQLiteDatabaseCopyLockProvider,
			)
			ExportResult.Success
		} catch (_: Exception) {
			ExportResult.Error(LocalizedString(R.string.export_error_unknown))
		}
	}

	private fun isDatabaseFileName(name: String): Boolean =
		name == File(name).name &&
			!name.endsWith("-wal") &&
			!name.endsWith("-shm") &&
			!name.endsWith("-journal")
}

/**
 * A transaction lock starts immediately after the WAL checkpoint and remains held until the raw
 * file copy finishes. In WAL mode, later writes cannot enter the WAL while the lock is held; in
 * rollback-journal mode, writers cannot modify the database file. The lock is intentionally
 * per-file: coordinating every independent Room database would require a global write-quiesce
 * protocol that this module does not own.
 */
internal object SQLiteDatabaseCopyLockProvider : DatabaseCopyLockProvider {
	override fun lock(databaseFile: File): Closeable {
		val database = SQLiteDatabase.openDatabase(
			databaseFile.path,
			null,
			SQLiteDatabase.OPEN_READWRITE,
		)
		try {
			database.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use {
				while (it.moveToNext()) {}
			}
			database.beginTransaction()
		} catch (exception: Exception) {
			database.close()
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
}
