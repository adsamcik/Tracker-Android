package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV2
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolves the read-only Steps exporter used by the application singleton graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableStepsExportEntryPoint {
	fun exportPortableSteps(): ExportPortableSteps
	fun exportPortableStepsV2(): ExportPortableStepsV2
}

/** Prefers authenticated v2 and falls back to canonical v1 before writing any destination bytes. */
@Keep // Export activity reconstructs the selected class through its public no-argument constructor.
internal class PortableStepsExporter(
	private val legacyExporterProvider: (Context) -> ExportPortableSteps = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableStepsExportEntryPoint::class.java,
		).exportPortableSteps()
	},
	private val v2MaximumBytes: Long = StepsPortableFormatV2.MAX_FILE_BYTES,
	private val v1MaximumBytes: Long = StepsPortableFormatV1.MAX_FILE_BYTES,
	private val exporterProvider: (Context) -> ExportPortableStepsV2 = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableStepsExportEntryPoint::class.java,
		).exportPortableStepsV2()
	},
) : Exporter {
	init {
		require(v2MaximumBytes in 1..StepsPortableFormatV2.MAX_FILE_BYTES)
		require(v1MaximumBytes in 1..StepsPortableFormatV1.MAX_FILE_BYTES)
	}

	override val requiresLocationData: Boolean = false
	override val containsSensitiveLocationData: Boolean = false
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = MIME_TYPE
	override val extension: String = EXTENSION

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		val request = dateRange?.toPortableRequest() ?: FULL_HISTORY
		return exportStaged(context, outputStream, verifySelectedContents = false,
			encodeV2 = { output ->
				PortableStepsJsonV2Codec().encode(output) { sink ->
					exporterProvider(context).export(request, sink)
				}
			},
			encodeV1 = { output ->
				PortableStepsJsonV1Codec().encode(output) { sink ->
					legacyExporterProvider(context).export(request, sink)
				}
			})
	}

	/**
	 * Backend prerequisite for the shared session bundle: exact saved records only, never dates.
	 * A failure after copying requires the caller to discard its destination; the caller owns the
	 * enclosing retained-data transfer, I/O dispatcher, destination closure and final handoff.
	 */
	internal suspend fun exportSession(
		context: Context,
		request: ExportSelectedPortableStepsRequest,
		outputStream: OutputStream,
	): ExportResult {
		currentCoroutineContext().ensureActive()
		val selection = request.copy(nativeSegmentIds = request.nativeSegmentIds.toList(),
			importedEntryIdentities = request.importedEntryIdentities.toList())
		return exportStaged(context, outputStream, verifySelectedContents = true,
			encodeV2 = { output ->
				PortableStepsJsonV2Codec().encode(output) { sink ->
					exporterProvider(context).export(selection, sink)
				}
			},
			encodeV1 = { output ->
				PortableStepsJsonV1Codec().encode(output) { sink ->
					legacyExporterProvider(context).export(selection, sink)
				}
			})
	}

	/** The same codec closure stages and rechecks the chosen format without changing selection. */
	private suspend fun exportStaged(
		context: Context,
		outputStream: OutputStream,
		verifySelectedContents: Boolean,
		encodeV2: suspend (OutputStream) -> ExportPortableStepsResult,
		encodeV1: suspend (OutputStream) -> ExportPortableStepsResult,
	): ExportResult {
		val result = stageAndCopy(context, outputStream, "portable-steps-v2-", v2MaximumBytes,
			verifySelectedContents, encodeV2)
		val finalResult: ExportPortableStepsResult = if (
			result == null ||
			result is ExportPortableStepsResult.Unverifiable &&
				result.reason == PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE
		) {
			stageAndCopy(context, outputStream, "portable-steps-v1-", v1MaximumBytes,
				verifySelectedContents, encodeV1) ?: return ExportResult.Error(
				LocalizedString(R.string.export_error_portable_steps_write))
		} else {
			requireNotNull(result)
		}
		return when (finalResult) {
			is ExportPortableStepsResult.Exported -> ExportResult.Success(
				recordCount = finalResult.entryCount,
			)
			ExportPortableStepsResult.NoEntries -> ExportResult.Error(
				LocalizedString(R.string.export_error_no_portable_steps),
			)
			is ExportPortableStepsResult.Unverifiable -> ExportResult.Error(
				LocalizedString(R.string.export_error_portable_steps_unverifiable),
			)
			is ExportPortableStepsResult.RetryableFailure -> ExportResult.Error(
				LocalizedString(R.string.export_error_portable_steps_retryable),
			)
		}
	}

	/** Returns null only for the existing encoded-size fallback, before destination bytes. */
	private suspend fun stageAndCopy(
		context: Context,
		outputStream: OutputStream,
		prefix: String,
		maximumBytes: Long,
		verifySelectedContents: Boolean,
		encode: suspend (OutputStream) -> ExportPortableStepsResult,
	): ExportPortableStepsResult? = FileBackedExportSpool(context, prefix, maximumBytes).use { spool ->
		val digest = if (verifySelectedContents) {
			MessageDigest.getInstance("SHA-256")
		} else {
			null
		}
		when (val staged = spool.stage { output ->
			encode(if (digest == null) {
				output
			} else {
				DigestOutputStream(output, digest)
			})
		}) {
			is FileBackedExportStage.Complete -> {
				copyCompletedStage(spool, outputStream, staged.value, digest, encode)
			}
			FileBackedExportStage.EncodedSizeExceeded -> null
		}
	}

	private suspend fun copyCompletedStage(
		spool: FileBackedExportSpool, outputStream: OutputStream, result: ExportPortableStepsResult,
		digest: MessageDigest?, encode: suspend (OutputStream) -> ExportPortableStepsResult,
	): ExportPortableStepsResult {
		if (result !is ExportPortableStepsResult.Exported) {
			return result
		}
		if (spool.byteCount == 0L) {
			throw PortableStepsJsonException("Successful Portable Steps export wrote no bytes")
		}
		val expectedHash = digest?.digest()
		if (expectedHash != null) {
			verifyContents(result, expectedHash, encode)?.let { return it }
		}
		spool.copyTo(outputStream)
		if (expectedHash != null) {
			verifyContents(result, expectedHash, encode)?.let { return it }
		}
		return result
	}

	/** Read-only re-encoding includes original graphs, corrections, identities and source bounds. */
	private suspend fun verifyContents(
		expectedResult: ExportPortableStepsResult.Exported,
		expectedHash: ByteArray,
		encode: suspend (OutputStream) -> ExportPortableStepsResult,
	): ExportPortableStepsResult? {
		currentCoroutineContext().ensureActive()
		val digest = MessageDigest.getInstance("SHA-256")
		val discarded = object : OutputStream() {
			override fun write(value: Int) = Unit
			override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
		}
		val current = encode(DigestOutputStream(discarded, digest))
		currentCoroutineContext().ensureActive()
		return when {
			current is ExportPortableStepsResult.RetryableFailure -> current
			current == expectedResult && digest.digest().contentEquals(expectedHash) -> null
			else -> ExportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		}
	}

	internal companion object {
		const val EXTENSION = StepsPortableFormatV1.FILE_EXTENSION
		const val MIME_TYPE = StepsPortableFormatV1.MIME_TYPE
		val FULL_HISTORY = ExportPortableStepsRequest(0L, Long.MAX_VALUE)
	}
}

private fun LongRange.toPortableRequest(): ExportPortableStepsRequest {
	require(first >= 0L && !isEmpty()) { "Portable Steps export range must be non-empty and non-negative" }
	val exclusiveEnd = if (last == Long.MAX_VALUE) {
		Long.MAX_VALUE
	} else {
		Math.addExact(last, 1L)
	}
	return ExportPortableStepsRequest(first, exclusiveEnd)
}
