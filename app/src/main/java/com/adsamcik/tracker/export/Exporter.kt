package com.adsamcik.tracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation

/**
 * Interface for exporting data to file system.
 */
interface Exporter {
	val canSelectDateRange: Boolean

	/**
	 * Called when data should be exported by the exporter to desired location in the filesystem.
	 */
	fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: DocumentFile,
			desiredName: String
	): ExportResult
}

