package com.adsamcik.tracker.impexp.importer

import java.io.InputStream

/**
 * Provides stream with a filename.
 */
class FileImportStream(
		private val stream: InputStream,
		/**
		 * File name with extension.
		 */
		val fileName: String
) : InputStream() {
	/**
	 * File extension.
	 */
	val extension: String get() = fileName.substringAfterLast('.', "")

	override fun read(): Int = stream.read()
}
