package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportReceiptContext
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsFormatException
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.portableAmbientStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.readPortableBytes
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableAmbientStepsImportEntryPoint {
	fun importPortableAmbientSteps(): ImportPortableAmbientSteps
	fun importPortableAmbientStepsV2(): ImportPortableAmbientStepsV2
	fun collectedDataLifecycleStore(): CollectedDataLifecycleStore
}

internal data class PortableAmbientStepsImportDependencies(
	val importer: ImportPortableAmbientSteps,
	val lifecycleStore: CollectedDataLifecycleStore,
	val importerV2: ImportPortableAmbientStepsV2 = object : ImportPortableAmbientStepsV2 {
		override suspend fun importArchive(
			request: ImportPortableAmbientStepsV2Request,
		): ImportPortableAmbientStepsResult = ImportPortableAmbientStepsResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository
				.PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID,
		)
	},
)

/** Strict one-archive `.trackerambientsteps` importer with source-owned Room admission. */
internal class PortableAmbientStepsFileImport(
	private val dependenciesProvider: (Context) -> PortableAmbientStepsImportDependencies = { context ->
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableAmbientStepsImportEntryPoint::class.java,
		)
		PortableAmbientStepsImportDependencies(
			importer = entryPoint.importPortableAmbientSteps(),
			lifecycleStore = entryPoint.collectedDataLifecycleStore(),
			importerV2 = entryPoint.importPortableAmbientStepsV2(),
		)
	},
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf(EXTENSION)
	override val transactionMode: ImportTransactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	): ImportResult {
		val fileReceipt = stream.importReceipt
			?: throw PortableAmbientStepsImportReceiptContextException()
		val bytes = try {
			readPortableBytes(stream, MAX_FILE_BYTES)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableStepsJsonException) {
			return ImportResult(
				failedCount = 1,
				errors = listOf(PERMANENT_FORMAT_ERROR),
			)
		}
		val dependencies = dependenciesProvider(context)
		return try {
			when (portableAmbientStepsSchemaVersion(bytes)) {
				AmbientStepsPortableFormatV1.SCHEMA_VERSION -> {
					val decoded = PortableAmbientStepsJsonV1Codec()
						.decode(ByteArrayInputStream(bytes))
					val request = ImportPortableAmbientStepsRequest(
						archive = decoded.archive,
						receipt = decoded.archive.toReceipt(fileReceipt),
						metadata = decoded.metadata,
						expectedCollectedDataEpoch = dependencies.lifecycleStore.snapshot().epoch,
					)
					dependencies.importer.importArchive(request)
						.toFileResult(decoded.archive.days.size)
				}
				else -> {
					val decoded = PortableAmbientStepsJsonV2Codec()
						.decode(ByteArrayInputStream(bytes))
					val request = ImportPortableAmbientStepsV2Request(
						archive = decoded.archive,
						receipt = decoded.archive.toReceipt(fileReceipt),
						metadata = decoded.metadata,
						expectedCollectedDataEpoch = dependencies.lifecycleStore.snapshot().epoch,
					)
					dependencies.importerV2.importArchive(request)
						.toFileResult(decoded.archive.days.size)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableAmbientStepsFormatException) {
			ImportResult(failedCount = 1, errors = listOf(PERMANENT_FORMAT_ERROR))
		}
	}

	private fun ImportPortableAmbientStepsResult.toFileResult(dayCount: Int): ImportResult =
		when (this) {
			is ImportPortableAmbientStepsResult.Applied ->
				ImportResult(successCount = this.dayCount)
			is ImportPortableAmbientStepsResult.Duplicate ->
				ImportResult(skippedCount = this.dayCount)
			is ImportPortableAmbientStepsResult.Blocked -> when (reason) {
				PortableAmbientStepsImportBlockedReason.RETENTION_BOUNDARY,
				PortableAmbientStepsImportBlockedReason.DELETED_DAY,
				PortableAmbientStepsImportBlockedReason.RETAINED_DAY,
				PortableAmbientStepsImportBlockedReason.SOURCE_DELETED,
				-> ImportResult(
					skippedCount = dayCount,
					errors = listOf(
						"Portable Ambient Steps archive is protected by ${reason.name.lowercase()}.",
					),
				)
				else -> ImportResult(
					failedCount = dayCount,
					errors = listOf(
						"Portable Ambient Steps archive was rejected: ${reason.name.lowercase()}.",
					),
				)
			}
			is ImportPortableAmbientStepsResult.Unverifiable -> ImportResult(
				failedCount = dayCount,
				errors = listOf(
					"Portable Ambient Steps archive cannot be verified: ${reason.name.lowercase()}.",
				),
			)
			is ImportPortableAmbientStepsResult.RetryableFailure ->
				throw PortableAmbientStepsRetryableImportException(reason)
		}

	internal companion object {
		const val EXTENSION = AmbientStepsPortableFormatV1.FILE_EXTENSION
		const val MAX_FILE_BYTES = AmbientStepsPortableFormatV1.MAX_FILE_BYTES
		const val PERMANENT_FORMAT_ERROR =
			"Portable Ambient Steps file is malformed, unsupported, or exceeds its limits."
	}
}

internal class PortableAmbientStepsImportReceiptContextException :
	IOException("Portable Ambient Steps import requires durable file-job receipt context.") {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

internal class PortableAmbientStepsRetryableImportException(
	reason: PortableAmbientStepsTransferRetryableReason,
) : IOException(
	"Portable Ambient Steps import is temporarily unavailable: ${reason.name.lowercase()}.",
) {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

private fun PortableAmbientStepsArchiveV1.toReceipt(
	fileReceipt: FileImportReceiptContext,
): PortableAmbientStepsImportReceipt = PortableAmbientStepsImportReceipt(
	jobId = fileReceipt.jobId,
	archiveKey = subordinateArchiveKey(fileReceipt.entryKey, identity.value),
	sourceName = fileReceipt.sourceName,
	receivedAtMs = fileReceipt.receivedAtMs,
)

private fun PortableAmbientStepsArchiveV2.toReceipt(
	fileReceipt: FileImportReceiptContext,
): PortableAmbientStepsImportReceipt = PortableAmbientStepsImportReceipt(
	jobId = fileReceipt.jobId,
	archiveKey = subordinateArchiveKey(fileReceipt.entryKey, identity.value),
	sourceName = fileReceipt.sourceName,
	receivedAtMs = fileReceipt.receivedAtMs,
)

private fun subordinateArchiveKey(fileEntryKey: String, archiveIdentity: String): String {
	val canonical = listOf(SUBORDINATE_RECEIPT_DOMAIN, fileEntryKey, archiveIdentity)
		.joinToString(separator = "") { "${it.length}:$it" }
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private const val SUBORDINATE_RECEIPT_DOMAIN =
	"tracker-portable-ambient-steps-subordinate-archive-receipt-v1"
