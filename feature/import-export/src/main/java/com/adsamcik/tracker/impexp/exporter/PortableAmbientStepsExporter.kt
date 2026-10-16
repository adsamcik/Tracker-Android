package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsFormatException
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV2
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
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
@Keep // Export activity reconstructs the selected class through its public no-argument constructor.
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
	private val v2MaximumBytes: Long = AmbientStepsPortableFormatV2.MAX_FILE_BYTES,
	private val v1MaximumBytes: Long = AmbientStepsPortableFormatV1.MAX_FILE_BYTES,
) : Exporter {
	init {
		require(v2MaximumBytes in 1..AmbientStepsPortableFormatV2.MAX_FILE_BYTES)
		require(v1MaximumBytes in 1..AmbientStepsPortableFormatV1.MAX_FILE_BYTES)
	}

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
		return exportSession(context, request, outputStream)
	}

	/** Exact imported day membership can narrow a bundle without splitting any real count window. */
	internal suspend fun exportSession(context: Context, request: ExportPortableAmbientStepsRequest,
		outputStream: OutputStream, selectedDayIdentities: Set<String>? = null): ExportResult {
		val backend = backendProvider(context)
		val selectedRequest = request.copy(selectedDayIdentities = selectedDayIdentities?.toSet() ?:
			request.selectedDayIdentities)
		val sourceResult = try {
			val v2Result = exportV2File(context, backend, selectedRequest, selectedDayIdentities, outputStream)
			if (v2Result == null || v2Result is ExportPortableAmbientStepsResult.Unverifiable &&
				v2Result.reason == PortableAmbientStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE) {
				exportV1File(context, backend, selectedRequest, selectedDayIdentities, outputStream)
					?: return ExportResult.Error(LocalizedString(R.string.export_error_portable_ambient_steps_write))
			} else {
				requireNotNull(v2Result)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableAmbientStepsFormatException) {
			return ExportResult.Error(LocalizedString(R.string.export_error_portable_ambient_steps_write))
		}
		return sourceResult.toExportResult()
	}

	private suspend fun exportV2File(
		context: Context, backend: AmbientStepsPortableSourceBackend, selectedRequest: ExportPortableAmbientStepsRequest,
		selectedDayIdentities: Set<String>?, output: OutputStream,
	): ExportPortableAmbientStepsResult? =
		FileBackedExportSpool(context, "portable-ambient-steps-v2-", v2MaximumBytes).use { spool ->
			spool.stageAndCopy(output, requireBytes = true) { destination ->
				codec.encode(destination) { sink ->
					exportSelectedV2(backend, selectedRequest, selectedDayIdentities, sink)
				}
			}
		}

	private suspend fun exportSelectedV2(
		backend: AmbientStepsPortableSourceBackend, selectedRequest: ExportPortableAmbientStepsRequest,
		selectedDayIdentities: Set<String>?,
		sink: com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveV2Sink,
	): ExportPortableAmbientStepsResult =
		if (selectedDayIdentities == null) {
			backend.exportV2(origin, selectedRequest, sink)
		} else {
			var selected: PortableAmbientStepsArchiveV2? = null
			val result = backend.exportV2(origin, selectedRequest) { archive ->
				val days = archive.days.filter { it.product.identity.value in selectedDayIdentities }
				if (days.map { it.product.identity.value }.toSet() != selectedDayIdentities) {
					throw PortableAmbientStepsFormatException("Selected Steps data is unavailable")
				}
				selected = PortableAmbientStepsArchiveV2.create(days)
				sink.emit(requireNotNull(selected))
			}
			if (result is ExportPortableAmbientStepsResult.Exported) {
				requireNotNull(selected).let { archive ->
				ExportPortableAmbientStepsResult.Exported(archive.days.size,
					archive.days.sumOf { it.product.facts.size },
                                        archive.days.sumOf { it.product.gaps.size })
			}
			} else {
				result
			}
		}

	private suspend fun exportV1File(
		context: Context, backend: AmbientStepsPortableSourceBackend, selectedRequest: ExportPortableAmbientStepsRequest,
		selectedDayIdentities: Set<String>?, output: OutputStream,
	): ExportPortableAmbientStepsResult? =
		FileBackedExportSpool(context, "portable-ambient-steps-v1-", v1MaximumBytes).use { spool ->
			spool.stageAndCopy(output, requireBytes = false) { destination ->
				legacyCodec.encode(destination) { sink ->
					exportSelectedV1(backend, selectedRequest, selectedDayIdentities, sink)
				}
			}
		}

	private suspend fun exportSelectedV1(
		backend: AmbientStepsPortableSourceBackend, selectedRequest: ExportPortableAmbientStepsRequest,
		selectedDayIdentities: Set<String>?,
		sink: com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink,
	): ExportPortableAmbientStepsResult =
		if (selectedDayIdentities == null) {
			backend.export(origin, selectedRequest, sink)
		} else {
			var selected: PortableAmbientStepsArchiveV1? = null
			val result = backend.export(origin, selectedRequest) { archive ->
				val days = archive.days.filter { it.identity.value in selectedDayIdentities }
				if (days.map { it.identity.value }.toSet() != selectedDayIdentities) {
					throw PortableAmbientStepsFormatException("Selected Steps data is unavailable")
				}
				selected = PortableAmbientStepsArchiveV1.create(days)
				sink.emit(requireNotNull(selected))
			}
			if (result is ExportPortableAmbientStepsResult.Exported) {
				requireNotNull(selected).let { archive ->
				ExportPortableAmbientStepsResult.Exported(archive.days.size,
					archive.days.sumOf { it.facts.size }, archive.days.sumOf { it.gaps.size })
			}
			} else {
				result
			}
		}

	private suspend fun FileBackedExportSpool.stageAndCopy(
		output: OutputStream, requireBytes: Boolean,
		encode: suspend (OutputStream) -> ExportPortableAmbientStepsResult,
	): ExportPortableAmbientStepsResult? = when (val staged = stage { encode(it) }) {
		is FileBackedExportStage.Complete -> {
			if (staged.value is ExportPortableAmbientStepsResult.Exported) {
				if (requireBytes && byteCount == 0L) {
					throw PortableAmbientStepsFormatException("Successful Ambient Steps v2 export wrote no bytes")
				}
				copyTo(output)
			}
			staged.value
		}
		FileBackedExportStage.EncodedSizeExceeded -> null
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
	val exclusiveEnd = if (last == Long.MAX_VALUE) {
		Long.MAX_VALUE
	} else {
		last + 1L
	}
	return AmbientStepsPortableFileScope.Range(first, exclusiveEnd)
}
