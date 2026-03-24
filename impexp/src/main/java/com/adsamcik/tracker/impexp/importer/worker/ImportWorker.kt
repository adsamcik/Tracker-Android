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
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.extension
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.shared.utils.extension.runWithReport
import com.adsamcik.tracker.shared.utils.extension.runWithResultAndReport
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

class ImportWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val import = DataImport()
    private lateinit var database: AppDatabase
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var errorCount: Int = 0

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(ARG_FILE_URI) ?: return Result.failure()
        val uri = Uri.parse(uriString)
        val file = DocumentFile.fromSingleUri(context, uri) ?: return Result.failure()

        showNotification(
            context.getString(R.string.import_notification_progress),
            true
        )

        database = AppDatabase.database(context)
        return try {
            val importResult = database.withTransaction {
                handleFile(file)
            }

            val notificationText = buildNotificationText(importResult)
            showNotification(notificationText, false)

            if (importResult.failedCount > 0) {
                showErrorNotification(
                    context.getString(
                        R.string.import_notification_error_records_failed,
                        importResult.failedCount
                    )
                )
                return Result.failure()
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Reporter.report(e)
            showErrorNotification(e.message ?: "Import failed due to an I/O error.")
            Result.retry()
        } catch (e: Exception) {
            Reporter.report(e)
            showErrorNotification(e.message ?: "Import failed.")
            Result.failure()
        }
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
        runWithReport {
            val notification = createNotification(text, inProgress)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    @AnyThread
    private fun showErrorNotification(text: String) {
        runWithReport {
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

        val extractionStream = extractor.extract(context, file)
            ?: throw IOException("Failed to extract ${file.name ?: "archive"}")

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
        val importer = FormatRegistry.importerForExtension(extension)

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

        return runWithResultAndReport {
            try {
                import.import(context, database, stream)
            } catch (e: SQLiteCantOpenDatabaseException) {
                showErrorNotification(
                    context.getString(
                        R.string.import_notification_error_failed_open_database,
                        stream.fileName
                    )
                )
                throw e
            }
        }.getOrThrow()
    }

    private suspend fun handleFile(file: DocumentFile): ImportResult {
        val extension = file.extension?.lowercase(Locale.ROOT)
        val extractor = import.activeArchiveExtractorList
            .find { it.supportedExtensions.contains(extension) }

        return if (extractor != null) {
            extract(file, extractor)
        } else {
            val fileName = file.name ?: throw IOException("Missing import file name")
            file.openInputStream(context)?.use { inputStream ->
                tryImport(FileImportStream(inputStream, fileName))
            } ?: throw IOException("Failed to open ${fileName}")
        }
    }
    companion object {
        const val NOTIFICATION_ID: Int = 98784
        const val NOTIFICATION_ERROR_BASE_ID: Int = 98785
        const val ARG_FILE_URI: String = "filePath"
    }
}
