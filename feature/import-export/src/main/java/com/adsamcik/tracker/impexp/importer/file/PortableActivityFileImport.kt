package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonException
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityImportBlockedReason
import com.adsamcik.tracker.shared.base.database.PortableActivityImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableActivityImportEntryPoint {
	fun importPortableCapturedActivity(): ImportPortableCapturedActivity
}

/**
 * Strict `.trackeractivity` adapter.
 *
 * The whole bounded file is decoded and authenticated before the first source-local mutation.
 * Each entry then receives an exact receipt and its own replay-safe Room transaction.
 */
internal class PortableActivityFileImport(
	private val importerProvider: (Context) -> ImportPortableCapturedActivity = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableActivityImportEntryPoint::class.java,
		).importPortableCapturedActivity()
	},
	private val currentTimeMs: () -> Long = System::currentTimeMillis,
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf(EXTENSION)
	override val transactionMode: ImportTransactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	): ImportResult {
		val envelope = PortableActivityJsonV1Codec().decode(stream)
		val state = try {
			database.sourceEvidenceStateDao().get()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			throw PortableActivityRetryableImportException(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} ?: return ImportResult(
			failedCount = envelope.entries.size,
			errors = listOf("Portable Activity source evidence is unavailable."),
		)
		val receivedAtMs = currentTimeMs()
		if (receivedAtMs < 0L) {
			throw PortableActivityJsonException("Portable Activity receipt time is invalid")
		}
		val sourceName = stream.fileName
		if (sourceName.isBlank() ||
			sourceName.length > ActivityCapturedPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH
		) throw PortableActivityJsonException("Portable Activity source name is invalid")
		val jobId = receiptJobId(stream.receiptKey, envelope.contentChecksum.value, receivedAtMs)
		val importer = importerProvider(context)
		var aggregate = ImportResult.EMPTY
		envelope.entries.forEachIndexed { index, entry ->
			try {
				aggregate += importer.importEntry(
					ImportPortableCapturedActivityRequest(
						entry = entry,
						receipt = PortableActivityImportReceipt(
							jobId = jobId,
							entryKey = "$index:${entry.identity.value}",
							sourceName = sourceName,
							receivedAtMs = receivedAtMs,
						),
						expectedCollectedDataEpoch = state.collectedDataEpoch,
					),
				).toFileResult()
			} catch (cancelled: CancellationException) {
				throw cancelled
			}
		}
		return aggregate
	}

	private fun ImportPortableCapturedActivityResult.toFileResult(): ImportResult = when (this) {
		is ImportPortableCapturedActivityResult.Applied -> ImportResult(successCount = 1)
		is ImportPortableCapturedActivityResult.Duplicate -> ImportResult(skippedCount = 1)
		is ImportPortableCapturedActivityResult.Blocked -> when (reason) {
			PortableActivityImportBlockedReason.DELETED_ENTRY,
			PortableActivityImportBlockedReason.DELETED_RUN,
			PortableActivityImportBlockedReason.DELETED_SCOPE,
			PortableActivityImportBlockedReason.RETENTION_BOUNDARY,
			-> ImportResult(
				skippedCount = 1,
				errors = listOf("Portable Activity entry is protected by ${reason.name.lowercase()}."),
			)
			else -> ImportResult(
				failedCount = 1,
				errors = listOf("Portable Activity entry was rejected: ${reason.name.lowercase()}."),
			)
		}
		is ImportPortableCapturedActivityResult.Unverifiable -> ImportResult(
			failedCount = 1,
			errors = listOf("Portable Activity entry cannot be verified: ${reason.name.lowercase()}."),
		)
		is ImportPortableCapturedActivityResult.RetryableFailure ->
			throw PortableActivityRetryableImportException(reason)
	}

	internal companion object {
		const val EXTENSION = ActivityCapturedPortableFormatV1.FILE_EXTENSION
		const val MAX_FILE_BYTES = ActivityCapturedPortableFormatV1.MAX_FILE_BYTES

		private fun receiptJobId(
			receiptKey: String,
			envelopeChecksum: String,
			receivedAtMs: Long,
		): String {
			if (receiptKey.isBlank()) {
				throw PortableActivityJsonException("Portable Activity receipt identity is missing")
			}
			val canonical = listOf(
				RECEIPT_DOMAIN,
				receiptKey,
				envelopeChecksum,
				receivedAtMs.toString(),
			)
				.joinToString(separator = "") { "${it.length}:$it" }
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}

		private const val RECEIPT_DOMAIN = "tracker-portable-activity-file-receipt-v1"
	}
}

internal class PortableActivityRetryableImportException(
	reason: PortableActivityTransferRetryableReason,
) : IOException("Portable Activity import is temporarily unavailable: ${reason.name.lowercase()}.") {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}
