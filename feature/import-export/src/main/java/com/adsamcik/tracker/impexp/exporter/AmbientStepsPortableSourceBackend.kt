package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveV2Sink
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

internal enum class AmbientStepsPortableOrigin {
	NATIVE,
	IMPORTED,
}

internal sealed interface AmbientStepsPortableFileScope {
	data object AllAvailableSnapshot : AmbientStepsPortableFileScope

	data class Range(
		val fromInclusiveMs: Long,
		val toExclusiveMs: Long,
	) : AmbientStepsPortableFileScope

	data class StructuralDay(
		val epochDay: Long,
		val zoneId: String,
	) : AmbientStepsPortableFileScope

	data class Trip(val selectionKey: String) : AmbientStepsPortableFileScope
}

internal enum class AmbientStepsPortableUnsupportedScope {
	TRIP_SCOPE_NOT_REPRESENTABLE,
	DAY_SCOPE_NOT_REPRESENTABLE,
	RANGE_SCOPE_NOT_REPRESENTABLE,
}

internal sealed interface AmbientStepsPortableScopeResolution {
	data class Ready(
		val request: ExportPortableAmbientStepsRequest,
	) : AmbientStepsPortableScopeResolution

	data class Unsupported(
		val reason: AmbientStepsPortableUnsupportedScope,
	) : AmbientStepsPortableScopeResolution
}

/**
 * Source-specific action backend for native export and imported-origin re-export.
 *
 * A single snapshot is bounded by the portable format's `MAX_DAYS`. Producers return dependency
 * overflow rather than silently truncating an all-history or range request. No continuation
 * contract exists yet.
 */
internal class AmbientStepsPortableSourceBackend(
	private val nativeExporter: ExportPortableAmbientSteps,
	private val importedReexporter: ReexportImportedAmbientSteps,
	private val nativeExporterV2: ExportPortableAmbientStepsV2,
	private val importedReexporterV2: ReexportImportedAmbientStepsV2,
) {
	fun resolve(scope: AmbientStepsPortableFileScope): AmbientStepsPortableScopeResolution =
		when (scope) {
			AmbientStepsPortableFileScope.AllAvailableSnapshot ->
				AmbientStepsPortableScopeResolution.Ready(FULL_HISTORY)
			is AmbientStepsPortableFileScope.Range -> if (
				scope.fromInclusiveMs >= 0L &&
				scope.toExclusiveMs > scope.fromInclusiveMs
			) {
				AmbientStepsPortableScopeResolution.Ready(
					ExportPortableAmbientStepsRequest(
						scope.fromInclusiveMs,
						scope.toExclusiveMs,
					),
				)
			} else {
				AmbientStepsPortableScopeResolution.Unsupported(
					AmbientStepsPortableUnsupportedScope.RANGE_SCOPE_NOT_REPRESENTABLE,
				)
			}

			suspend fun exportV2(
				origin: AmbientStepsPortableOrigin,
				request: ExportPortableAmbientStepsRequest,
				sink: PortableAmbientStepsArchiveV2Sink,
			): ExportPortableAmbientStepsResult = when (origin) {
				AmbientStepsPortableOrigin.NATIVE -> nativeExporterV2.export(request, sink)
				AmbientStepsPortableOrigin.IMPORTED ->
					importedReexporterV2.export(request, sink)
			}
			is AmbientStepsPortableFileScope.StructuralDay -> resolveDay(scope)
			is AmbientStepsPortableFileScope.Trip ->
				AmbientStepsPortableScopeResolution.Unsupported(
					AmbientStepsPortableUnsupportedScope.TRIP_SCOPE_NOT_REPRESENTABLE,
				)
		}

	suspend fun export(
		origin: AmbientStepsPortableOrigin,
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveSink,
	): ExportPortableAmbientStepsResult = when (origin) {
		AmbientStepsPortableOrigin.NATIVE -> nativeExporter.export(request, sink)
		AmbientStepsPortableOrigin.IMPORTED -> importedReexporter.export(request, sink)
	}

	private fun resolveDay(
		scope: AmbientStepsPortableFileScope.StructuralDay,
	): AmbientStepsPortableScopeResolution = if (
		scope.zoneId.isBlank() ||
		scope.zoneId.length > AmbientStepsPortableFormatV1.MAX_ZONE_ID_LENGTH
	) {
		AmbientStepsPortableScopeResolution.Unsupported(
			AmbientStepsPortableUnsupportedScope.DAY_SCOPE_NOT_REPRESENTABLE,
		)
	} else try {
		val zone = ZoneId.of(scope.zoneId)
		val date = LocalDate.ofEpochDay(scope.epochDay)
		val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
		val end = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		if (start < 0L || end <= start) {
			AmbientStepsPortableScopeResolution.Unsupported(
				AmbientStepsPortableUnsupportedScope.DAY_SCOPE_NOT_REPRESENTABLE,
			)
		} else {
			AmbientStepsPortableScopeResolution.Ready(
				ExportPortableAmbientStepsRequest(start, end),
			)
		}
	} catch (_: DateTimeException) {
		AmbientStepsPortableScopeResolution.Unsupported(
			AmbientStepsPortableUnsupportedScope.DAY_SCOPE_NOT_REPRESENTABLE,
		)
	} catch (_: ArithmeticException) {
		AmbientStepsPortableScopeResolution.Unsupported(
			AmbientStepsPortableUnsupportedScope.DAY_SCOPE_NOT_REPRESENTABLE,
		)
	}

	internal companion object {
		val FULL_HISTORY = ExportPortableAmbientStepsRequest(0L, Long.MAX_VALUE)
	}
}
