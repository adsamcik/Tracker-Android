package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.database.AppDatabase

/**
 * File importer interface.
 */
interface FileImport {
	val supportedExtensions: Collection<String>

	/**
	 * Imports given file into the database
	 *
	 * @param database Instance of the main database
	 * @param stream Import stream
	 * @return [ImportResult] with success/skipped/failed record counts
	 */
	suspend fun import(
			context: Context,
			database: AppDatabase,
			stream: FileImportStream
	): ImportResult
}
