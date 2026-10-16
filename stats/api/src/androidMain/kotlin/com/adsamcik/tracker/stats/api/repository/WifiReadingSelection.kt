package com.adsamcik.tracker.stats.api.repository

/** A selected file adds readings. Absence from that file is never a deletion. */
fun PortableCapturedWifiEntryV1.mergeReadingSelection(
    incoming: PortableCapturedWifiEntryV1
): PortableCapturedWifiEntryV1 {
	require(identity == incoming.identity && sessionMode == incoming.sessionMode)
	val byRun = runs.associateBy { it.identity }.toMutableMap()
	incoming.runs.forEach { next ->
		val previous = byRun[next.identity]
		if (previous == null) {
			byRun[next.identity] = next
		} else {
			byRun[next.identity] = mergeWifiRunReadings(previous, next)
		}
	}
	val merged = byRun.values.sortedWith(PORTABLE_WIFI_RUN_ORDER)
	return PortableWifiIntegrity.createEntry(identity, sessionMode, merged.minOf { it.startTimeMs },
		merged.maxOf { it.endTimeMs }, merged)
}

private fun mergeWifiRunReadings(
	previous: PortableCapturedWifiRunV1,
	next: PortableCapturedWifiRunV1,
): PortableCapturedWifiRunV1 {
	require(previous.deletionScopeDigest == next.deletionScopeDigest &&
		previous.storedZoneIds == next.storedZoneIds)
	val bounds = wifiMergedRunBounds(previous, next)
	val children = mergeWifiObservations(previous.observations, next.observations)
	return PortableWifiIntegrity.createRun(previous.identity, previous.deletionScopeDigest,
		bounds.first, bounds.second, previous.storedZoneIds,
		if (previous.captureCoverage == PortableWifiCaptureCoverage.WHOLE_RUN ||
			next.captureCoverage == PortableWifiCaptureCoverage.WHOLE_RUN) {
			PortableWifiCaptureCoverage.WHOLE_RUN
		} else {
			previous.captureCoverage
		},
		if (children.isNotEmpty()) {
			PortableWifiRunAvailability.RETAINED
		} else {
			previous.availability
		},
		if (previous.acquisitionCompleteness == PortableWifiAcquisitionCompleteness.COMPLETE ||
			next.acquisitionCompleteness == PortableWifiAcquisitionCompleteness.COMPLETE) {
			PortableWifiAcquisitionCompleteness.COMPLETE
		} else {
			PortableWifiAcquisitionCompleteness.PARTIAL
		},
		previous.hasUnresolvedProviderRange || next.hasUnresolvedProviderRange,
		previous.retentionLoss || next.retentionLoss, children)
}


private fun mergeWifiObservations(
	previous: List<PortableCapturedWifiObservationV1>,
	next: List<PortableCapturedWifiObservationV1>,
): List<PortableCapturedWifiObservationV1> {
	val observations = previous.associateBy { it.identity }.toMutableMap()
	next.forEach { observation ->
		val saved = observations[observation.identity]
		require(saved == null || saved == observation) { "Conflicting Wi-Fi reading" }
		observations[observation.identity] = observation
	}
	return observations.values.sortedWith(PORTABLE_WIFI_OBSERVATION_ORDER)
}

private fun wifiMergedRunBounds(previous: PortableCapturedWifiRunV1, next: PortableCapturedWifiRunV1): Pair<Long,
    Long> {
	if (previous.captureCoverage != PortableWifiCaptureCoverage.PARTIAL_RUN &&
		next.captureCoverage != PortableWifiCaptureCoverage.PARTIAL_RUN) {
		require(previous.startTimeMs == next.startTimeMs && previous.endTimeMs == next.endTimeMs &&
			previous.captureCoverage == next.captureCoverage)
		return previous.startTimeMs to previous.endTimeMs
	}
	require(previous.captureCoverage != PortableWifiCaptureCoverage.NOT_CAPTURED &&
		next.captureCoverage != PortableWifiCaptureCoverage.NOT_CAPTURED)
	val whole = listOf(previous, next).firstOrNull { it.captureCoverage == PortableWifiCaptureCoverage.WHOLE_RUN }
	// Observation uncertainty can extend beyond a known stop; the complete envelope is still preserved.
	if (whole != null) {
		return whole.startTimeMs to whole.endTimeMs
	}
	return minOf(previous.startTimeMs, next.startTimeMs) to maxOf(previous.endTimeMs, next.endTimeMs)
}

/**
 * Returns whether merging this file into the previous entry adds readings without changing any
 * existing reading or ownership.
 */
fun PortableCapturedWifiEntryV1.isAdditiveReadingSelectionOf(previous: PortableCapturedWifiEntryV1): Boolean =
	runCatching { previous.mergeReadingSelection(this) == this && this != previous }.getOrDefault(false)

/**
 * Keeps readings accepted by the predicate plus their original aggregate owners and chosen
 * gap-only runs; returns null if nothing remains.
 */
fun PortableCapturedWifiEntryV1.selectReadings(
	includeObservation: (PortableCapturedWifiObservationV1) -> Boolean,
	includeGap: (PortableCapturedWifiRunV1) -> Boolean,
): PortableCapturedWifiEntryV1? {
	val selected = runs.mapNotNull { run ->
		val ids = run.observations.filter(includeObservation).mapTo(mutableSetOf()) { it.identity }
		// Coverage-only revisions still need their original aggregate; this keeps the real measurement window intact.
		var changed: Boolean
		do {
			changed = false
			run.observations.filter { it.identity in ids }.forEach { observation ->
				observation.aggregateOwnerIdentity?.let { if (ids.add(it)) {
					changed = true
				} }
			}
		} while (changed)
		val observations = run.observations.filter { it.identity in ids }
		if (observations.isEmpty() && !includeGap(run)) {
			null
		} else {
			PortableWifiIntegrity.createRun(run.identity,
			run.deletionScopeDigest, run.startTimeMs, run.endTimeMs, run.storedZoneIds, run.captureCoverage,
			if (observations.isEmpty()) {
				PortableWifiRunAvailability.NO_RETAINED_OBSERVATION
			} else {
				PortableWifiRunAvailability.RETAINED
			},
			if (observations.size == run.observations.size) {
				run.acquisitionCompleteness
			} else {
				PortableWifiAcquisitionCompleteness.PARTIAL
			},
			run.hasUnresolvedProviderRange, run.retentionLoss, observations)
		}
	}.sortedWith(PORTABLE_WIFI_RUN_ORDER)
	return selected.takeIf { it.isNotEmpty() }?.let { PortableWifiIntegrity.createEntry(identity, sessionMode,
		it.minOf { run -> run.startTimeMs }, it.maxOf { run -> run.endTimeMs }, it) }
}
