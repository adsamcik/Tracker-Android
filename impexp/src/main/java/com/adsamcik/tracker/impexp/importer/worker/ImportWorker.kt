package com.adsamcik.tracker.impexp.importer.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.impexp.importer.service.ImportService
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.extension
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import java.util.Locale

class ImportWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val import = DataImport()
    private lateinit var database: AppDatabase
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var errorCount: Int = 0

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(ARG_FILE_URI)
        val uri = Uri.parse(uriString)
        val file = DocumentFile.fromSingleUri(context, uri) ?: return Result.failure()

        showNotification(
            context.getString(R.string.import_notification_progress),
            true
        )

        database = AppDatabase.database(context)
        database.runInTransaction {
            val count = handleFile(file)

            showNotification(
                context.resources.getQuantityString(
                    R.plurals.import_notification_finished,
                    count,
                    count
                ),
                false
            )
        }

        return Result.success()
    }

    private fun createNotification(text: String, inProgress: Boolean): Notification =
        NotificationCompat.Builder(context, context.getString(R.string.channel_other_id))
            .setSmallIcon(R.drawable.ic_signals)
            .setOngoing(inProgress)
            .setContentTitle(text)
            .build()

    @AnyThread
    private fun showNotification(text: String, inProgress: Boolean) {
        //notification should under no circumstances crash import
        tryWithReport {
            val notification = createNotification(text, inProgress)
            notificationManager.notify(ImportService.NOTIFICATION_ID, notification)
        }
    }

    @AnyThread
    private fun showErrorNotification(text: String) {
        tryWithReport {
            val notification = createNotification(text, false)
            notificationManager.notify(ImportService.NOTIFICATION_ERROR_BASE_ID + errorCount++, notification)
        }
    }

    @WorkerThread
    private fun extract(file: DocumentFile, extractor: ArchiveExtractor): Int {
        showNotification(
            context.getString(R.string.import_notification_extracting, file.name),
            true
        )

        val extractionStream = extractor.extract(context, file) ?: return 0

        return importAll(extractionStream)
    }

    @WorkerThread
    private fun importAll(stream: Sequence<FileImportStream>): Int {
        return stream.sumOf { tryImport(it) }
    }

    @WorkerThread
    private fun tryImport(stream: FileImportStream): Int {
        val extension = stream.extension.lowercase(Locale.ROOT)
        val importer = import.activeImporterList
            .find { it.supportedExtensions.contains(extension) }

        if (importer != null) {
            return import(stream, importer)
        } else {
            showErrorNotification(
                context.getString(
                    R.string.import_notification_error_file_not_supported,
                    stream.fileName
                )
            )
        }

        return 0
    }

    @WorkerThread
    private fun import(
        stream: FileImportStream,
        import: FileImport
    ): Int {
        showNotification(
            context.getString(R.string.import_notification_importing, stream.fileName),
            true
        )

        return tryWithResultAndReport({ 0 }) {
            try {
                import.import(context, database, stream)
                1
            } catch (e: SQLiteCantOpenDatabaseException) {
                showErrorNotification(
                    context.getString(
                        R.string.import_notification_error_failed_open_database,
                        stream.fileName
                    )
                )
                0
            }
        }
    }

    private fun handleFile(file: DocumentFile): Int {
        val extension = file.extension?.lowercase(Locale.ROOT)
        val extractor = import.activeArchiveExtractorList
            .find { it.supportedExtensions.contains(extension) }

        return if (extractor != null) {
            extract(file, extractor)
        } else {
            val fileName = file.name ?: return 0
            file.openInputStream(context)?.use { inputStream ->
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
