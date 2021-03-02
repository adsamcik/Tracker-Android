package com.adsamcik.tracker.dataimport

import android.app.Activity
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.adsamcik.tracker.dataimport.archive.ArchiveExtractor
import com.adsamcik.tracker.dataimport.archive.ZipArchiveExtractor
import com.adsamcik.tracker.dataimport.file.DatabaseImport
import com.adsamcik.tracker.dataimport.file.FileImport
import com.adsamcik.tracker.dataimport.file.GpxImport
import com.adsamcik.tracker.dataimport.service.ImportService
import com.adsamcik.tracker.shared.base.extension.startForegroundService

/**
 * Takes care of data importing.
 */
internal class DataImport {
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
		this.activeImporterList = mutableListOf(GpxImport(), DatabaseImport())
		val archiveList = mutableListOf<ArchiveExtractor>()
		archiveList.add(ZipArchiveExtractor())
		this.activeArchiveExtractorList = archiveList
	}
}

