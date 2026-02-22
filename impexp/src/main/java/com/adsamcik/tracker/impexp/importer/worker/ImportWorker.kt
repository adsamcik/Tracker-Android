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
import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.FileImport
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
        database.withTransaction {
            val importResult = handleFile(file)

            val notificationText = buildNotificationText(importResult)
            showNotification(notificationText, false)

            if (importResult.failedCount > 0) {
                showErrorNotification(
                    context.getString(
                        R.string.import_notification_error_records_failed,
                        importResult.failedCount
                    )
                )
            }
        }

        return Result.success()
    }

    private fun buildNotificationText(result: ImportResult): String {
        val successCount = result.successCount
        return if (result.skippedCount > 0) {
            context.getString(
                R.string.import_notification_finished_with_skipped,
                successCount,
                result.skippedCount
            )
        } else {
            context.resources.getQuantityString(
                R.plurals.import_notification_finished,
                successCount,
                successCount
            )
        }
    }

    private fun createNotification(text: String, inProgress: Boolean): Notification =
        NotificationCompat.Builder(
            context,
            context.getString(com.adsamcik.tracker.shared.base.R.string.channel_other_id)
        )
            .setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
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
    private suspend fun extract(file: DocumentFile, extractor: ArchiveExtractor): ImportResult {
        showNotification(
            context.getString(R.string.import_notification_extracting, file.name),
            true
        )

        val extractionStream = extractor.extract(context, file) ?: return ImportResult.EMPTY

        return importAll(extractionStream)
    }

    @WorkerThread
    private suspend fun importAll(stream: Sequence<FileImportStream>): ImportResult {
        var result = ImportResult.EMPTY
        for (it in stream) {
            result += tryImport(it)
        }
        return result
    }

    @WorkerThread
    private suspend fun tryImport(stream: FileImportStream): ImportResult {
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

        return ImportResult.EMPTY
    }

    @WorkerThread
    private suspend fun import(
        stream: FileImportStream,
        import: FileImport
    ): ImportResult {
        showNotification(
            context.getString(R.string.import_notification_importing, stream.fileName),
            true
        )

        return tryWithResultAndReport({ ImportResult.EMPTY }) {
            try {
                import.import(context, database, stream)
            } catch (e: SQLiteCantOpenDatabaseException) {
                showErrorNotification(
                    context.getString(
                        R.string.import_notification_error_failed_open_database,
                        stream.fileName
                    )
                )
                ImportResult.EMPTY
            }
        }
    }

    private suspend fun handleFile(file: DocumentFile): ImportResult {
        val extension = file.extension?.lowercase(Locale.ROOT)
        val extractor = import.activeArchiveExtractorList
            .find { it.supportedExtensions.contains(extension) }

        return if (extractor != null) {
            extract(file, extractor)
        } else {
            val fileName = file.name ?: return ImportResult.EMPTY
            file.openInputStream(context)?.use { inputStream ->
                tryImport(FileImportStream(inputStream, fileName))
            } ?: ImportResult.EMPTY
        }
    }
    companion object {
        const val NOTIFICATION_ID: Int = 98784
        const val NOTIFICATION_ERROR_BASE_ID: Int = 98785
        const val ARG_FILE_URI: String = "filePath"
    }
}
