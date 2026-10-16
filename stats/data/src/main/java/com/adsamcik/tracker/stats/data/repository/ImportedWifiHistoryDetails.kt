package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Adds details only to an already-authenticated imported product in the caller's Room snapshot. */
internal suspend fun AppDatabase.withImportedWifiDetails(
	evaluation: ImportedWifiProductEvaluation,
	publicEntry: WifiHistoryEntry,
): WifiHistoryEntry {
	val readable = evaluation as? ImportedWifiProductEvaluation.Readable ?: return publicEntry
	if (publicEntry.observations.isEmpty()) {
		return publicEntry
	}
	val retained = readable.retainedObservationIdentities
	val visible = readable.entry.runs.filterNot { it.identity in readable.deletedRunIdentities }
		.flatMap { run -> run.observations.filter { it.identity in retained }.map { run to it } }
	check(visible.size == publicEntry.observations.size)
	val owners = visible.associate { (run, observation) ->
		observation.identity.value to (run.identity.value to observation)
	}
	check(owners.size == visible.size)
	val rows = mutableListOf<ImportedWifiChildEntity>()
	for ((runIdentity, observations) in owners.entries.groupBy { it.value.first }) {
		for (ids in observations.map { it.key }.chunked(IMPORTED_WIFI_OWNER_BATCH)) {
			currentCoroutineContext().ensureActive()
			rows += importedRadioChildDao().wifiSessionChildrenForObservations(readable.entry.identity.value,
				readable.candidate.importRevision, runIdentity, ids, MAX_IMPORTED_WIFI_DETAILS - rows.size + 1)
			if (rows.size > MAX_IMPORTED_WIFI_DETAILS) {
				return publicEntry.copy(
				state = WifiHistoryProductState.FAILED, coverage = WifiHistoryCoverage.NONE,
				observations = emptyList(), causes = setOf(WifiHistoryCause.READ_BUDGET_EXCEEDED))
			}
		}
	}
	// An imported correction can retain children for a run or observation that was later erased.
	// Its parent remains for provenance, but it must not make the surviving history unreadable.
	val byObservation = rows.groupBy { row ->
		val owner = requireNotNull(owners[row.observationIdentity])
		validateImportedWifiChild(row, readable.entry.identity.value, readable.candidate.importRevision,
			owner.first, owner.second)
		row.observationIdentity
	}
	check(visible.all { (_, observation) ->
		// The current format permits an absent detail group; a present group must be complete.
		byObservation[observation.identity.value]?.size?.let { it == observation.observationCount } != false
	})
	return publicEntry.copy(observations = visible.mapIndexed { index, (_, observation) ->
		publicEntry.observations[index].copy(networks = byObservation[observation.identity.value]
			.orEmpty().map { row -> WifiObservedNetwork(row.observedWallTimeMs,
				row.wallTimeUncertaintyMs, row.bssid?.takeIf(String::isNotBlank),
				row.ssid?.takeIf(String::isNotEmpty), row.frequencyMhz, row.signalDbm) })
	})
}

private const val MAX_IMPORTED_WIFI_DETAILS = 4_096
private const val IMPORTED_WIFI_OWNER_BATCH = 64

/** Exact selected imported parent details; legacy absent groups remain typed summaries. */
internal suspend fun AppDatabase.measuredImportedWifiChildren(
	evaluation: ImportedWifiProductEvaluation.Readable,
	runIdentity: String,
	parent: com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1,
): List<ImportedWifiChildEntity> {
	val owner = evaluation.entry.runs.singleOrNull { it.identity.value == runIdentity }
	check(owner != null && owner.observations.singleOrNull { it.identity == parent.identity } == parent)
	val rows = importedRadioChildDao().wifiSessionChildrenForObservations(evaluation.entry.identity.value,
		evaluation.candidate.importRevision, runIdentity, listOf(parent.identity.value),
            Math.addExact(parent.observationCount, 1))
	check(rows.size <= parent.observationCount && (rows.isEmpty() || rows.size == parent.observationCount))
	check(rows.map { it.itemIndex }.distinct().size == rows.size)
	rows.forEach { row -> validateImportedWifiChild(row, evaluation.entry.identity.value,
		evaluation.candidate.importRevision, runIdentity, parent) }
	return rows
}

private fun validateImportedWifiChild(row: ImportedWifiChildEntity, entryIdentity: String,
	importRevision: Long, runIdentity: String,
	parent: com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1) {
	check(row.entryIdentity == entryIdentity && row.entryImportRevision == importRevision &&
		row.runIdentity == runIdentity && row.observationIdentity == parent.identity.value)
	check(row.itemIndex >= 0 && row.providerTimestampNanos > 0L && row.wallTimeUncertaintyMs >= 0L &&
		row.observedWallTimeMs >= row.wallTimeUncertaintyMs &&
            row.observedWallTimeMs - row.wallTimeUncertaintyMs >= parent.coverageStartTimeMs &&
		row.observedWallTimeMs <= Long.MAX_VALUE - row.wallTimeUncertaintyMs &&
		row.observedWallTimeMs + row.wallTimeUncertaintyMs <= parent.latestPossibleTimeMs)
	check(row.contentChecksum == PortableRadioChildIntegrity.digest("wifi",
		listOf(row.entryIdentity, row.runIdentity, row.observationIdentity),
		listOf(parent.semanticRevision, row.itemIndex, row.providerTimestampNanos, row.observedWallTimeMs,
			row.wallTimeUncertaintyMs, row.bssid, row.ssid, row.frequencyMhz, row.signalDbm)))
}
