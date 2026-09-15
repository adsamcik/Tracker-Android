package com.adsamcik.tracker.impexp.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.importer.file.ImportTransactionMode
import com.adsamcik.tracker.impexp.importer.worker.ImportWorker
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exposes import start to other packages.
 */
object DataImporter {
	fun import(context: Context, fileUri: Uri) {
		persistReadPermission(context, fileUri)
		val workRequest = OneTimeWorkRequestBuilder<ImportWorker>()
			.setInputData(workDataOf(ImportWorker.ARG_FILE_URI to fileUri.toString()))
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			UNIQUE_WORK_NAME,
			ExistingWorkPolicy.APPEND_OR_REPLACE,
			workRequest,
		)
	}

	/** Stops a database-writing import before collected data is cleared. */
	fun cancel(context: Context): Operation =
		WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)

	internal fun persistReadPermission(context: Context, fileUri: Uri): Boolean {
		if (fileUri.scheme != ContentResolver.SCHEME_CONTENT) return false

		return runCatching {
			context.contentResolver.takePersistableUriPermission(
				fileUri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION
			)
			true
		}.getOrElse {
			false
		}
	}

	const val UNIQUE_WORK_NAME = "data-import"
}

private class ImportReceiptPreconditionException(message: String) : IOException(message)

