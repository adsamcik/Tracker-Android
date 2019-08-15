package com.adsamcik.signalcollector.import

import android.content.Context
import android.os.Build
import com.adsamcik.signalcollector.common.extension.lowerCaseExtension
import com.adsamcik.signalcollector.common.extension.startForegroundService
import com.adsamcik.signalcollector.import.archive.ArchiveExtractor
import com.adsamcik.signalcollector.import.archive.ZipArchiveExtractor
import com.adsamcik.signalcollector.import.file.FileImport
import com.adsamcik.signalcollector.import.file.GpxImport
import com.adsamcik.signalcollector.import.service.ImportService
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.files.FileFilter
import com.afollestad.materialdialogs.files.fileChooser

//todo show progress
//todo import in service or a job, it can take quite awhile
class DataImport {
	val activeImporterList: Collection<FileImport>
	val activeArchiveExtractorList: Collection<ArchiveExtractor>

	val supportedImporterExtensions get() = activeImporterList.flatMap { it.supportedExtensions }
	val supportedArchiveExtractorExtensions get() = activeArchiveExtractorList.flatMap { it.supportedExtensions }

	val supportedExtensions: Collection<String>
		get() = supportedImporterExtensions.union(supportedArchiveExtractorExtensions)

	init {
		val importList = mutableListOf<FileImport>()
		if (Build.VERSION.SDK_INT >= 26) {
			importList.add(GpxImport())
		}

		this.activeImporterList = importList

		val archiveList = mutableListOf<ArchiveExtractor>()

		archiveList.add(ZipArchiveExtractor())

		this.activeArchiveExtractorList = archiveList
	}


	fun showImportDialog(context: Context) {
		MaterialDialog(context).show {
			val supportedExtensions = supportedExtensions
			val filter: FileFilter = { file ->
				val extension = file.lowerCaseExtension
				file.isDirectory || supportedExtensions.contains(extension)
			}

			fileChooser(filter = filter, waitForPositiveButton = true, allowFolderCreation = false) { _, file ->
				context.startForegroundService<ImportService> {
					putExtra(ImportService.ARG_FILE_PATH, file.path)
				}
			}
		}
	}
}

