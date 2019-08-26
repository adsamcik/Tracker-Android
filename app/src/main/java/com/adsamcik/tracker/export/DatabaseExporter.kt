package com.adsamcik.tracker.export

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.data.DatabaseLocation
import java.io.File

class DatabaseExporter : Exporter {
	override val canSelectDateRange: Boolean = false

	override fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: File,
			desiredName: String
	): ExportResult {
		val db = AppDatabase.getDatabase(context)
		val dbFile = context.getDatabasePath(db.openHelper.databaseName)
		val targetFile = File(destinationDirectory, "$desiredName.db")
		db.generalDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
		db.runInTransaction {
			dbFile.copyTo(targetFile, true)
		}
		return ExportResult(targetFile, "application/vnd.sqlite3")
	}
}

