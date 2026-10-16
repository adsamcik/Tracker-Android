package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import kotlinx.coroutines.CancellationException

internal fun snapshotAmbientStepsImport(
	request: AmbientImportEnvelope,
): AmbientImportEnvelope = ambientStepsIncomingValue {
	val rawDays = request.archive.days
	authenticateAmbientStepsRawSnapshot(request, rawDays)
	val days = rawDays.map { day ->
		day.copy(
			partialCauses = day.partialCauses.toList(),
			facts = day.facts.map { it.copy() },
			gaps = day.gaps.map { it.copy() },
		)
	}
	val archive = request.archive.copy(days = days)
	val factCount = days.sumOf { it.facts.size }
	val gapCount = days.sumOf { it.gaps.size }
	if (request.dayCount != days.size ||
		request.factCount != factCount ||
		request.gapCount != gapCount
	) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.METADATA_MISMATCH)
	}
	if (days.map { StructuralAmbientStepsDayKey(it) }.distinct().size != days.size) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
	}
	IncomingAmbientStepsOwnership(
		archive,
		request.sourceArchiveIdentity.value,
		ImportedAmbientStepsIdentity.receipt(
			request.receipt.jobId,
			request.receipt.archiveKey,
		),
	)
	val copiedGraphs = request.graphsByDayIdentity.mapValues { (_, graph) ->
		graph.copy(
			receipts = graph.receipts.map { it.copy() },
			ownerRevisions = graph.ownerRevisions.map { it.copy() },
			completenessMarkers = graph.completenessMarkers.map { it.copy() },
			roots = graph.roots.map { it.copy() },
		)
	}
	days.forEach { day ->
		copiedGraphs[day.identity.value]?.let { graph ->
			PortableAmbientStepsDayV2(day, graph)
		}
	}
	request.copy(
		archive = archive,
		graphsByDayIdentity = copiedGraphs,
	)
}

private fun authenticateAmbientStepsRawSnapshot(
	request: AmbientImportEnvelope,
	rawDays: List<PortableAmbientStepsDayV1>,
) {
	if (rawDays.size !in 1..AmbientStepsPortableFormatV1.MAX_DAYS ||
		rawDays.any {
			it.facts.size !in 1..AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY ||
				it.gaps.size > AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY ||
				it.partialCauses.size > PortableAmbientStepsPartialCause.entries.size
		}
	) {
		ambientStepsImportOverflow()
	}
	val (rawFactCount, rawGapCount) = countAmbientStepsRawMeasurements(rawDays)
	authenticateAmbientStepsRawMetadata(request, rawDays.size, rawFactCount, rawGapCount)
}

private fun countAmbientStepsRawMeasurements(rawDays: List<PortableAmbientStepsDayV1>): Pair<Long, Long> {
	var rawFactCount = 0L
	var rawGapCount = 0L
	rawDays.forEach { day ->
		rawFactCount = Math.addExact(rawFactCount, day.facts.size.toLong())
		rawGapCount = Math.addExact(rawGapCount, day.gaps.size.toLong())
	}
	return rawFactCount to rawGapCount
}

private fun authenticateAmbientStepsRawMetadata(
	request: AmbientImportEnvelope,
	dayCount: Int,
	rawFactCount: Long,
	rawGapCount: Long,
) {
	if (rawFactCount > AmbientStepsPortableFormatV1.MAX_FACTS ||
		rawGapCount > AmbientStepsPortableFormatV1.MAX_GAPS
	) {
		ambientStepsImportOverflow()
	}
	if (request.dayCount != dayCount || request.factCount.toLong() != rawFactCount ||
		request.gapCount.toLong() != rawGapCount
	) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.METADATA_MISMATCH)
	}
}

internal suspend inline fun <T> ambientStepsStoredValue(crossinline block: suspend () -> T): T = try {
	block()
} catch (abort: PortableAmbientStepsImportAbort) {
	throw abort
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: ImportedAmbientStepsLineageFailure) {
	mapAmbientStepsStoredLineageFailure(failure.reason)
} catch (_: IllegalArgumentException) {
	corruptAmbientStepsImport()
} catch (_: IllegalStateException) {
	corruptAmbientStepsImport()
} catch (_: ArithmeticException) {
	corruptAmbientStepsImport()
} catch (_: java.time.DateTimeException) {
	corruptAmbientStepsImport()
}

private fun mapAmbientStepsStoredLineageFailure(reason: ImportedAmbientStepsLineageFailureReason): Nothing =
	when (reason) {
		ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW -> ambientStepsImportOverflow()
		ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW ->
			unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
		ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE -> corruptAmbientStepsImport()
	}

internal inline fun <T> ambientStepsIncomingValue(block: () -> T): T = try {
	block()
} catch (abort: PortableAmbientStepsImportAbort) {
	throw abort
} catch (_: IllegalArgumentException) {
	unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
} catch (_: IllegalStateException) {
	unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
} catch (_: ArithmeticException) {
	unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
} catch (_: java.time.DateTimeException) {
	unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
}

internal fun ambientStepsImportOverflow(): Nothing =
	unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW)

internal fun corruptAmbientStepsImport(): Nothing =
	unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

internal fun blockAmbientStepsImport(reason: PortableAmbientStepsImportBlockedReason): Nothing =
	throw PortableAmbientStepsImportAbort(ImportPortableAmbientStepsResult.Blocked(reason))

internal fun unverifiableAmbientStepsImport(reason: PortableAmbientStepsImportUnverifiableReason): Nothing =
	throw PortableAmbientStepsImportAbort(ImportPortableAmbientStepsResult.Unverifiable(reason))

internal class PortableAmbientStepsImportAbort(
	val result: ImportPortableAmbientStepsResult,
) : RuntimeException(null, null, false, false)
