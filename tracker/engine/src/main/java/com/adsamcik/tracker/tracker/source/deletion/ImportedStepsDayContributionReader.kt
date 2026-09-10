package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
import com.adsamcik.tracker.tracker.source.summary.StepsNumericCoveredFact
import com.adsamcik.tracker.tracker.source.summary.StepsNumericDayWindowAccumulator
import com.adsamcik.tracker.tracker.source.summary.StepsNumericRunContribution
import java.time.ZoneId

/** Exact retained origin partition, authenticated in the caller's existing Room snapshot. */
internal class ImportedStepsDayContributionReader(private val database: AppDatabase) {
	/** Envelope discovery is supplemented by fact identities after wall-clock jumps. */
	suspend fun read(
		fromMs: Long,
		toMs: Long,
		presentationSegments: List<SessionSegment>,
		factRunIds: List<String>,
		accumulator: StepsNumericDayWindowAccumulator,
		excludedSegmentId: Long?,
	): ImportedStepsDayContributions? {
		val dao = database.importedStepsDao()
		val entries = dao.entriesOverlapping(fromMs, toMs, StepsPortableFormatV1.MAX_ENTRIES + 1)
		if (entries.size > StepsPortableFormatV1.MAX_ENTRIES) { return null }
		val importedSegments = presentationSegments.filter { it.source == SegmentSource.PORTABLE_STEPS_IMPORT }
		val candidateRuns = factRunIds.chunked(ID_BATCH).flatMap { ids ->
			dao.runsForIdentities(ids, ids.size + 1)
		} + importedSegments.map(SessionSegment::id).chunked(ID_BATCH).flatMap { ids ->
			dao.runsForSegmentIds(ids, ids.size + 1)
		}
		val entryIds = (entries.map { it.identity } + candidateRuns.map { it.entryIdentity }).distinct()
		val retained = authenticate(entryIds, accumulator, excludedSegmentId) ?: return null
		val segments = retained.segments.associateBy(SessionSegment::id)
		return if (importedSegments.any { segments[it.id] != it } || candidateRuns.any { it.identity !in retained.runIds }) {
			null
		} else {
			retained
		}
	}

	private suspend fun authenticate(
		entryIds: List<String>,
		accumulator: StepsNumericDayWindowAccumulator,
		excludedSegmentId: Long?,
	): ImportedStepsDayContributions? {
		if (entryIds.size > StepsPortableFormatV1.MAX_ENTRIES) { return null }
		val segments = mutableListOf<SessionSegment>()
		val runIds = hashSetOf<String>()
		for (ids in entryIds.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH)) {
			val result = ImportedStepsRetainedReader(database).readEntriesInTransaction(ids)
			if (result !is ImportedStepsRetainedRead.Ready || result.unverifiableEntries.isNotEmpty()) {
				return null
			}
			val batchRuns = result.entries.flatMap { it.runs }.map(ImportedStepsRunEntity::identity)
			if (runIds.size + batchRuns.size > MAX_RUN_METADATA || batchRuns.any { it in runIds } ||
				!ImportedStepsDayBatch(result.entries).addTo(accumulator, excludedSegmentId)
			) {
				return null
			}
			runIds += batchRuns
			segments += result.entries.flatMap { it.segmentsByRun.values }
		}
		return ImportedStepsDayContributions(segments, runIds, entryIds.toSet())
	}

	private companion object {
		const val ID_BATCH = 128
		const val MAX_RUN_METADATA = 16_384
	}
}

/** Only bounded ownership metadata survives batch consumption, never the complete fact hierarchies. */
internal data class ImportedStepsDayContributions(
	val segments: List<SessionSegment>,
	val runIds: Set<String>,
	val logicalTrackingIds: Set<String>,
)

/** Retained Steps are not local provider runs and never enter local manifest/lane qualification. */
private class ImportedStepsDayBatch(private val entries: List<RetainedImportedStepsEntry>) {
	fun addTo(accumulator: StepsNumericDayWindowAccumulator, excludedSegmentId: Long?): Boolean {
		for (entry in entries) {
			val runs = entry.portableRunsById.values.filterNot { run ->
				entry.segmentsByRun.getValue(run.identity.value).id == excludedSegmentId
			}
			if (runs.isEmpty()) { continue }
			val contributions = runs.map { run -> run.toContribution(entry) }
			if (!accumulator.addLogicalGroup(contributions)) { return false }
			for ((run, contribution) in runs.zip(contributions)) {
				if (!consumeRun(accumulator, run, contribution)) { return false }
			}
		}
		return true
	}

	/** Finish each exact physical run before another; no local elapsed clock is invented. */
	private fun consumeRun(
		accumulator: StepsNumericDayWindowAccumulator,
		run: PortableStepsRunV1,
		contribution: StepsNumericRunContribution,
	): Boolean {
		if (!accumulator.startRun(contribution)) { return false }
		val consumed = run.facts.all { fact ->
			if (fact.coverage == PortableStepsFactCoverage.COVERED) {
				accumulator.consumeCoveredFact(StepsNumericCoveredFact(
					serviceRunId = run.identity.value, manifestRevision = fact.manifestRevision,
					startMs = fact.intervalStartTimeMs, endMs = fact.intervalEndTimeMs,
					steps = requireNotNull(fact.stepCount), wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
				))
			} else {
				accumulator.addUncoveredStepsInterval(
					run.identity.value, fact.intervalStartTimeMs, fact.intervalEndTimeMs,
					isGap = fact.coverage != PortableStepsFactCoverage.BASELINE,
				)
			}
		}
		return consumed && accumulator.finishRun()
	}
}

private fun PortableStepsRunV1.toContribution(entry: RetainedImportedStepsEntry) = StepsNumericRunContribution(
	serviceRunId = identity.value,
	logicalTrackingId = entry.metadata.identity,
	logicalStartedAtMs = entry.metadata.startTimeMs,
	capturedZoneId = ZoneId.of(storedZoneId),
	segmentStartMs = startTimeMs,
	segmentEndMs = endTimeMs,
	distanceM = 0f,
	contributesTrackedDuration = false,
	stepsManifestRevisions = manifests.mapTo(linkedSetOf()) { it.revision },
	// The original full capture set is unknown; partial retained evidence is a separate flag.
	hasManifestWithoutStepsCapture = false,
	hasPartialStepsEvidence = identity.value in entry.retentionTruncatedRunIds ||
		completeness.captureCoverage != PortableStepsCaptureCoverage.WHOLE_RUN ||
		completeness.providerCoverage != PortableStepsProviderCoverage.COMPLETE ||
		!completeness.appDrainComplete || !completeness.stopComplete || completeness.hasUnresolvedProviderRange ||
		facts.any { it.coverage == PortableStepsFactCoverage.RESET_GAP || it.coverage == PortableStepsFactCoverage.PARTIAL } ||
		hasMissingInternalBoundary(),
)

/**
 * An ordinary internal covered subset cannot certify the unobserved remainder. Wall-regressed
 * facts outside the presentation envelope remain discoverable under their stored civil authority;
 * portable v1 does not carry the original elapsed clock and we do not reconstruct one from walls.
 */
private fun PortableStepsRunV1.hasMissingInternalBoundary(): Boolean {
	val covered = facts.filter { it.coverage == PortableStepsFactCoverage.COVERED }
	val first = covered.firstOrNull() ?: return false
	val last = covered.last()
	val isInternal = first.intervalStartTimeMs >= startTimeMs && last.intervalEndTimeMs <= endTimeMs
	return isInternal && (first.intervalStartTimeMs != startTimeMs || last.intervalEndTimeMs != endTimeMs)
}
