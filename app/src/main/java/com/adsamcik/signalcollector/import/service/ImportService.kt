package com.adsamcik.signalcollector.import.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.extension.lowerCaseExtension
import com.adsamcik.signalcollector.common.extension.notificationManager
import com.adsamcik.signalcollector.common.service.CoreService
import com.adsamcik.signalcollector.import.DataImport
import com.adsamcik.signalcollector.import.archive.ArchiveExtractor
import com.adsamcik.signalcollector.import.file.FileImport
import kotlinx.coroutines.launch
import java.io.File

class ImportService : CoreService() {
	private val import = DataImport()
	private lateinit var database: AppDatabase

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent == null) {
			Reporter.report(NullPointerException("Service cannot be started without intent"))
			stopSelf()
			return Service.START_NOT_STICKY
		}

		val path = intent.getStringExtra(ARG_FILE_PATH)
				?: throw NullPointerException("Argument $ARG_FILE_PATH needs to be set")

		val file = File(path)

		startForeground(NOTIFICATION_ID, createNotification(
				getString(R.string.import_notification_progress),
				true))

		launch {
			database = AppDatabase.getDatabase(this@ImportService)
			database.runInTransaction {
				val count = handleFile(file)

				showNotification(
						resources.getQuantityString(R.plurals.import_notification_finished, count, count),
						false)
			}

			stopForeground(false)
		}

		return super.onStartCommand(intent, flags, startId)
	}

	private fun createNotification(text: String, inProgress: Boolean): Notification =
			NotificationCompat.Builder(this, getString(R.string.channel_other_id))
					.setSmallIcon(R.drawable.ic_signals)
					.setOngoing(inProgress)
					.setContentTitle(text)
					.build()

	@AnyThread
	private fun showNotification(text: String, inProgress: Boolean) {
		//notification should under no circumstances crash import
		try {
			val notification = createNotification(text, inProgress)
			notificationManager.notify(NOTIFICATION_ID, notification)
		} catch (e: Exception) {
			Reporter.report(e)
		}
	}

	@WorkerThread
	private fun extract(file: File, extractor: ArchiveExtractor): Int {
		showNotification(
				getString(R.string.import_notification_extracting, file.name),
				true)

		val extractedFile = extractor.extract(this, file) ?: return 0

		return importAllRecursively(extractedFile)
	}

	@WorkerThread
	private fun importAllRecursively(directory: File): Int {
		assert(directory.isDirectory)

		var importedCount = 0
		directory.listFiles()?.forEach {
			importedCount += if (it.isDirectory) {
				importAllRecursively(it)
			} else {
				tryImport(it)

			}
		}

		return importedCount
	}

	@WorkerThread
	private fun tryImport(file: File): Int {
		val extension = file.lowerCaseExtension
		val importer = import.activeImporterList
				.find { it.supportedExtensions.contains(extension) }

		if (importer != null) {
			return import(file, importer)
		}

		return 0
	}

	@WorkerThread
	private fun import(file: File, import: FileImport): Int {
		showNotification(
				getString(R.string.import_notification_importing, file.name),
				true)

		return try {
			import.import(database, file)
			1
		} catch (e: Exception) {
			Reporter.report(e)
			0
		}
	}

	private fun handleFile(file: File): Int {
		val extension = file.lowerCaseExtension
		val extractor = import.activeArchiveExtractorList
				.find { it.supportedExtensions.contains(extension) }

		return if (extractor != null) {
			extract(file, extractor)
		} else {
			tryImport(file)
		}
	}

	companion object {
		const val NOTIFICATION_ID = 98784
		const val ARG_FILE_PATH = "filePath"
	}
}