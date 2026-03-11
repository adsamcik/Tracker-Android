package com.adsamcik.tracker.impexp.importer

import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.DatabaseImport
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.impexp.importer.file.GpxImport
import com.adsamcik.tracker.impexp.importer.file.JsonImport
import com.adsamcik.tracker.impexp.importer.file.KmlImport

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
		this.activeImporterList = mutableListOf(GpxImport(), KmlImport(), DatabaseImport(), JsonImport())
		val archiveList = mutableListOf<ArchiveExtractor>()
		archiveList.add(ZipArchiveExtractor())
		this.activeArchiveExtractorList = archiveList
	}
}
