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
import com.adsamcik.tracker.impexp.importer.worker.ImportWorker
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

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

internal class ImportJobRunner(
	private val receiptStore: ImportReceiptStore,
	private val nowMs: () -> Long = System::currentTimeMillis,
) {
	suspend fun start(
		jobId: String,
		sourceName: String,
		sourceSizeBytes: Long,
	): Boolean = receiptStore.transaction {
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
		true
	}

	suspend fun importArchive(
		jobId: String,
		context: Context,
		file: DocumentFile,
		extractor: ArchiveExtractor,
		importEntry: suspend (FileImportStream) -> ImportResult,
	): ImportResult {
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
				) {
					importEntry(stream)
				}
			},
		)
		if (!opened) throw IOException("Failed to extract ${file.name ?: "archive"}")
		return aggregate
	}

	suspend fun importSingle(
		jobId: String,
		stream: FileImportStream,
		importEntry: suspend (FileImportStream) -> ImportResult,
	): ImportResult = processEntry(jobId, stream.receiptKey, stream.fileName) {
		importEntry(stream)
	}

	suspend fun completeIfSuccessful(jobId: String, result: ImportResult) {
		if (result.failedCount == 0) {
			receiptStore.transaction {
				receiptStore.markJobComplete(jobId, nowMs())
			}
		}
	}

	private suspend fun successfulEntryResult(jobId: String, entryKey: String): ImportResult? {
		val receipt = receiptStore.getEntry(jobId, entryKey)
			?.takeIf { it.status == ImportEntryReceiptEntity.STATUS_SUCCESS }
			?: return null
		return receipt.toImportResult()
	}

	private suspend fun processEntry(
		jobId: String,
		entryKey: String,
		entryName: String,
		importEntry: suspend () -> ImportResult,
	): ImportResult {
		successfulEntryResult(jobId, entryKey)?.let { return it }

		return try {
			receiptStore.transaction {
				successfulEntryResult(jobId, entryKey)?.let { return@transaction it }
				val result = importEntry()
				if (result.failedCount > 0) throw ImportEntryRollback(result)
				receiptStore.putEntry(
					result.toReceipt(
						jobId = jobId,
						entryKey = entryKey,
						entryName = entryName,
						status = ImportEntryReceiptEntity.STATUS_SUCCESS,
						updatedAt = nowMs(),
					)
				)
				result
			}
		} catch (rollback: ImportEntryRollback) {
			recordFailure(jobId, entryKey, entryName, rollback.result)
			rollback.result
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (failure: Exception) {
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

	private suspend fun recordFailure(
		jobId: String,
		entryKey: String,
		entryName: String,
		result: ImportResult,
	) {
		receiptStore.transaction {
			receiptStore.putEntry(
				result.toReceipt(
					jobId = jobId,
					entryKey = entryKey,
					entryName = entryName,
					status = ImportEntryReceiptEntity.STATUS_FAILURE,
					updatedAt = nowMs(),
				)
			)
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
