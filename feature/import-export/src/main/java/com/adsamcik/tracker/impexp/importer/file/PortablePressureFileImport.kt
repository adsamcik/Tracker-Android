package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.MessageDigest

/** Resolves Pressure import authority and the durable collected-data epoch from one graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortablePressureImportEntryPoint {
	fun importPortablePressure(): ImportPortablePressure
	fun collectedDataLifecycleStore(): CollectedDataLifecycleStore
}

internal data class PortablePressureImportDependencies(
	val importer: ImportPortablePressure,
	val lifecycleStore: CollectedDataLifecycleStore,
)

/**
 * Strict `.trackerpressure` adapter using source-managed per-entry transactions.
 *
 * The shared worker does not expose its content-addressed job id or start time to a [FileImport].
 * This adapter therefore derives a bounded stable receipt namespace from the authenticated entry,
 * source name, and [FileImportStream.receiptKey], and uses received-at zero as an explicit
 * "durable worker timestamp unavailable" sentinel. These hashes provide stable replay identity and
 * content integrity only; they grant no live provider, capture, or lifecycle authority.
 *
 * A valid prefix may commit before a later malformed entry. Replay is safe because every entry uses
 * the same deterministic source receipt and the authoritative importer handles duplicate receipts.
 */
internal class PortablePressureFileImport(
	private val dependenciesProvider: (Context) -> PortablePressureImportDependencies = { context ->
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortablePressureImportEntryPoint::class.java,
		)
		PortablePressureImportDependencies(
			importer = entryPoint.importPortablePressure(),
			lifecycleStore = entryPoint.collectedDataLifecycleStore(),
		)
	},
	private val codec: PortablePressureJsonV1Codec = PortablePressureJsonV1Codec(),
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf(EXTENSION)
	override val transactionMode: ImportTransactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	): ImportResult {
		val dependencies = dependenciesProvider(context)
		var aggregate = ImportResult.EMPTY
		codec.decode(stream) { entry ->
			val request = ImportPortablePressureRequest(
				entry = entry,
				receipt = PortablePressureFileReceipt.create(stream, entry),
				expectedCollectedDataEpoch = dependencies.lifecycleStore.snapshot().epoch,
			)
			aggregate += dependencies.importer.importEntry(request).toFileResult()
		}
		return aggregate
	}

	private fun ImportPortablePressureResult.toFileResult(): ImportResult = when (this) {
		is ImportPortablePressureResult.Applied -> ImportResult(successCount = 1)
		is ImportPortablePressureResult.Duplicate -> ImportResult(skippedCount = 1)
		is ImportPortablePressureResult.Blocked -> when (reason) {
			PortablePressureImportBlockedReason.DELETED_ENTRY,
			PortablePressureImportBlockedReason.DELETED_RUN,
			-> ImportResult(
				skippedCount = 1,
				errors = listOf(
					"Portable Pressure entry is protected by retained deletion authority.",
				),
			)
			else -> ImportResult(
				failedCount = 1,
				errors = listOf(
					"Portable Pressure entry was blocked: ${reason.name.lowercase()}.",
				),
			)
		}
		is ImportPortablePressureResult.Unverifiable -> ImportResult(
			failedCount = 1,
			errors = listOf(
				"Portable Pressure entry cannot be verified: ${reason.name.lowercase()}.",
			),
		)
		is ImportPortablePressureResult.RetryableFailure ->
			throw PortablePressureRetryableImportException(reason)
	}

	internal companion object {
		const val EXTENSION = PressurePortableFormatV1.FILE_EXTENSION
		const val MAX_FILE_BYTES = PortablePressureJsonV1Codec.MAX_FILE_BYTES
	}
}

/** Transient source-local refusal; the owning WorkManager path maps [IOException] to retry. */
internal class PortablePressureRetryableImportException(
	reason: PortablePressureTransferRetryableReason,
) : IOException("Portable Pressure import is temporarily unavailable: ${reason.name.lowercase()}.") {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

private object PortablePressureFileReceipt {
	private const val JOB_NAMESPACE = "tracker-portable-pressure-file-job-v1"
	private const val ENTRY_NAMESPACE = "tracker-portable-pressure-file-entry-v1"
	private const val HASH_PREFIX = "sha256:"
	private const val UNKNOWN_RECEIVED_AT_MS = 0L

	fun create(
		stream: FileImportStream,
		entry: PortablePressureEntryV1,
	): PortablePressureImportReceipt {
		val sourceName = boundedField("pressure-source", stream.fileName)
		val entryKey = boundedField("pressure-entry", stream.receiptKey)
		val jobId = digest(
			JOB_NAMESPACE,
			listOf(
				PressurePortableFormatV1.FORMAT,
				PressurePortableFormatV1.SCHEMA_VERSION.toString(),
				sourceName,
				entryKey,
				entry.identity.value,
				entry.contentChecksum.value,
			),
		)
		return PortablePressureImportReceipt(
			jobId = jobId,
			entryKey = digest(
				ENTRY_NAMESPACE,
				listOf(entryKey, entry.identity.value),
			),
			sourceName = sourceName,
			receivedAtMs = UNKNOWN_RECEIVED_AT_MS,
		)
	}

	private fun boundedField(namespace: String, value: String): String =
		value.takeIf {
			it.isNotBlank() &&
				it.length <= PressurePortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH
		} ?: digest(namespace, listOf(value))

	private fun digest(namespace: String, values: List<String>): String {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update(namespace.toByteArray(Charsets.UTF_8))
		values.forEach { value ->
			digest.update(0)
			digest.update(value.toByteArray(Charsets.UTF_8))
		}
		return HASH_PREFIX + digest.digest().joinToString("") { byte ->
			(byte.toInt() and 0xff).toString(16).padStart(2, '0')
		}
	}
}
