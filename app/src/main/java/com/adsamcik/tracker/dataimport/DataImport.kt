package com.adsamcik.tracker.dataimport

import android.content.Context
import android.os.Build
import com.adsamcik.tracker.dataimport.activity.ImportActivity
import com.adsamcik.tracker.dataimport.archive.ArchiveExtractor
import com.adsamcik.tracker.dataimport.archive.ZipArchiveExtractor
import com.adsamcik.tracker.dataimport.file.DatabaseImport
import com.adsamcik.tracker.dataimport.file.FileImport
import com.adsamcik.tracker.dataimport.file.GpxImport
import com.adsamcik.tracker.shared.base.extension.startActivity

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
		val importList = mutableListOf<FileImport>()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			importList.add(GpxImport())
		}
		importList.add(DatabaseImport())

		this.activeImporterList = importList

		val archiveList = mutableListOf<ArchiveExtractor>()

		archiveList.add(ZipArchiveExtractor())

		this.activeArchiveExtractorList = archiveList
	}


	/**
	 * Show import dialog
	 */
	fun showImportDialog(context: Context) {
		context.startActivity<ImportActivity>()
	}
}

