package com.adsamcik.signalcollector.import

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.extension.lowerCaseExtension
import com.adsamcik.signalcollector.common.extension.notificationManager
import com.adsamcik.signalcollector.import.archive.ArchiveExtractor
import com.adsamcik.signalcollector.import.archive.ZipArchiveExtractor
import com.adsamcik.signalcollector.import.file.FileImport
import com.adsamcik.signalcollector.import.file.GpxImport
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.files.FileFilter
import com.afollestad.materialdialogs.files.fileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

//todo show progress
//todo import in service or a job, it can take quite awhile
class DataImport {
	private val activeImporterList: Collection<FileImport>
	private val activeArchiveExtractorList: Collection<ArchiveExtractor>

	private val supportedImporterExtensions get() = activeImporterList.flatMap { it.supportedExtensions }
	private val supportedArchiveExtractorExtensions get() = activeArchiveExtractorList.flatMap { it.supportedExtensions }

	val supportedExtensions: Collection<String> get() = supportedImporterExtensions.union(supportedArchiveExtractorExtensions)

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

	private fun showNotification(context: Context, progress: Int, success: Boolean) {
		val notificationBuilder = NotificationCompat.Builder(context, context.getString(R.string.channel_other_id))
				.setSmallIcon(R.drawable.ic_signals)
				.setOngoing(progress < 100)

		val contentResource = if (progress < 100) {
			R.string.import_notification_progress
		} else {
			if (success) {
				R.string.import_notification_success
			} else {
				R.string.import_notification_failed
			}
		}

		notificationBuilder
				.setContentTitle(context.getString(contentResource))

		context.notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
	}

	private fun extract(context: Context, file: File, extractor: ArchiveExtractor): Int {
		val extractedFile = extractor.extract(context, file) ?: return 0

		return importAllRecursively(context, extractedFile)
	}

	private fun importAllRecursively(context: Context, directory: File): Int {
		assert(directory.isDirectory)

		var importedCount = 0
		directory.listFiles()?.forEach {
			if (it.isDirectory) {
				importedCount += importAllRecursively(context, it)
			} else {
				if (tryImport(context, it)) {
					importedCount++
				}
			}
		}

		return importedCount
	}

	private fun tryImport(context: Context, file: File): Boolean {
		val extension = file.lowerCaseExtension
		val importer = activeImporterList.find { it.supportedExtensions.contains(extension) }

		if (importer != null) {
			import(context, file, importer)
			return true
		}

		return false
	}

	private fun import(context: Context, file: File, import: FileImport): Boolean {
		val database = AppDatabase.getDatabase(context)

		return try {
			import.import(database, file)
			true
		} catch (e: Exception) {
			Reporter.report(e)
			false
		}
	}

	private fun handleFile(context: Context, file: File): Boolean {
		val extension = file.lowerCaseExtension
		val extractor = activeArchiveExtractorList.find { it.supportedExtensions.contains(extension) }

		return if (extractor != null) {
			extract(context, file, extractor) > 0
		} else {
			tryImport(context, file)
		}
	}

	fun showImportDialog(context: Context) {
		MaterialDialog(context).show {
			val supportedExtensions = supportedExtensions
			val filter: FileFilter = { file ->
				val extension = file.lowerCaseExtension
				file.isDirectory || supportedExtensions.contains(extension)
			}

			fileChooser(filter = filter, waitForPositiveButton = true, allowFolderCreation = false) { _, file ->
				GlobalScope.launch(Dispatchers.Default) {
					showNotification(context, 0, true)
					val success = handleFile(context, file)
					showNotification(context, 100, success)
				}
			}
		}
	}

	companion object {
		const val NOTIFICATION_ID = 98784
	}
}