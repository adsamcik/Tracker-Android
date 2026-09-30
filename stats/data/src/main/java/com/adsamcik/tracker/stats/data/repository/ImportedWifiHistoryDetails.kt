package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork

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
	val rows = importedRadioChildDao().wifiSessionChildren(readable.entry.identity.value,
		readable.candidate.importRevision, MAX_IMPORTED_WIFI_DETAILS + 1)
	check(rows.size <= MAX_IMPORTED_WIFI_DETAILS)
	val owners = visible.associate { (run, observation) ->
		observation.identity.value to (run.identity.value to observation.semanticRevision)
	}
	check(owners.size == visible.size)
	// An imported correction can retain children for a run or observation that was later erased.
	// Its parent remains for provenance, but it must not make the surviving history unreadable.
	val byObservation = rows.filter { it.observationIdentity in owners }.groupBy { row ->
		check(row.entryIdentity == readable.entry.identity.value &&
			row.entryImportRevision == readable.candidate.importRevision)
		val owner = requireNotNull(owners[row.observationIdentity])
		check(owner.first == row.runIdentity)
		val identities = listOf(row.entryIdentity, row.runIdentity, row.observationIdentity)
		val revision = owner.second
		check(row.contentChecksum == PortableRadioChildIntegrity.digest("wifi", identities,
			listOf(revision, row.itemIndex, row.providerTimestampNanos,
				row.observedWallTimeMs, row.wallTimeUncertaintyMs, row.bssid, row.ssid,
				row.frequencyMhz, row.signalDbm)))
		row.observationIdentity
	}
	check(visible.all { (_, observation) ->
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