internal class ImportJobRunner(
	private val receiptStore: ImportReceiptStore,
	private val verifyCollectedDataAccess: () -> Unit = {},
	private val nowMs: () -> Long = System::currentTimeMillis,
) {
	// One runner belongs to one uniquely scheduled ImportWorker. Completion cannot race its imports.
	private val operationMutex = Mutex()

	suspend fun start(
		jobId: String,
		sourceName: String,
		sourceSizeBytes: Long,
	): Boolean = operationMutex.withLock {
		verifyCollectedDataAccess()
		receiptStore.transaction {
			verifyCollectedDataAccess()
			if (receiptStore.getJob(jobId)?.status == ImportJobReceiptEntity.STATUS_COMPLETE) {
				return@transaction false
			}

			val now = nowMs()
			receiptStore.insertJob(
				ImportJobReceiptEntity(
					jobId = jobId,
					sourceName = sourceName,
					sourceSizeBytes = sourceSizeBytes,
					status = ImportJobReceiptEntity.STATUS_IN_PROGRESS,
					startedAt = now,
					updatedAt = now,
				)
			)
			receiptStore.markJobInProgress(jobId, sourceName, sourceSizeBytes, now)
			verifyCollectedDataAccess()
			true
		}
	}

	suspend fun importArchive(
		jobId: String,
		context: Context,
		file: DocumentFile,
		extractor: ArchiveExtractor,
		transactionModeForEntry: (FileImportStream) -> ImportTransactionMode = {
			ImportTransactionMode.WORKER_MANAGED
		},
		importEntry: suspend (FileImportStream) -> ImportResult,
	): ImportResult = operationMutex.withLock {
		var aggregate = ImportResult.EMPTY
		val opened = extractor.extract(
			context = context,
			file = file,
			shouldExtract = { entry ->
				val previous = successfulEntryResult(jobId, entry.receiptKey)
				if (previous != null) aggregate += previous
				previous == null
			},
			consume = { stream ->
				aggregate += processEntry(
					jobId = jobId,
					entryKey = stream.receiptKey,
					entryName = stream.fileName,
					transactionMode = transactionModeForEntry(stream),
				) {
					importEntry(receiptBoundStream(jobId, stream))
				}
			},
		)
		if (!opened) throw IOException("Failed to extract ${file.name ?: "archive"}")
		aggregate
	}

	suspend fun importSingle(
		jobId: String,
		stream: FileImportStream,
		transactionMode: ImportTransactionMode = ImportTransactionMode.WORKER_MANAGED,
		importEntry: suspend (FileImportStream) -> ImportResult,
	): ImportResult = operationMutex.withLock {
		processEntry(jobId, stream.receiptKey, stream.fileName, transactionMode) {
			importEntry(receiptBoundStream(jobId, stream))
		}
	}

	private suspend fun receiptBoundStream(jobId: String, stream: FileImportStream): FileImportStream {
		verifyCollectedDataAccess()
		val job = receiptStore.getJob(jobId)
			?: throw ImportReceiptPreconditionException("Source import requires its durable file-job receipt")
		if (job.jobId != jobId || job.status != ImportJobReceiptEntity.STATUS_IN_PROGRESS) {
			throw ImportReceiptPreconditionException("Source import requires the current in-progress file job")
		}
		verifyCollectedDataAccess()
		return stream.withImportReceipt(jobId, job.startedAt)
	}

	suspend fun completeIfSuccessful(jobId: String, result: ImportResult): Unit = operationMutex.withLock {
		if (result.failedCount == 0) {
			verifyCollectedDataAccess()
			receiptStore.transaction {
				verifyCollectedDataAccess()
				receiptStore.markJobComplete(jobId, nowMs())
				verifyCollectedDataAccess()
			}
		}
	}

	private suspend fun successfulEntryResult(jobId: String, entryKey: String): ImportResult? {
		verifyCollectedDataAccess()
		val receipt = receiptStore.getEntry(jobId, entryKey)
			?.takeIf { it.status == ImportEntryReceiptEntity.STATUS_SUCCESS }
			?: return null
		return receipt.toImportResult()
	}

	private suspend fun processEntry(
		jobId: String,
		entryKey: String,
		entryName: String,
		transactionMode: ImportTransactionMode,
		importEntry: suspend () -> ImportResult,
	): ImportResult {
		successfulEntryResult(jobId, entryKey)?.let { return it }

		return try {
			when (transactionMode) {
				ImportTransactionMode.WORKER_MANAGED -> workerManagedEntry(
					jobId = jobId,
					entryKey = entryKey,
					entryName = entryName,
					importEntry = importEntry,
				)
				ImportTransactionMode.IMPORTER_MANAGED -> importerManagedEntry(
					jobId = jobId,
					entryKey = entryKey,
					entryName = entryName,
					importEntry = importEntry,
				)
			}
		} catch (precondition: ImportReceiptPreconditionException) {
			throw precondition
		} catch (rollback: ImportEntryRollback) {
			recordFailure(jobId, entryKey, entryName, rollback.result)
			rollback.result
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (failure: Exception) {
			// If the owning worker's startup generation changed, leave the transaction rolled
			// back and let that fence escape instead of recording a stale failure receipt.
			verifyCollectedDataAccess()
			val result = ImportResult(
				failedCount = 1,
				errors = listOfNotNull(failure.message),
			)
			try {
				recordFailure(jobId, entryKey, entryName, result)
			} catch (receiptFailure: Exception) {
				failure.addSuppressed(receiptFailure)
			}
			throw failure
		}
	}

	private suspend fun workerManagedEntry(
		jobId: String,
		entryKey: String,
		entryName: String,
		importEntry: suspend () -> ImportResult,
	): ImportResult {
		verifyCollectedDataAccess()
		return receiptStore.transaction {
			verifyCollectedDataAccess()
			successfulEntryResult(jobId, entryKey)?.let { return@transaction it }
			val result = importEntry()
			verifyCollectedDataAccess()
			if (result.failedCount > 0) throw ImportEntryRollback(result)
			receiptStore.putEntry(result.successReceipt(jobId, entryKey, entryName))
			verifyCollectedDataAccess()
			result
		}
	}

	private suspend fun importerManagedEntry(
		jobId: String,
		entryKey: String,
		entryName: String,
		importEntry: suspend () -> ImportResult,
	): ImportResult {
		// The source-local importer may acquire non-Room locks before opening its own transaction.
		// A successful receipt is therefore a separate replay marker written only after that work.
		verifyCollectedDataAccess()
		val result = importEntry()
		verifyCollectedDataAccess()
		if (result.failedCount > 0) throw ImportEntryRollback(result)
		return receiptStore.transaction {
			verifyCollectedDataAccess()
			successfulEntryResult(jobId, entryKey)?.let { return@transaction it }
			receiptStore.putEntry(result.successReceipt(jobId, entryKey, entryName))
			verifyCollectedDataAccess()
			result
		}
	}

	private fun ImportResult.successReceipt(
		jobId: String,
		entryKey: String,
		entryName: String,
	): ImportEntryReceiptEntity = toReceipt(
		jobId = jobId,
		entryKey = entryKey,
		entryName = entryName,
		status = ImportEntryReceiptEntity.STATUS_SUCCESS,
		updatedAt = nowMs(),
	)

	private suspend fun recordFailure(
		jobId: String,
		entryKey: String,
		entryName: String,
		result: ImportResult,
	) {
		verifyCollectedDataAccess()
		receiptStore.transaction {
			verifyCollectedDataAccess()
			receiptStore.putEntry(
				result.toReceipt(
					jobId = jobId,
					entryKey = entryKey,
					entryName = entryName,
					status = ImportEntryReceiptEntity.STATUS_FAILURE,
					updatedAt = nowMs(),
				)
			)
			verifyCollectedDataAccess()
		}
	}

	private class ImportEntryRollback(val result: ImportResult) : RuntimeException()
}

internal interface ImportReceiptStore {
	suspend fun <T> transaction(block: suspend () -> T): T
	suspend fun getJob(jobId: String): ImportJobReceiptEntity?
	suspend fun insertJob(job: ImportJobReceiptEntity)
	suspend fun markJobInProgress(
		jobId: String,
		sourceName: String,
		sourceSizeBytes: Long,
		updatedAt: Long,
	)
	suspend fun markJobComplete(jobId: String, completedAt: Long)
	suspend fun getEntry(jobId: String, entryKey: String): ImportEntryReceiptEntity?
	suspend fun putEntry(entry: ImportEntryReceiptEntity)
}

internal class RoomImportReceiptStore(
	private val database: AppDatabase,
) : ImportReceiptStore {
	private val dao get() = database.importReceiptDao()

	override suspend fun <T> transaction(block: suspend () -> T): T =
		database.withTransaction { block() }

	override suspend fun getJob(jobId: String): ImportJobReceiptEntity? = dao.getJob(jobId)

	override suspend fun insertJob(job: ImportJobReceiptEntity) {
		dao.insertJob(job)
	}

	override suspend fun markJobInProgress(
		jobId: String,
		sourceName: String,
		sourceSizeBytes: Long,
		updatedAt: Long,
	) {
		dao.markJobInProgress(jobId, sourceName, sourceSizeBytes, updatedAt)
	}

	override suspend fun markJobComplete(jobId: String, completedAt: Long) {
		dao.markJobComplete(jobId, completedAt)
	}

	override suspend fun getEntry(
		jobId: String,
		entryKey: String,
	): ImportEntryReceiptEntity? = dao.getEntry(jobId, entryKey)

	override suspend fun putEntry(entry: ImportEntryReceiptEntity) {
		dao.putEntry(entry)
	}
}

internal fun computeImportJobId(
	context: Context,
	file: DocumentFile,
	maxBytes: Long = Long.MAX_VALUE,
): String {
	val extension = file.name.orEmpty().substringAfterLast('.', "").lowercase()
	val digest = MessageDigest.getInstance("SHA-256")
	digest.update("tracker-import-job-v1\u0000".toByteArray())
	digest.update(extension.toByteArray())
	digest.update(0)
	val input = file.openInputStream(context)
		?: throw IOException("Failed to open ${file.name ?: "import source"}")
	input.use {
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var totalBytes = 0L
		while (true) {
			val read = it.read(buffer)
			if (read <= 0) break
			totalBytes += read
			if (totalBytes > maxBytes) {
				throw IOException("Import source exceeds size limit ($maxBytes bytes).")
			}
			digest.update(buffer, 0, read)
		}
	}
	return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun ImportResult.toReceipt(
	jobId: String,
	entryKey: String,
	entryName: String,
	status: String,
	updatedAt: Long,
): ImportEntryReceiptEntity = ImportEntryReceiptEntity(
	jobId = jobId,
	entryKey = entryKey,
	entryName = entryName,
	status = status,
	successCount = successCount,
	skippedCount = skippedCount,
	failedCount = failedCount,
	errorMessage = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")?.take(MAX_RECEIPT_ERROR_LENGTH),
	updatedAt = updatedAt,
)

private fun ImportEntryReceiptEntity.toImportResult(): ImportResult = ImportResult(
	successCount = successCount,
	skippedCount = skippedCount,
	failedCount = failedCount,
	errors = errorMessage?.let(::listOf).orEmpty(),
)

private const val MAX_RECEIPT_ERROR_LENGTH = 4_000
