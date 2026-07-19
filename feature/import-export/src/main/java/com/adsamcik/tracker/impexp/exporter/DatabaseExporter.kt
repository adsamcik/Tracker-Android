package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.File
import java.io.OutputStream

/**
 * Exports every database owned by the application as a ZIP backup.
 *
 * Database discovery is deliberately based on [Context.databaseList] instead of a list of
 * module-specific database implementations. This includes independent feature databases without
 * making this module depend on their implementations, and automatically includes future ones.
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
			databaseFiles.forEach(::checkpoint)
			DatabaseBackupArchive.write(databaseFiles, outputStream)
			ExportResult.Success
		} catch (_: Exception) {
			ExportResult.Error(LocalizedString(R.string.export_error_unknown))
		}
	}

	private fun checkpoint(databaseFile: File) {
		SQLiteDatabase.openDatabase(
			databaseFile.path,
			null,
			SQLiteDatabase.OPEN_READWRITE,
		).use { database ->
			database.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use {
				while (it.moveToNext()) {}
			}
		}
	}

	private fun isDatabaseFileName(name: String): Boolean =
		name == File(name).name &&
			!name.endsWith("-wal") &&
			!name.endsWith("-shm") &&
			!name.endsWith("-journal")
}
