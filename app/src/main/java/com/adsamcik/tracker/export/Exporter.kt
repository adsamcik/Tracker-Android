package com.adsamcik.tracker.export

import android.content.Context
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import java.io.File

interface Exporter {
	val canSelectDateRange: Boolean

	fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: File,
			desiredName: String
	): ExportResult
}

