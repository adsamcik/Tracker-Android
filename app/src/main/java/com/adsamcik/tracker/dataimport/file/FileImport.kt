package com.adsamcik.tracker.dataimport.file

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.shared.base.database.AppDatabase
import java.io.File
import java.io.InputStream

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
	 */
	//todo add some way to handle errors
	fun import(
			context: Context,
			database: AppDatabase,
			stream: InputStream
	)
}
