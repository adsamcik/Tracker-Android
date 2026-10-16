package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolves the read-only Pressure exporter used by the application singleton graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortablePressureExportEntryPoint {
	fun exportPortablePressure(): ExportPortablePressure
}

/** User-facing `.trackerpressure` export over authenticated Pressure product evidence. */
@Keep // Export activity reconstructs the selected class through its public no-argument constructor.
internal class PortablePressureExporter(
	private val exporterProvider: (Context) -> ExportPortablePressure = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortablePressureExportEntryPoint::class.java,
		).exportPortablePressure()
	},
	private val codec: PortablePressureJsonV1Codec = PortablePressureJsonV1Codec(),
) : Exporter {
	override val requiresLocationData: Boolean = false

	/**
	 * The format contains no coordinates, but exact Pressure and timing can disclose sensitive
	 * environmental/location context and therefore retains the export warning.
	 */
	override val containsSensitiveLocationData: Boolean = true
	override val sensitivityTitleRes: Int = R.string.export_pressure_sensitivity_title
	override val sensitivityMessageRes: Int = R.string.export_pressure_sensitivity_message
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = MIME_TYPE
	override val extension: String = EXTENSION

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		val request = dateRange?.toPortablePressureRequest() ?: FULL_HISTORY
		return codec.encode(outputStream) { sink ->
			exporterProvider(context).export(request, sink)
		}.toExportResult()
	}

	/**
	 * Exact saved records for the shared bundle. The caller owns the I/O dispatcher, retained-data
	 * transfer, destination closure/discard on failure and final handoff; no tracking is started.
	 */
	internal suspend fun exportSession(
		context: Context,
		request: ExportSelectedPortablePressureRequest,
		outputStream: OutputStream,
	): ExportResult {
		currentCoroutineContext().ensureActive()
		val selection = request.copy(nativeSegmentIds = request.nativeSegmentIds.toList(),
			importedEntryIdentities = request.importedEntryIdentities.toList())
		val encode: suspend (OutputStream) -> ExportPortablePressureResult = { output ->
			codec.encode(output) { sink -> exporterProvider(context).export(selection, sink) }
		}
		val result = FileBackedExportSpool(context, "portable-pressure-v1-",
			PortablePressureJsonV1Codec.MAX_FILE_BYTES).use { spool ->
			val digest = MessageDigest.getInstance("SHA-256")
			val staged = spool.write { encode(DigestOutputStream(it, digest)) }
			if (staged !is ExportPortablePressureResult.Exported) {
				return@use staged
			}
			val expectedHash = digest.digest()
			verifyContents(staged, expectedHash, encode)?.let { return@use it }
			spool.copyTo(outputStream)
			verifyContents(staged, expectedHash, encode) ?: staged
		}
		return result.toExportResult()
	}

	/** Uses the same codec and exact selection; unrelated source revisions are not inputs. */
	private suspend fun verifyContents(
		expectedResult: ExportPortablePressureResult.Exported,
		expectedHash: ByteArray,
		encode: suspend (OutputStream) -> ExportPortablePressureResult,
	): ExportPortablePressureResult? {
		currentCoroutineContext().ensureActive()
		val digest = MessageDigest.getInstance("SHA-256")
		val discarded = object : OutputStream() {
			override fun write(value: Int) = Unit
			override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
		}
		val current = encode(DigestOutputStream(discarded, digest))
		currentCoroutineContext().ensureActive()
		return when {
			current is ExportPortablePressureResult.RetryableFailure -> current
			current == expectedResult && digest.digest().contentEquals(expectedHash) -> null
			else -> ExportPortablePressureResult.RetryableFailure(
				PortablePressureTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		}
	}

	private fun ExportPortablePressureResult.toExportResult(): ExportResult = when (this) {
		is ExportPortablePressureResult.Exported -> ExportResult.Success(
			recordCount = entryCount,
		)
		ExportPortablePressureResult.NoEntries -> ExportResult.Error(
			LocalizedString(R.string.export_error_no_portable_pressure),
		)
		is ExportPortablePressureResult.Unverifiable -> when (reason) {
			PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE ->
				ExportResult.Error(
					LocalizedString(R.string.export_error_portable_pressure_data_pruned),
				)
			else -> ExportResult.Error(
				LocalizedString(R.string.export_error_portable_pressure_unverifiable),
			)
		}
		is ExportPortablePressureResult.RetryableFailure -> ExportResult.Error(
			LocalizedString(R.string.export_error_portable_pressure_retryable),
		)
	}

	internal companion object {
		const val EXTENSION = PressurePortableFormatV1.FILE_EXTENSION
		const val MIME_TYPE = PressurePortableFormatV1.MIME_TYPE
		val FULL_HISTORY = ExportPortablePressureRequest(0L, Long.MAX_VALUE)
	}
}

private fun LongRange.toPortablePressureRequest(): ExportPortablePressureRequest {
	require(first >= 0L && !isEmpty()) {
		"Portable Pressure export range must be non-empty and non-negative"
	}
	val exclusiveEnd = if (last == Long.MAX_VALUE) {
		Long.MAX_VALUE
	} else {
		Math.addExact(last, 1L)
	}
	return ExportPortablePressureRequest(first, exclusiveEnd)
}
