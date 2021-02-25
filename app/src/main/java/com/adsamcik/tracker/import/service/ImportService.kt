package com.adsamcik.tracker.import.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.database.sqlite.SQLiteCantOpenDatabaseException
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.import.DataImport
import com.adsamcik.tracker.import.archive.ArchiveExtractor
import com.adsamcik.tracker.import.file.FileImport
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.logger.assertTrue
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.lowerCaseExtension
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import kotlinx.coroutines.launch
import java.io.File

/**
 * Data import service.
 */
class ImportService : CoreService() {
	private val import = DataImport()
	private lateinit var database: AppDatabase

	var errorCount: Int = 0

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent == null) {
			Reporter.report(NullPointerException("Service cannot be started without intent"))
			stopSelf()
			return Service.START_NOT_STICKY
		}

		val path = intent.getStringExtra(ARG_FILE_PATH)
				?: throw NullPointerException("Argument $ARG_FILE_PATH needs to be set")

		val file = File(path)

		startForeground(
				NOTIFICATION_ID, createNotification(
				getString(R.string.import_notification_progress),
				true
		)
		)

		launch {
			database = AppDatabase.database(this@ImportService)
			database.runInTransaction {
				val count = handleFile(file)

				showNotification(
						resources.getQuantityString(
								R.plurals.import_notification_finished,
								count,
								count
						),
						false
				)
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
		tryWithReport {
			val notification = createNotification(text, inProgress)
			notificationManager.notify(NOTIFICATION_ID, notification)
		}
	}

	@AnyThread
	private fun showErrorNotification(text: String) {
		tryWithReport {
			val notification = createNotification(text, false)
			notificationManager.notify(NOTIFICATION_ERROR_BASE_ID + errorCount++, notification)
		}
	}

	@WorkerThread
	private fun extract(file: File, extractor: ArchiveExtractor): Int {
		showNotification(
				getString(R.string.import_notification_extracting, file.name),
				true
		)

		val extractedFile = extractor.extract(this, file) ?: return 0

		return importAllRecursively(extractedFile)
	}

	@WorkerThread
	private fun importAllRecursively(directory: File): Int {
		assertTrue(directory.isDirectory)

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
	private fun import(
			file: File,
			import: FileImport
	): Int {
		showNotification(
				getString(R.string.import_notification_importing, file.name),
				true
		)

		return tryWithResultAndReport({ 0 }) {
			try {
				import.import(this, database, file)
				1
			} catch (e: SQLiteCantOpenDatabaseException) {
				showErrorNotification(
						getString(
								R.string.import_notification_error_failed_open_database,
								file.name
						)
				)
				0
			}
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
		const val NOTIFICATION_ID: Int = 98784
		const val NOTIFICATION_ERROR_BASE_ID: Int = 98785
		const val ARG_FILE_PATH: String = "filePath"
	}
}

