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
import androidx.hilt.work.HiltWorker
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.RoomImportReceiptStore
import com.adsamcik.tracker.impexp.importer.computeImportJobId
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveClassification
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.DatabaseImportFailure
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.extension.extension
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import java.io.IOException
import java.util.Locale
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Provider

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
	private val databaseProvider: Provider<AppDatabase>,
    private val dispatchers: DispatchersProvider,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(context, workerParams) {

    private val import = DataImport()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	private lateinit var database: AppDatabase
	private lateinit var importJobRunner: ImportJobRunner
	private var startupGeneration: Long = -1L

    private var errorCount: Int = 0

    override suspend fun doWork(): Result = withContext(dispatchers.io) {
        doWorkOnIo()
    }

    private suspend fun doWorkOnIo(): Result {
        val uriString = inputData.getString(ARG_FILE_URI) ?: return Result.failure()
        val uri = Uri.parse(uriString)
        val file = DocumentFile.fromSingleUri(context, uri) ?: return Result.failure()

		startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> {
				showErrorNotification("Import unavailable until tracking data recovery is complete.")
				return Result.failure()
			}
		}
		if (!isReadyGeneration()) return Result.success()
		database = databaseProvider.get()
		if (!isReadyGeneration()) return Result.success()
		importJobRunner = ImportJobRunner(
			receiptStore = RoomImportReceiptStore(database),
			verifyCollectedDataAccess = ::requireReadyGeneration,
		)

        showNotification(
            context.getString(R.string.import_notification_progress),
            true
        )

        return try {
            val fileName = file.name ?: throw IOException("Missing import file name")
            val sourceReadLimit = if (
                file.extension.equals("zip", ignoreCase = true)
            ) {
                ZipArchiveExtractor.MAX_COMPRESSED_INPUT_BYTES
            } else {
                Long.MAX_VALUE
            }
            val jobId = computeImportJobId(context, file, sourceReadLimit)
			requireReadyGeneration()
            if (!importJobRunner.start(jobId, fileName, file.length())) {
                val alreadyImported = ImportResult(skippedCount = 1)
                showNotification(buildNotificationText(alreadyImported), false)
                return Result.success()
            }
            val importResult = handleFile(file, jobId)
			requireReadyGeneration()
            importJobRunner.completeIfSuccessful(jobId, importResult)

            val notificationText = buildNotificationText(importResult)
            showNotification(notificationText, false)

            if (importResult.failedCount > 0) {
                showErrorNotification(
                    importResult.errors.singleOrNull()
                        ?.takeIf {
                            it == DatabaseImportFailure.TrackerDatabaseRestoreRequired.message
                        }
                        ?: context.getString(
                            R.string.import_notification_error_records_failed,
                            importResult.failedCount
                        )
                )
                return Result.failure()
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
		} catch (_: StartupGenerationChangedException) {
			Result.success()
        } catch (e: IOException) {
            showErrorNotification(e.message ?: "Import failed due to an I/O error.")
            Result.retry()
        } catch (e: Exception) {
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
        runCatchingCancellable {
            val notification = createNotification(text, inProgress)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }.getOrNull()
    }

    @AnyThread
    private fun showErrorNotification(text: String) {
        runCatchingCancellable {
            val notification = createNotification(text, false)
            notificationManager.notify(NOTIFICATION_ERROR_BASE_ID + errorCount++, notification)
        }.getOrNull()
    }

    @WorkerThread
    private suspend fun extract(
        file: DocumentFile,
        extractor: ArchiveExtractor,
        jobId: String,
    ): ImportResult {
        showNotification(
            context.getString(R.string.import_notification_extracting, file.name),
            true
        )
        if (
            extractor is ZipArchiveExtractor &&
            extractor.classifyForMergeImport(context, file) ==
            ZipArchiveClassification.TRACKER_DATABASE_BACKUP
        ) {
            return ImportResult(
                failedCount = 1,
                errors = listOf(DatabaseImportFailure.TrackerDatabaseRestoreRequired.message),
            )
        }

        return importJobRunner.importArchive(
            jobId = jobId,
            context = context,
            file = file,
            extractor = extractor,
            importEntry = ::tryImport,
        )
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
            return ImportResult(
                skippedCount = 1,
                errors = listOf("Unsupported import file type")
            )
        }
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

        return runCatchingCancellable {
			requireReadyGeneration()
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

    private suspend fun handleFile(file: DocumentFile, jobId: String): ImportResult {
		requireReadyGeneration()
        val extension = file.extension?.lowercase(Locale.ROOT)
        val extractor = import.activeArchiveExtractorList
            .find { it.supportedExtensions.contains(extension) }

        return if (extractor != null) {
            extract(file, extractor, jobId)
        } else {
            val fileName = file.name ?: throw IOException("Missing import file name")
            file.openInputStream(context)?.use { inputStream ->
                importJobRunner.importSingle(
                    jobId = jobId,
                    stream = FileImportStream(
                        fileName = fileName,
                        receiptKey = DIRECT_ENTRY_KEY,
                        streamProvider = { inputStream },
                    ),
                    importEntry = ::tryImport,
                )
            } ?: throw IOException("Failed to open ${fileName}")
        }
    }

	private fun isReadyGeneration(): Boolean =
		trackingStartupGate.isReady && trackingStartupGate.currentGeneration == startupGeneration

	private fun requireReadyGeneration() {
		if (!isReadyGeneration()) throw StartupGenerationChangedException
	}

    companion object {
        const val NOTIFICATION_ID: Int = 98784
        const val NOTIFICATION_ERROR_BASE_ID: Int = 98785
        const val ARG_FILE_URI: String = "filePath"
        private const val DIRECT_ENTRY_KEY: String = "direct-source-v1"
    }

	private object StartupGenerationChangedException : RuntimeException()
}
