package com.adsamcik.tracker.importer.file

import android.content.Context
import com.adsamcik.tracker.importer.FileImportStream
import com.adsamcik.tracker.shared.base.database.AppDatabase

/**
 * File importer interface.
 */
internal interface FileImport {
	val supportedExtensions: Collection<String>

	/**
	 * Imports given file into the database
	 *
	 * @param database Instance of the main database
	 * @param stream Import stream
	 */
	//todo add some way to handle errors
	fun import(
			context: Context,
			database: AppDatabase,
			stream: FileImportStream
	)
}
