package com.adsamcik.tracker.dataimport.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.dataimport.FileImportStream

/**
 * Archive extractor interface.
 */
internal interface ArchiveExtractor {
	val supportedExtensions: Collection<String>

	/**
	 * Extracts an archive.
	 */
	fun extract(context: Context, file: DocumentFile): Sequence<FileImportStream>?
}
