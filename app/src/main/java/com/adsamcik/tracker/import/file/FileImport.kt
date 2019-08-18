package com.adsamcik.tracker.import.file

import android.content.Context
import com.adsamcik.tracker.common.database.AppDatabase
import java.io.File

interface FileImport {
	val supportedExtensions: Collection<String>

	/**
	 * Imports given file into the database
	 *
	 * @param database Instance of the main database
	 * @param file File to import
	 */
	//todo add some way to handle errors
	fun import(context: Context,
	           database: AppDatabase,
	           file: File)
}
