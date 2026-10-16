package com.adsamcik.tracker.impexp.importer.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.format.FormatRegistry
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.PermanentImportInputException
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.RoomImportReceiptStore
import com.adsamcik.tracker.impexp.importer.computeImportJobId
import com.adsamcik.tracker.impexp.importer.locationFileReview
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.archive.ArchiveEntryMetadata
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveClassification
import com.adsamcik.tracker.impexp.importer.archive.ZipArchiveExtractor
import com.adsamcik.tracker.impexp.importer.archive.SessionBundleStager
import com.adsamcik.tracker.impexp.importer.archive.SessionBundleMetadataImport
import com.adsamcik.tracker.impexp.importer.archive.PreparedSessionBundleMetadata
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.importer.file.DatabaseImportFailure
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.impexp.importer.file.ImportTransactionMode
import com.adsamcik.tracker.impexp.importer.file.PortableAmbientStepsFileImport
import com.adsamcik.tracker.impexp.importer.file.PortableStepsFileImport
import com.adsamcik.tracker.impexp.importer.file.PortableActivityFileImport
import com.adsamcik.tracker.impexp.importer.file.PortablePressureFileImport
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.extension.extension
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import java.io.IOException
import java.util.Locale
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Provider

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
	private val databaseProvider: Provider<AppDatabase>,
    private val dispatchers: DispatchersProvider,
	private val trackingStartupGate: TrackingStartupGate,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
) : CoroutineWorker(context, workerParams) {

    private val import = DataImport()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	private lateinit var database: AppDatabase
	private lateinit var importJobRunner: ImportJobRunner
	private var startupGeneration: Long = -1L
	private var originalDataEpoch: Long = -1L

    private var errorCount: Int = 0

    override suspend fun doWork(): Result = withContext(dispatchers.io) {
        doWorkOnIo()
    }

    private suspend fun doWorkOnIo(): Result {
        val uriString = inputData.getString(ARG_FILE_URI) ?: return Result.failure()
		originalDataEpoch = inputData.getLong(ARG_ORIGINAL_DATA_EPOCH, -1L)
		if (originalDataEpoch < 0L) {
			showErrorNotification(context.getString(R.string.import_cleared_error))
			return Result.failure()
		}
        val uri = Uri.parse(uriString)
        val file = DocumentFile.fromSingleUri(context, uri) ?: return Result.failure()

		startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> {
				showErrorNotification("Import unavailable until tracking data recovery is complete.", allowBeforeReady =
                    true)
				return Result.failure()
			}
		}
		if (!trackingStartupGate.isImportGenerationReady(startupGeneration)) {
			return Result.success()
		}
		if (!isCurrentDataEpoch()) {
			showErrorNotification(context.getString(R.string.import_cleared_error))
			return Result.failure()
		}
		return trackingStartupGate.withReadyGenerationTransfer(startupGeneration) {
			runAdmittedImport(file)
		} ?: Result.success()
	}

	private suspend fun runAdmittedImport(file: DocumentFile): Result {
		database = databaseProvider.get()
		if (!trackingStartupGate.isImportGenerationReady(startupGeneration)) {
			return Result.success()
		}
		importJobRunner = ImportJobRunner(
			receiptStore = RoomImportReceiptStore(database),
			verifyCollectedDataAccess = ::requireCurrentImportAuthority,
			originalDataEpoch = originalDataEpoch,
		)

		showNotification(
			context.getString(R.string.import_notification_progress),
			true
		)

		return try {
			importSourceDocument(file)
		} catch (e: CancellationException) {
			throw e
		} catch (_: ImportWorkerStartupGenerationChangedException) {
			Result.success()
		} catch (_: ImportDataEpochChangedException) {
			showErrorNotification(context.getString(R.string.import_cleared_error))
			Result.failure()
		} catch (e: IOException) {
			when (val decision = classifyImportWorkerIOException(e)) {
				is ImportWorkerIoDecision.Terminal ->
					finishImport(decision.result)
				ImportWorkerIoDecision.Retry -> {
					showErrorNotification(context.getString(R.string.import_notification_error_io))
					Result.retry()
				}
			}
		} catch (e: Exception) {
			showErrorNotification(e.message ?: "Import failed.")
			Result.failure()
		}
	}

	private suspend fun importSourceDocument(file: DocumentFile): Result {
		val fileName = file.name ?: throw IOException("Missing import file name")
		return if (file.extension.equals(SessionBundleManifestCodecV1.EXTENSION, ignoreCase = true)) {
			SessionBundleStager().stageSource(context, file).use { source ->
				importPickedFile(source.document, fileName)
			}
		} else {
			importPickedFile(file, fileName)
		}
	}

	/** Session job identity and dispatch use the same owned copy; ordinary source imports retain their flow. */
	private suspend fun importPickedFile(file: DocumentFile, fileName: String): Result {
		val importContext = currentCoroutineContext()
		val jobId = computeImportJobId(context, file, importSourceReadLimit(file.extension)) {
			importContext.ensureActive()
		}
		requireCurrentImportAuthority()
		if (!importJobRunner.start(jobId, fileName, file.length())) {
			showNotification(context.buildNotificationText(ImportResult(skippedCount = 1)), false)
			return Result.success()
		}
		val result = handleFile(file, jobId)
		requireCurrentImportAuthority()
		importJobRunner.completeIfSuccessful(jobId, result)
		return finishImport(result)
	}

	private fun finishImport(importResult: ImportResult): Result {
		showNotification(context.buildNotificationText(importResult), false)
		if (importResult.failedCount > 0) {
			showErrorNotification(
				importResult.errors.singleOrNull()
					?.takeIf {
						it == DatabaseImportFailure.TrackerDatabaseRestoreRequired.message
					}
					?: context.getString(
						R.string.import_notification_error_records_failed,
						importResult.failedCount,
					),
			)
		}
		return importWorkerResult(importResult)
	}

    @AnyThread
    private fun showNotification(text: String, inProgress: Boolean) {
        runCatchingCancellable {
            val notification = context.createNotification(text, inProgress)
            if (startupGeneration < 0L) {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            else {
                trackingStartupGate.withRetainedDataGeneration(startupGeneration) {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            }
        }.getOrNull()
    }

    @AnyThread
    private fun showErrorNotification(text: String, allowBeforeReady: Boolean = false) {
        runCatchingCancellable {
            val notification = context.createNotification(text, false)
            if (allowBeforeReady || startupGeneration < 0L) {
                notificationManager.notify(NOTIFICATION_ERROR_BASE_ID + errorCount++, notification)
            }
            else {
                trackingStartupGate.withRetainedDataGeneration(startupGeneration) {
                notificationManager.notify(NOTIFICATION_ERROR_BASE_ID + errorCount++, notification)
            }
            }
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
			isImportableEntry = ::isImportableEntry,
			transactionModeForEntry = { stream ->
				importerFor(stream)?.transactionMode ?: ImportTransactionMode.WORKER_MANAGED
			},
            importEntry = ::tryImport,
        )
    }

    @WorkerThread
    private suspend fun tryImport(stream: FileImportStream): ImportResult {
		if (!isCurrentDataEpoch()) {
			throw IOException("Import stopped because recorded data was cleared")
		}
		stream.withOriginalDataEpoch(originalDataEpoch)
        val importer = importerFor(stream)

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
			trackingStartupGate.requireImportGenerationReady(startupGeneration)
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
		trackingStartupGate.requireImportGenerationReady(startupGeneration)
        val extension = file.extension?.lowercase(Locale.ROOT)
		if (extension == SessionBundleManifestCodecV1.EXTENSION) {
			return importSessionBundle(file, jobId)
		}
        val extractor = import.activeArchiveExtractorList
            .find { it.supportedExtensions.contains(extension) }

        return if (extractor != null) {
            extract(file, extractor, jobId)
        } else {
            importSingleFile(file, jobId)
        }
    }

	private suspend fun importSessionBundle(file: DocumentFile, jobId: String): ImportResult {
		return try { SessionBundleStager().stage(context, file).use { bundle ->
			var prepared: PreparedSessionBundleMetadata? = null
			requireCurrentImportAuthority()
			importJobRunner.importSessionBundle(jobId, bundle,
				transactionModeForChild = { child -> importerFor(child)?.transactionMode
					?: throw InvalidSessionBundle("Unsupported session source file") },
				importChild = ::tryImport,
				importMetadata = {
					requireCurrentImportAuthority()
					SessionBundleMetadataImport.publish(database, jobId, originalDataEpoch,
						System.currentTimeMillis(), checkNotNull(prepared))
						.also { requireCurrentImportAuthority() }
				}, prepareMetadata = {
					requireCurrentImportAuthority()
					prepared = SessionBundleMetadataImport.prepare(context, bundle)
					requireCurrentImportAuthority()
				})
		} } catch (failure: SQLiteException) {
			throw IOException("Session import storage is temporarily unavailable", failure)
		}
	}

	private suspend fun importSingleFile(file: DocumentFile, jobId: String): ImportResult {
    val fileName = file.name ?: throw IOException("Missing import file name")
    return file.openInputStream(context)?.use { inputStream ->
        importJobRunner.importSingle(
            jobId = jobId,
            stream = FileImportStream(
                fileName = fileName,
                receiptKey = DIRECT_ENTRY_KEY,
                streamProvider = { inputStream },
            ).also { stream -> inputData.locationFileReview()?.let(stream::withLocationReview) },
					transactionMode = FormatRegistry.importerForExtension(
						fileName.substringAfterLast('.', "").lowercase(Locale.ROOT),
					)?.transactionMode ?: ImportTransactionMode.WORKER_MANAGED,
            importEntry = ::tryImport,
        )
    } ?: throw IOException("Failed to open ${fileName}")
	}

	private suspend fun isCurrentDataEpoch(): Boolean =
		collectedDataLifecycleStore.snapshot().epoch == originalDataEpoch

	private suspend fun requireCurrentImportAuthority() {
		trackingStartupGate.requireImportGenerationReady(startupGeneration)
		if (!isCurrentDataEpoch()) {
			throw ImportDataEpochChangedException
		}
	}

    companion object {
        const val NOTIFICATION_ID: Int = 98784
        const val NOTIFICATION_ERROR_BASE_ID: Int = 98785
        const val ARG_FILE_URI: String = "filePath"
		const val ARG_ORIGINAL_DATA_EPOCH: String = "originalCollectedDataEpoch"
        private const val DIRECT_ENTRY_KEY: String = "direct-source-v1"
    }

	private object ImportDataEpochChangedException : RuntimeException()

}

internal sealed interface ImportWorkerIoDecision {
	data class Terminal(val result: ImportResult) : ImportWorkerIoDecision
	data object Retry : ImportWorkerIoDecision
}

internal fun classifyImportWorkerIOException(failure: IOException): ImportWorkerIoDecision =
	if (failure is PermanentImportInputException) {
		ImportWorkerIoDecision.Terminal(failure.toImportResult())
	} else if (failure is InvalidSessionBundle) {
		ImportWorkerIoDecision.Terminal(ImportResult(failedCount = 1,
			errors = listOf(failure.message ?: "Session file is invalid")))
	} else {
		ImportWorkerIoDecision.Retry
	}

internal fun importWorkerResult(result: ImportResult): ListenableWorker.Result =
	if (result.failedCount > 0) {
		ListenableWorker.Result.failure()
	} else {
		ListenableWorker.Result.success()
	}

// Fixed format-to-existing-size-limit mapping; unknown extensions retain the existing default.
@Suppress("CyclomaticComplexMethod")
internal fun importSourceReadLimit(extension: String?): Long = when {
	extension.equals(SessionBundleManifestCodecV1.EXTENSION, ignoreCase = true) ->
        SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES
	extension.equals("zip", ignoreCase = true) -> ZipArchiveExtractor.MAX_COMPRESSED_INPUT_BYTES
	extension.equals(PortableStepsFileImport.EXTENSION, ignoreCase = true) ->
		PortableStepsFileImport.MAX_FILE_BYTES
	extension.equals(PortableAmbientStepsFileImport.EXTENSION, ignoreCase = true) ->
		PortableAmbientStepsFileImport.MAX_FILE_BYTES
	extension.equals(PortableActivityFileImport.EXTENSION, ignoreCase = true) ->
		PortableActivityFileImport.MAX_FILE_BYTES
	extension.equals(PortablePressureFileImport.EXTENSION, ignoreCase = true) ->
		PortablePressureFileImport.MAX_FILE_BYTES
	extension.equals("trackerwifi", ignoreCase = true) ||
		extension.equals("trackercell", ignoreCase = true) ->
		com.adsamcik.tracker.impexp.portable.RadioSourceFileV2.MAX_FILE_BYTES
	extension.equals("trackerlocation", ignoreCase = true) ->
		com.adsamcik.tracker.impexp.portable.LocationFileJsonV1.MAX_FILE_BYTES
	else -> Long.MAX_VALUE
}

private fun Context.buildNotificationText(result: ImportResult): String {
    val successCount = result.successCount
    return if (result.skippedCount > 0) {
        this.getString(
            R.string.import_notification_finished_with_skipped,
            successCount,
            result.skippedCount
        )
    } else {
        this.resources.getQuantityString(
            R.plurals.import_notification_finished,
            successCount,
            successCount
        )
    }
}


private fun Context.createNotification(text: String, inProgress: Boolean): Notification =
    NotificationCompat.Builder(
        this,
        this.getString(com.adsamcik.tracker.shared.base.R.string.channel_other_id)
    )
        .setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
        .setOngoing(inProgress)
        .setContentTitle(text)
        .build()


private fun importerFor(stream: FileImportStream): FileImport? =
	importerForFileName(stream.fileName)


private fun isImportableEntry(entry: ArchiveEntryMetadata): Boolean =
	importerForFileName(entry.fileName) != null


private fun importerForFileName(fileName: String): FileImport? =
	FormatRegistry.importerForExtension(
		fileName.substringAfterLast('.', "").lowercase(Locale.ROOT),
	)


private fun TrackingStartupGate.isImportGenerationReady(generation: Long): Boolean =
	withRetainedDataGeneration(generation) { true } == true


private fun TrackingStartupGate.requireImportGenerationReady(generation: Long) {
	if (!isImportGenerationReady(generation)) {
		throw ImportWorkerStartupGenerationChangedException
	}
}


private object ImportWorkerStartupGenerationChangedException : RuntimeException()
