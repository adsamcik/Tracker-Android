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
	if (publicEntry.observations.isEmpty()) return publicEntry
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
			if (rows.size > MAX_IMPORTED_WIFI_DETAILS) return publicEntry.copy(
				state = WifiHistoryProductState.FAILED, coverage = WifiHistoryCoverage.NONE,
				observations = emptyList(), causes = setOf(WifiHistoryCause.READ_BUDGET_EXCEEDED))
		}
	}
	// An imported correction can retain children for a run or observation that was later erased.
	// Its parent remains for provenance, but it must not make the surviving history unreadable.
	val byObservation = rows.groupBy { row ->
		check(row.entryIdentity == readable.entry.identity.value &&
			row.entryImportRevision == readable.candidate.importRevision)
		val owner = requireNotNull(owners[row.observationIdentity])
		check(owner.first == row.runIdentity)
		val parent = owner.second
		check(row.itemIndex >= 0 && row.providerTimestampNanos > 0L &&
			row.wallTimeUncertaintyMs >= 0L && row.observedWallTimeMs >= row.wallTimeUncertaintyMs &&
			row.observedWallTimeMs - row.wallTimeUncertaintyMs >= parent.coverageStartTimeMs &&
			row.observedWallTimeMs <= Long.MAX_VALUE - row.wallTimeUncertaintyMs &&
			row.observedWallTimeMs + row.wallTimeUncertaintyMs <= parent.latestPossibleTimeMs)
		val identities = listOf(row.entryIdentity, row.runIdentity, row.observationIdentity)
		val revision = parent.semanticRevision
		check(row.contentChecksum == PortableRadioChildIntegrity.digest("wifi", identities,
			listOf(revision, row.itemIndex, row.providerTimestampNanos,
				row.observedWallTimeMs, row.wallTimeUncertaintyMs, row.bssid, row.ssid,
				row.frequencyMhz, row.signalDbm)))
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
