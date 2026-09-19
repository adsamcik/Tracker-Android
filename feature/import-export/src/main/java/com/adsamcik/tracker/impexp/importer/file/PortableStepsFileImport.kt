package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportReceiptContext
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.portableStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.readPortableBytes
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsV1WithReceipt
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** Resolves the source-local authoritative importer from the application graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableStepsImportEntryPoint {
	fun importPortableSteps(): ImportPortableSteps
	fun importPortableStepsV1WithReceipt(): ImportPortableStepsV1WithReceipt
	fun importPortableStepsV2(): ImportPortableStepsV2
}

internal data class PortableStepsImportDependencies(
	val legacyImporter: ImportPortableSteps,
	val receiptImporter: ImportPortableStepsV1WithReceipt,
	val v2Importer: ImportPortableStepsV2,
)

/** Strict `.trackersteps` adapter; the codec and source-local command retain their own authority. */
internal class PortableStepsFileImport(
	private val importerProvider: ((Context) -> ImportPortableSteps)? = null,
	private val dependenciesProvider: (Context) -> PortableStepsImportDependencies = { context ->
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableStepsImportEntryPoint::class.java,
		)
		PortableStepsImportDependencies(
			legacyImporter = entryPoint.importPortableSteps(),
			receiptImporter = entryPoint.importPortableStepsV1WithReceipt(),
			v2Importer = entryPoint.importPortableStepsV2(),
		)
	},
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf(EXTENSION)

	/** Day locks must be acquired before the importer's per-entry Room transaction. */
	override val transactionMode: ImportTransactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	): ImportResult {
		val fileReceipt = stream.importReceipt ?: throw PortableStepsImportReceiptContextException()
		val bytes = try {
			readPortableBytes(stream, MAX_FILE_BYTES)
		} catch (_: PortableStepsJsonException) {
			return permanentFormatFailure()
		}
		val dependencies = importerProvider?.invoke(context)?.let { legacy ->
			PortableStepsImportDependencies(
				legacyImporter = legacy,
				receiptImporter = object : ImportPortableStepsV1WithReceipt {
					override suspend fun importEntry(
						entry: PortableStepsEntryV1,
						receipt: PortableStepsImportReceipt,
						entryOrdinal: Int,
					): ImportPortableStepsResult = legacy.importEntry(entry)
				},
				v2Importer = object : ImportPortableStepsV2 {
					override suspend fun importEntry(
						request: ImportPortableStepsV2Request,
					): ImportPortableStepsResult =
						legacy.importEntry(request.entry.product)
				},
			)
		} ?: dependenciesProvider(context)
		var aggregate = ImportResult.EMPTY
		try {
			when (portableStepsSchemaVersion(bytes)) {
				StepsPortableFormatV1.SCHEMA_VERSION -> {
					var ordinal = 0
					PortableStepsJsonV1Codec().decode(ByteArrayInputStream(bytes)) { entry ->
						aggregate += dependencies.receiptImporter.importEntry(
							entry,
							fileReceipt.forEntry(entry),
							ordinal,
						).toFileResult()
						ordinal++
					}
				}
				else -> {
					val decoded = PortableStepsJsonV2Codec().decode(ByteArrayInputStream(bytes))
					decoded.archive.entries.forEachIndexed { ordinal, entry ->
						aggregate += dependencies.v2Importer.importEntry(
							ImportPortableStepsV2Request(
								archiveContentChecksum = decoded.archive.contentChecksum,
								entryOrdinal = ordinal,
								entry = entry,
								receipt = fileReceipt.forEntry(entry.product),
								metadata = decoded.metadata,
							),
						).toFileResult()
					}
				}
			}
		} catch (_: PortableStepsJsonException) {
			aggregate += permanentFormatFailure()
		}
		return aggregate
	}

	private fun permanentFormatFailure() = ImportResult(
		failedCount = 1,
		errors = listOf(PERMANENT_FORMAT_ERROR),
	)

	private fun ImportPortableStepsResult.toFileResult(): ImportResult = when (this) {
		is ImportPortableStepsResult.Applied -> ImportResult(successCount = 1)
		ImportPortableStepsResult.Duplicate -> ImportResult(skippedCount = 1)
		ImportPortableStepsResult.DeletedScope -> ImportResult(
			skippedCount = 1,
			errors = listOf("Portable Steps entry is protected by a deletion fence."),
		)
		ImportPortableStepsResult.OutsideRetention -> ImportResult(
			skippedCount = 1,
			errors = listOf("Portable Steps entry is outside the retained data window."),
		)
		is ImportPortableStepsResult.Conflict -> ImportResult(
			failedCount = 1,
			errors = listOf("Portable Steps entry conflicts with existing ${scope.name.lowercase()} identity."),
		)
		is ImportPortableStepsResult.Unverifiable -> ImportResult(
			failedCount = 1,
			errors = listOf("Portable Steps entry cannot be verified: ${reason.name.lowercase()}."),
		)
		is ImportPortableStepsResult.RetryableFailure -> throw PortableStepsRetryableImportException(reason)
	}

	internal class PortableStepsImportReceiptContextException :
		IOException("Portable Steps import requires durable file-job receipt context.") {
		private companion object {
			const val serialVersionUID: Long = 1L
		}
	}

	internal companion object {
		const val EXTENSION = StepsPortableFormatV1.FILE_EXTENSION
		const val MAX_FILE_BYTES = StepsPortableFormatV1.MAX_FILE_BYTES
		const val PERMANENT_FORMAT_ERROR =
			"Portable Steps file is malformed, unsupported, or exceeds its limits."
	}
}

/** Signals WorkManager to retry without collapsing a transient source-local refusal into failure. */
internal class PortableStepsRetryableImportException(
	reason: PortableStepsTransferRetryableReason,
) : IOException("Portable Steps import is temporarily unavailable: ${reason.name.lowercase()}.") {
	private companion object {
		const val serialVersionUID: Long = 1L
	}

	private fun FileImportReceiptContext.forEntry(
		entry: PortableStepsEntryV1,
	): PortableStepsImportReceipt = PortableStepsImportReceipt(
		jobId = jobId,
		entryKey = subordinateEntryKey(entryKey, entry.identity.value),
		sourceName = sourceName,
		receivedAtMs = receivedAtMs,
	)

	private fun subordinateEntryKey(fileEntryKey: String, entryIdentity: String): String {
		val canonical = listOf(
			"tracker-portable-steps-subordinate-entry-receipt-v2",
			fileEntryKey,
			entryIdentity,
		).joinToString(separator = "") { "${it.length}:$it" }
		return "sha256:" + MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}
