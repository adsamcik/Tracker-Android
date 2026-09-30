package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.CellSessionChildV2
import com.adsamcik.tracker.impexp.portable.WifiSessionChildV2
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Converts the selected imported owner in DAO order without repeated scans of its runs. */
internal suspend fun mapImportedWifiSessionChildren(
	entry: PortableCapturedWifiEntryV1,
	rows: List<ImportedWifiChildEntity>,
): List<WifiSessionChildV2> {
	val context = currentCoroutineContext()
	context.ensureActive()
	val revisions = mutableMapOf<Pair<String, String>, Long>()
	for (run in entry.runs) {
		context.ensureActive()
		for (observation in run.observations) {
			context.ensureActive()
			check(revisions.put(run.identity.value to observation.identity.value,
				observation.semanticRevision) == null)
		}
	}
	context.ensureActive()
	return rows.map { row ->
		context.ensureActive()
		check(row.entryIdentity == entry.identity.value)
		val revision = requireNotNull(revisions[row.runIdentity to row.observationIdentity])
		WifiSessionChildV2(row.entryIdentity, row.runIdentity, row.observationIdentity, revision,
			row.itemIndex, row.providerTimestampNanos, row.observedWallTimeMs,
			row.wallTimeUncertaintyMs, row.bssid, row.ssid, row.frequencyMhz, row.signalDbm,
			row.contentChecksum)
	}
}

internal suspend fun mapImportedCellSessionChildren(
	entry: PortableCapturedCellEntryV1,
	rows: List<ImportedCellChildEntity>,
): List<CellSessionChildV2> {
	val context = currentCoroutineContext()
	context.ensureActive()
	val revisions = mutableMapOf<Pair<String, String>, Long>()
	for (run in entry.runs) {
		context.ensureActive()
		for (observation in run.observations) {
			context.ensureActive()
			check(revisions.put(run.identity.value to observation.identity.value,
				observation.semanticRevision) == null)
		}
	}
	context.ensureActive()
	return rows.map { row ->
		context.ensureActive()
		check(row.entryIdentity == entry.identity.value)
		val revision = requireNotNull(revisions[row.runIdentity to row.observationIdentity])
		CellSessionChildV2(row.entryIdentity, row.runIdentity, row.observationIdentity, revision,
			row.itemIndex, row.providerTimestampNanos, row.observedWallTimeMs,
			row.wallTimeUncertaintyMs, row.towerIdentity, row.radioType, row.registered,
			row.signalDbm, row.contentChecksum)
	}
}
