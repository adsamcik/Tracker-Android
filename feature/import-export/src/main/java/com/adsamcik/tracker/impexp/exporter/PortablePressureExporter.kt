package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream

/** Resolves the read-only Pressure exporter used by the application singleton graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortablePressureExportEntryPoint {
	fun exportPortablePressure(): ExportPortablePressure
}

/** User-facing `.trackerpressure` export over authenticated Pressure product evidence. */
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
		return when (
			val result = codec.encode(outputStream) { sink ->
				exporterProvider(context).export(request, sink)
			}
		) {
			is ExportPortablePressureResult.Exported -> ExportResult.Success(
				recordCount = result.entryCount,
			)
			ExportPortablePressureResult.NoEntries -> ExportResult.Error(
				LocalizedString(R.string.export_error_no_portable_pressure),
			)
			is ExportPortablePressureResult.Unverifiable -> when (result.reason) {
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
	val exclusiveEnd = if (last == Long.MAX_VALUE) Long.MAX_VALUE else Math.addExact(last, 1L)
	return ExportPortablePressureRequest(first, exclusiveEnd)
}
