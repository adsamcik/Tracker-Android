package com.adsamcik.tracker.dataimport.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.stream.Stream

/**
 * Archive extractor interface.
 */
interface ArchiveExtractor {
	val supportedExtensions: Collection<String>

	/**
	 * Extracts an archive.
	 */
	fun extract(context: Context, file: DocumentFile): Sequence<InputStream>?
}
