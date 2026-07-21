package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.shared.model.LocationSample
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
	 * Location data is provided as a [Sequence] to enable chunked/streaming reads from the
	 * database, avoiding loading the entire dataset into memory at once.
	 *
	 * @param context Application context
	 * @param locationData Lazy sequence of [LocationSample] data; may only be iterated once
	 * @param outputStream Destination stream for the export file
	 * @param dateRange Optional time range (epochMillis start..end) for metadata; avoids
	 *   needing to scan the full sequence just for boundary timestamps
	 */
	suspend fun export(
			context: Context,
			locationData: Sequence<LocationSample>,
			outputStream: OutputStream,
			dateRange: LongRange? = null
	): ExportResult
}

/**
 * Exporter that performs its own database reads and therefore needs the persisted
 * location cursor in addition to the outer date range.
 */
internal interface CursorAwareExporter : Exporter {
	suspend fun exportAfter(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
		afterTimeMs: Long?,
		afterId: Long?,
	): ExportResult
}
