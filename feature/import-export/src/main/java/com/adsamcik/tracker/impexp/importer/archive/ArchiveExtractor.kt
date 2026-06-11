package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream

/**
 * Archive extractor interface.
 */
interface ArchiveExtractor {
	val supportedExtensions: Collection<String>

	/**
	 * Extracts an archive.
	 */
	fun extract(context: Context, file: DocumentFile): Sequence<FileImportStream>?
}
