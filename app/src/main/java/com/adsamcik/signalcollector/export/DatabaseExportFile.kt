package com.adsamcik.signalcollector.export

import android.content.Context
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import java.io.File

class DatabaseExportFile : ExportFile {
	override val canSelectDateRange: Boolean = false

	override fun export(context: Context, locationData: List<DatabaseLocation>, destinationDirectory: File, desiredName: String): ExportResult {
		val db = AppDatabase.getDatabase(context)
		AppDatabase.closeDatabase()
		val dbFile = context.getDatabasePath(db.openHelper.databaseName)
		val targetFile = File(destinationDirectory, "$desiredName.db")
		dbFile.copyTo(targetFile, true)
		return ExportResult(targetFile, "application/vnd.sqlite3")
	}
}