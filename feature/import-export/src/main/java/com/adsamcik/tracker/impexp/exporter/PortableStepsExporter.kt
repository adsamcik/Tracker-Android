package com.adsamcik.tracker.impexp.exporter

import android.content.Context
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
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV2
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream

/** Resolves the read-only Steps exporter used by the application singleton graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableStepsExportEntryPoint {
	fun exportPortableSteps(): ExportPortableSteps
	fun exportPortableStepsV2(): ExportPortableStepsV2
}

/** Prefers authenticated v2 and falls back to canonical v1 before writing any destination bytes. */
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
		val result = FileBackedExportSpool(
			context,
			"portable-steps-v2-",
			v2MaximumBytes,
		).use { spool ->
			when (val staged = spool.stage { spoolOutput ->
				PortableStepsJsonV2Codec().encode(spoolOutput) { sink ->
					exporterProvider(context).export(request, sink)
				}
			}) {
				is FileBackedExportStage.Complete -> {
					if (staged.value is ExportPortableStepsResult.Exported) {
						if (spool.byteCount == 0L) {
							throw PortableStepsJsonException(
								"Successful Portable Steps v2 export wrote no bytes",
							)
						}
						spool.copyTo(outputStream)
					}
					staged.value
				}
				FileBackedExportStage.EncodedSizeExceeded -> null
			}
		}
		val finalResult: ExportPortableStepsResult = if (
			result == null ||
			result is ExportPortableStepsResult.Unverifiable &&
				result.reason == PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE
		) {
			FileBackedExportSpool(
				context,
				"portable-steps-v1-",
				v1MaximumBytes,
			).use { spool ->
				when (val staged = spool.stage { spoolOutput ->
					PortableStepsJsonV1Codec().encode(spoolOutput) { sink ->
						legacyExporterProvider(context).export(request, sink)
					}
				}) {
					is FileBackedExportStage.Complete -> {
						if (staged.value is ExportPortableStepsResult.Exported) {
							spool.copyTo(outputStream)
						}
						staged.value
					}
					FileBackedExportStage.EncodedSizeExceeded -> return ExportResult.Error(
						LocalizedString(R.string.export_error_portable_steps_write),
					)
				}
			}
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

	internal companion object {
		const val EXTENSION = StepsPortableFormatV1.FILE_EXTENSION
		const val MIME_TYPE = StepsPortableFormatV1.MIME_TYPE
		val FULL_HISTORY = ExportPortableStepsRequest(0L, Long.MAX_VALUE)
	}
}

private fun LongRange.toPortableRequest(): ExportPortableStepsRequest {
	require(first >= 0L && !isEmpty()) { "Portable Steps export range must be non-empty and non-negative" }
	val exclusiveEnd = if (last == Long.MAX_VALUE) Long.MAX_VALUE else Math.addExact(last, 1L)
	return ExportPortableStepsRequest(first, exclusiveEnd)
}
