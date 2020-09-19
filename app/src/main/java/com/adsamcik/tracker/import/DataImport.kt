package com.adsamcik.tracker.import

import android.content.Context
import android.os.Build
import com.adsamcik.tracker.import.archive.ArchiveExtractor
import com.adsamcik.tracker.import.archive.ZipArchiveExtractor
import com.adsamcik.tracker.import.file.DatabaseImport
import com.adsamcik.tracker.import.file.FileImport
import com.adsamcik.tracker.import.file.GpxImport
import com.adsamcik.tracker.import.service.ImportService
import com.adsamcik.tracker.shared.base.extension.lowerCaseExtension
import com.adsamcik.tracker.shared.base.extension.startForegroundService
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.files.FileFilter
import com.afollestad.materialdialogs.files.fileChooser

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
	val supportedImporterExtensions get() = activeImporterList.flatMap { it.supportedExtensions }

	/**
	 * List of supported archive extensions.
	 */
	val supportedArchiveExtractorExtensions get() = activeArchiveExtractorList.flatMap { it.supportedExtensions }

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
		MaterialDialog(context).show {
			val supportedExtensions = supportedExtensions
			val filter: FileFilter = { file ->
				val extension = file.lowerCaseExtension
				file.isDirectory || supportedExtensions.contains(extension)
			}

			fileChooser(
					context = context,
					filter = filter,
					waitForPositiveButton = true,
					allowFolderCreation = false,
					initialDirectory = context.getExternalFilesDir(null)
			) { _, file ->
				context.startForegroundService<ImportService> {
					putExtra(ImportService.ARG_FILE_PATH, file.path)
				}
			}

			// Dynamic style is more complicated for file selection
			//dynamicStyle()
		}
	}
}

