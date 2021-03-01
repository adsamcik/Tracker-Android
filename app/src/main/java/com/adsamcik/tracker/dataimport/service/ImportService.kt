package com.adsamcik.tracker.dataimport.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.R
import com.adsamcik.tracker.dataimport.DataImport
import com.adsamcik.tracker.dataimport.FileImportStream
import com.adsamcik.tracker.dataimport.archive.ArchiveExtractor
import com.adsamcik.tracker.dataimport.file.FileImport
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import com.anggrayudi.storage.file.extension
import com.anggrayudi.storage.file.openInputStream
import kotlinx.coroutines.launch
import java.util.*

/**
 * Data import service.
 */
internal class ImportService : CoreService() {
	private val import = DataImport()
	private lateinit var database: AppDatabase

	var errorCount: Int = 0

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent == null) {
			Reporter.report(NullPointerException("Service cannot be started without intent"))
			stopSelf()
			return Service.START_NOT_STICKY
		}

		val uri = intent.getStringExtra(ARG_FILE_URI)
				?: throw NullPointerException("Argument $ARG_FILE_URI needs to be set")

		val file = DocumentFile.fromSingleUri(this, Uri.parse(uri))
				?: throw RuntimeException("Could not get document file")

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
	private fun extract(file: DocumentFile, extractor: ArchiveExtractor): Int {
		showNotification(
				getString(R.string.import_notification_extracting, file.name),
				true
		)

		val extractionStream = extractor.extract(this, file) ?: return 0

		return importAll(extractionStream)
	}

	@WorkerThread
	private fun importAll(stream: Sequence<FileImportStream>): Int {
		return stream.sumOf { tryImport(it) }
	}

	@WorkerThread
	private fun tryImport(stream: FileImportStream): Int {
		val extension = stream.extension.toLowerCase(Locale.ROOT)
		val importer = import.activeImporterList
				.find { it.supportedExtensions.contains(extension) }

		if (importer != null) {
			return import(stream, importer)
		}

		return 0
	}

	@WorkerThread
	private fun import(
			stream: FileImportStream,
			import: FileImport
	): Int {
		showNotification(
				getString(R.string.import_notification_importing, stream.fileName),
				true
		)

		return tryWithResultAndReport({ 0 }) {
			try {
				import.import(this, database, stream)
				1
			} catch (e: SQLiteCantOpenDatabaseException) {
				showErrorNotification(
						getString(
								R.string.import_notification_error_failed_open_database,
								stream.fileName
						)
				)
				0
			}
		}
	}

	private fun handleFile(file: DocumentFile): Int {
		val extension = file.extension.toLowerCase(Locale.ROOT)
		val extractor = import.activeArchiveExtractorList
				.find { it.supportedExtensions.contains(extension) }

		return if (extractor != null) {
			extract(file, extractor)
		} else {
			val fileName = file.name ?: return 0
			file.openInputStream(this)?.use { inputStream ->
				tryImport(FileImportStream(inputStream, fileName))
			} ?: 0
		}
	}

	companion object {
		const val NOTIFICATION_ID: Int = 98784
		const val NOTIFICATION_ERROR_BASE_ID: Int = 98785
		const val ARG_FILE_URI: String = "filePath"
	}
}

