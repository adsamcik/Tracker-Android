package com.adsamcik.tracker.impexp.importer

import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.FileImport

/**
 * Takes care of data importing.
 */
class DataImport {
	/**
	 * List of active file importers
	 */
	val activeImporterList: Collection<FileImport>

	/**
	 * List of active archive extractors.
	 */
	val activeArchiveExtractorList: Collection<ArchiveExtractor>

	/**
	 * List of supported extensions.
	 */
	val supportedImporterExtensions: List<String> get() = activeImporterList.flatMap { it.supportedExtensions }

	/**
	 * List of supported archive extensions.
	 */
	val supportedArchiveExtractorExtensions: List<String>
		get() = activeArchiveExtractorList.flatMap { it.supportedExtensions }

	/**
	 * List of all supported extensions (import + archive).
	 */
	val supportedExtensions: Collection<String>
		get() = supportedImporterExtensions.union(supportedArchiveExtractorExtensions)

	init {
		this.activeImporterList = FormatRegistry.allImporters()
		val archiveList = mutableListOf<ArchiveExtractor>()
		archiveList.add(ZipArchiveExtractor())
		this.activeArchiveExtractorList = archiveList
	}
}
