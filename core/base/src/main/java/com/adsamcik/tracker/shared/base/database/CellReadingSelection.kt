package com.adsamcik.tracker.shared.base.database

/** Immutable selected snapshots share the source's existing imported revision store. */
fun PortableCapturedCellEntryV1.mergeReadingSelection(incoming: PortableCapturedCellEntryV1): PortableCapturedCellEntryV1 {
	require(identity == incoming.identity && sessionMode == incoming.sessionMode && subscriptionGrouping == incoming.subscriptionGrouping)
	val byRun = runs.associateBy { it.identity }.toMutableMap()
	incoming.runs.forEach { next ->
		val previous = byRun[next.identity]
		if (previous == null) byRun[next.identity] = next else {
			require(previous.deletionScopeDigest == next.deletionScopeDigest &&
				previous.subscriptionGrouping == next.subscriptionGrouping)
			val bounds = cellMergedRunBounds(previous, next)
			val observations = previous.observations.associateBy { it.identity }.toMutableMap()
			next.observations.forEach { observation ->
				val saved = observations[observation.identity]
				require(saved == null || saved == observation) { "Conflicting Cell reading" }
				observations[observation.identity] = observation
			}
			val children = observations.values.sortedWith(CELL_SELECTED_OBSERVATION_ORDER)
			byRun[next.identity] = cellSelectedRun(previous, children,
				if (previous.acquisitionCompleteness == PortableCellAcquisitionCompleteness.COMPLETE ||
					next.acquisitionCompleteness == PortableCellAcquisitionCompleteness.COMPLETE)
					PortableCellAcquisitionCompleteness.COMPLETE else PortableCellAcquisitionCompleteness.PARTIAL,
				previous.retentionLoss || next.retentionLoss, bounds.first, bounds.second,
				if (previous.captureCoverage == PortableCellCaptureCoverage.WHOLE_RUN ||
					next.captureCoverage == PortableCellCaptureCoverage.WHOLE_RUN) PortableCellCaptureCoverage.WHOLE_RUN
				else previous.captureCoverage)
		}
	}
	return cellSelectedEntry(this, byRun.values.sortedWith(CELL_SELECTED_RUN_ORDER))
}

private fun cellMergedRunBounds(previous: PortableCapturedCellRunV1, next: PortableCapturedCellRunV1): Pair<Long, Long> {
	if (previous.captureCoverage != PortableCellCaptureCoverage.PARTIAL_RUN &&
		next.captureCoverage != PortableCellCaptureCoverage.PARTIAL_RUN) {
		require(previous.startTimeMs == next.startTimeMs && previous.endTimeMs == next.endTimeMs &&
			previous.captureCoverage == next.captureCoverage)
		return previous.startTimeMs to previous.endTimeMs
	}
	require(previous.captureCoverage != PortableCellCaptureCoverage.NOT_CAPTURED &&
		next.captureCoverage != PortableCellCaptureCoverage.NOT_CAPTURED)
	val whole = listOf(previous, next).firstOrNull { it.captureCoverage == PortableCellCaptureCoverage.WHOLE_RUN }
	// Observation uncertainty can extend beyond a known stop; the complete envelope is still preserved.
	if (whole != null) return whole.startTimeMs to whole.endTimeMs
	return minOf(previous.startTimeMs, next.startTimeMs) to maxOf(previous.endTimeMs, next.endTimeMs)
}

internal fun PortableCapturedCellEntryV1.isAdditiveReadingSelectionOf(previous: PortableCapturedCellEntryV1): Boolean =
	runCatching { previous.mergeReadingSelection(this) == this && this != previous }.getOrDefault(false)

fun PortableCapturedCellEntryV1.selectReadings(
	includeObservation: (PortableCapturedCellObservationV1) -> Boolean,
	includeGap: (PortableCapturedCellRunV1) -> Boolean,
): PortableCapturedCellEntryV1? {
	val selected = runs.mapNotNull { run ->
		val ids = run.observations.filter(includeObservation).mapTo(mutableSetOf()) { it.identity }
		var changed: Boolean
		do {
			changed = false
			run.observations.filter { it.identity in ids }.forEach { observation ->
				observation.aggregateOwnerIdentity?.let { if (ids.add(it)) changed = true }
			}
		} while (changed)
		val observations = run.observations.filter { it.identity in ids }
		if (observations.isEmpty() && !includeGap(run)) null else cellSelectedRun(run, observations,
			if (observations.size == run.observations.size) run.acquisitionCompleteness else PortableCellAcquisitionCompleteness.PARTIAL,
			run.retentionLoss)
	}.sortedWith(CELL_SELECTED_RUN_ORDER)
	return selected.takeIf { it.isNotEmpty() }?.let { cellSelectedEntry(this, it) }
}

private fun cellSelectedRun(original: PortableCapturedCellRunV1, observations: List<PortableCapturedCellObservationV1>,
	completeness: PortableCellAcquisitionCompleteness, retentionLoss: Boolean,
	startTimeMs: Long = original.startTimeMs, endTimeMs: Long = original.endTimeMs,
	captureCoverage: PortableCellCaptureCoverage = original.captureCoverage): PortableCapturedCellRunV1 {
	val availability = if (observations.isNotEmpty()) PortableCellRunAvailability.RETAINED else PortableCellRunAvailability.NO_RETAINED_OBSERVATION
	val payload = PortableCellRunPayload(original.identity, original.deletionScopeDigest, startTimeMs,
		endTimeMs, captureCoverage, availability, completeness, retentionLoss, original.subscriptionGrouping, observations)
	return PortableCapturedCellRunV1(original.identity, original.deletionScopeDigest, CellCapturedPortableIntegrity.runChecksum(payload),
		startTimeMs, endTimeMs, captureCoverage, availability, completeness, retentionLoss,
		original.subscriptionGrouping, observations)
}

private fun cellSelectedEntry(original: PortableCapturedCellEntryV1, runs: List<PortableCapturedCellRunV1>): PortableCapturedCellEntryV1 {
	val start = runs.minOf { it.startTimeMs }; val end = runs.maxOf { it.endTimeMs }
	val payload = PortableCellEntryPayload(original.identity, original.sessionMode, start, end, original.subscriptionGrouping, runs)
	return PortableCapturedCellEntryV1(identity = original.identity, contentChecksum = CellCapturedPortableIntegrity.entryChecksum(payload),
		sessionMode = original.sessionMode, startTimeMs = start, endTimeMs = end, subscriptionGrouping = original.subscriptionGrouping, runs = runs)
}

private val CELL_SELECTED_OBSERVATION_ORDER = compareBy<PortableCapturedCellObservationV1>(
	PortableCapturedCellObservationV1::coverageStartTimeMs, PortableCapturedCellObservationV1::observedTimeMs, { it.identity.value })
private val CELL_SELECTED_RUN_ORDER = compareBy<PortableCapturedCellRunV1>(PortableCapturedCellRunV1::startTimeMs, { it.identity.value })
