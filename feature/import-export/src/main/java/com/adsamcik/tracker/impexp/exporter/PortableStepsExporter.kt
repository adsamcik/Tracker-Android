package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
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

/** User-facing `.trackersteps` exporter over the exact source-local portable contract. */
internal class PortableStepsExporter(
	private val exporterProvider: (Context) -> ExportPortableStepsV2 = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableStepsExportEntryPoint::class.java,
		).exportPortableStepsV2()
	},
) : Exporter {
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
		val exporter = exporterProvider(context)
		val request = dateRange?.toPortableRequest() ?: FULL_HISTORY
		val result = PortableStepsJsonV2Codec().encode(outputStream) { sink ->
			exporter.export(request, sink)
		}
		return when (result) {
			is ExportPortableStepsResult.Exported -> ExportResult.Success(
				recordCount = result.entryCount,
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
