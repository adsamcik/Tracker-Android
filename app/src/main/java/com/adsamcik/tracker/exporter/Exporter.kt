package com.adsamcik.tracker.exporter

import android.content.Context
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import java.io.OutputStream

/**
 * Interface for exporting data to file system.
 */
interface Exporter {
	/**
	 * True if exporter can export based on date range.
	 */
	val canSelectDateRange: Boolean

	/**
	 * Mime type of result export file.
	 */
	val mimeType: String

	/**
	 * Extension export result file.
	 */
	val extension: String

	/**
	 * Called when data should be exported by the exporter to desired location in the filesystem.
	 */
	fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			outputStream: OutputStream
	): ExportResult
}

