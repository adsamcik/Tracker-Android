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
	 * Extracts and consumes one entry at a time. The entry stream is closed before the next
	 * entry is inspected, bounding temporary files and open descriptors to O(1).
	 *
	 * @return false when the archive source cannot be opened.
	 */
	suspend fun extract(
		context: Context,
		file: DocumentFile,
		shouldExtract: suspend (ArchiveEntryMetadata) -> Boolean,
		consume: suspend (FileImportStream) -> Unit,
	): Boolean
}

data class ArchiveEntryMetadata(
	val receiptKey: String,
	val fileName: String,
)
