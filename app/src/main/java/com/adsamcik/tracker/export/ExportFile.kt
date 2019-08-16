package com.adsamcik.tracker.export

import android.content.Context
import com.adsamcik.tracker.common.database.data.DatabaseLocation
import java.io.File

interface ExportFile {
	val canSelectDateRange: Boolean

	fun export(context: Context,
	           locationData: List<DatabaseLocation>,
	           destinationDirectory: File,
	           desiredName: String): ExportResult
}

