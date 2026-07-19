package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.OutputStream

/**
 * Exports raw database to a desired location.
 */
class DatabaseExporter : Exporter {
	override val canSelectDateRange: Boolean = false
	override val mimeType: String = "application/vnd.sqlite3"
	override val extension: String = "db"

	override suspend fun export(
			context: Context,
			locationData: Sequence<LocationSample>,
			outputStream: OutputStream,
			dateRange: LongRange?
	): ExportResult {
		val db = AppDatabase.database(context)
		val dbFile = DocumentFile.fromFile(context.getDatabasePath(db.openHelper.databaseName))

		db.generalDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
		val input = dbFile.uri.openInputStream(context)
			?: return ExportResult.Error(
				LocalizedString(R.string.export_error_source_database_unavailable)
			)
		db.runInTransaction {
			input.use {
				input.copyTo(outputStream)
			}
		}
		return ExportResult.Success
	}
}
