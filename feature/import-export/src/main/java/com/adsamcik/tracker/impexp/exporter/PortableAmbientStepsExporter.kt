package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsFormatException
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientStepsV2
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableAmbientStepsExportEntryPoint {
	fun exportPortableAmbientSteps(): ExportPortableAmbientSteps
	fun reexportImportedAmbientSteps(): ReexportImportedAmbientSteps
	fun exportPortableAmbientStepsV2(): ExportPortableAmbientStepsV2
	fun reexportImportedAmbientStepsV2(): ReexportImportedAmbientStepsV2
}

/** User-selected export that prefers authenticated v2 and falls back atomically to canonical v1. */
internal class PortableAmbientStepsExporter(
	private val origin: AmbientStepsPortableOrigin = AmbientStepsPortableOrigin.NATIVE,
	private val backendProvider: (Context) -> AmbientStepsPortableSourceBackend = { context ->
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableAmbientStepsExportEntryPoint::class.java,
		)
		AmbientStepsPortableSourceBackend(
			nativeExporter = entryPoint.exportPortableAmbientSteps(),
			importedReexporter = entryPoint.reexportImportedAmbientSteps(),
			nativeExporterV2 = entryPoint.exportPortableAmbientStepsV2(),
			importedReexporterV2 = entryPoint.reexportImportedAmbientStepsV2(),
		)
	},
	private val codec: PortableAmbientStepsJsonV2Codec = PortableAmbientStepsJsonV2Codec(),
	private val legacyCodec: PortableAmbientStepsJsonV1Codec = PortableAmbientStepsJsonV1Codec(),
) : Exporter {
	override val requiresLocationData: Boolean = false
	override val containsSensitiveLocationData: Boolean = true
	override val sensitivityTitleRes: Int = R.string.export_ambient_steps_sensitivity_title
	override val sensitivityMessageRes: Int = R.string.export_ambient_steps_sensitivity_message
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = AmbientStepsPortableFormatV1.MIME_TYPE
	override val extension: String = AmbientStepsPortableFormatV1.FILE_EXTENSION

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		val backend = backendProvider(context)
		val scope = dateRange?.toAmbientScope()
			?: AmbientStepsPortableFileScope.AllAvailableSnapshot
		val request = when (val resolution = backend.resolve(scope)) {
			is AmbientStepsPortableScopeResolution.Ready -> resolution.request
			is AmbientStepsPortableScopeResolution.Unsupported -> return ExportResult.Error(
				LocalizedString(R.string.export_error_portable_ambient_steps_scope),
			)
		}
		val sourceResult = try {
			val v2Bytes = ByteArrayOutputStream()
			val v2Result = codec.encode(v2Bytes) { sink ->
				backend.exportV2(origin, request, sink)
			}
			if (
				v2Result is ExportPortableAmbientStepsResult.Unverifiable &&
				v2Result.reason ==
				PortableAmbientStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE
			) {
				val v1Bytes = ByteArrayOutputStream()
				val fallback = legacyCodec.encode(v1Bytes) { sink ->
					backend.export(origin, request, sink)
				}
				if (fallback is ExportPortableAmbientStepsResult.Exported) {
					outputStream.write(v1Bytes.toByteArray())
				}
				fallback
			} else {
				if (v2Result is ExportPortableAmbientStepsResult.Exported) {
					outputStream.write(v2Bytes.toByteArray())
				}
				v2Result
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableAmbientStepsFormatException) {
			return ExportResult.Error(
				LocalizedString(R.string.export_error_portable_ambient_steps_write),
			)
		}
		return sourceResult.toExportResult()
	}

	private fun ExportPortableAmbientStepsResult.toExportResult(): ExportResult = when (this) {
		is ExportPortableAmbientStepsResult.Exported -> ExportResult.Success(recordCount = dayCount)
		ExportPortableAmbientStepsResult.NoData -> ExportResult.Error(
			LocalizedString(R.string.export_error_no_portable_ambient_steps),
		)
		is ExportPortableAmbientStepsResult.Unverifiable -> ExportResult.Error(
			LocalizedString(R.string.export_error_portable_ambient_steps_unverifiable),
		)
		is ExportPortableAmbientStepsResult.RetryableFailure -> ExportResult.Error(
			LocalizedString(R.string.export_error_portable_ambient_steps_retryable),
		)
	}

	internal companion object {
		const val FORMAT_ID = "portable-ambient-steps-v1"
	}
}

private fun LongRange.toAmbientScope(): AmbientStepsPortableFileScope.Range {
	val exclusiveEnd = if (last == Long.MAX_VALUE) Long.MAX_VALUE else last + 1L
	return AmbientStepsPortableFileScope.Range(first, exclusiveEnd)
}
