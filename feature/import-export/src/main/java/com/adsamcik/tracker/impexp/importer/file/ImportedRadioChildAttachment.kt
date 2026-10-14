package com.adsamcik.tracker.impexp.importer.file

import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.portable.CellBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.CellSessionChildV2
import com.adsamcik.tracker.impexp.portable.WifiBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.WifiSessionChildV2
import com.adsamcik.tracker.impexp.portable.radioChildFitsCoverage
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** A source importer admits the parent first; this adds only exact, epoch-bound details. */
internal class ImportedRadioChildAttachment(private val database: AppDatabase) {
	suspend fun wifiSession(children: List<WifiSessionChildV2>, revision: Long, epoch: Long) =
		attachBatches(children, epoch) { batch, state ->
			val dao = database.importedRadioChildDao()
			batch.forEach { child ->
				val parent = dao.wifiSessionParent(child.entryIdentity, revision,
					child.runIdentity, child.observationIdentity)
					?: error("Imported Wi-Fi observation is unavailable")
				check(parent.semanticRevision == child.semanticRevision)
				checkCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
					parent.coverageStartTimeMs, parent.latestPossibleTimeMs, state.retainedFromMs)
				val row = ImportedWifiChildEntity(child.entryIdentity, revision, child.runIdentity,
					child.observationIdentity, child.itemIndex, child.providerTimestampNanos,
					child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.bssid, child.ssid,
					child.frequencyMhz, child.signalDbm, child.contentChecksum)
				val existing = dao.wifiSessionChild(child.entryIdentity, revision,
					child.runIdentity, child.observationIdentity, child.itemIndex)
				if (existing == null) dao.insertWifiSession(listOf(row)) else check(existing == row)
			}
		}

	suspend fun cellSession(children: List<CellSessionChildV2>, revision: Long, epoch: Long) =
		attachBatches(children, epoch) { batch, state ->
			val dao = database.importedRadioChildDao()
			batch.forEach { child ->
				val parent = dao.cellSessionParent(child.entryIdentity, revision,
					child.runIdentity, child.observationIdentity)
					?: error("Imported Cell observation is unavailable")
				check(parent.semanticRevision == child.semanticRevision)
				checkCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
					parent.coverageStartTimeMs, parent.latestPossibleTimeMs, state.retainedFromMs)
				val row = ImportedCellChildEntity(child.entryIdentity, revision, child.runIdentity,
					child.observationIdentity, child.itemIndex, child.providerTimestampNanos,
					child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.towerIdentity,
					child.radioType, child.registered, child.signalDbm, child.contentChecksum)
				val existing = dao.cellSessionChild(child.entryIdentity, revision,
					child.runIdentity, child.observationIdentity, child.itemIndex)
				if (existing == null) dao.insertCellSession(listOf(row)) else check(existing == row)
			}
		}

	suspend fun wifiBackground(children: List<WifiBackgroundChildV2>, epoch: Long) =
		attachBatches(children, epoch) { batch, state ->
			val dao = database.importedRadioChildDao()
			batch.forEach { child ->
				// The owning archive can differ from this file after an overlapping import.
				val parent = dao.wifiBackgroundParent(child.factIdentity, child.semanticRevision)
					?: error("Imported Wi-Fi background fact is unavailable")
				check(state.acceptsRetainedEpoch(parent.collectedDataEpoch))
				checkCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
					parent.coverageStartTimeMs, parent.latestPossibleTimeMs, state.retainedFromMs)
				val row = ImportedAmbientWifiChildEntity(parent.archiveId, child.factIdentity,
					child.semanticRevision, child.itemIndex, child.providerTimestampNanos,
					child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.bssid, child.ssid,
					child.frequencyMhz, child.signalDbm, child.contentChecksum)
				val existing = dao.wifiBackgroundChild(parent.archiveId, child.factIdentity,
					child.semanticRevision, child.itemIndex)
				if (existing == null) dao.insertWifiBackground(listOf(row)) else check(existing == row)
			}
		}

	suspend fun cellBackground(children: List<CellBackgroundChildV2>, epoch: Long) =
		attachBatches(children, epoch) { batch, state ->
			val dao = database.importedRadioChildDao()
			batch.forEach { child ->
				val parent = dao.cellBackgroundParent(child.factIdentity, child.semanticRevision)
					?: error("Imported Cell background fact is unavailable")
				check(state.acceptsRetainedEpoch(parent.collectedDataEpoch))
				checkCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
					parent.coverageStartTimeMs, parent.latestPossibleTimeMs, state.retainedFromMs)
				val row = ImportedAmbientCellChildEntity(parent.archiveId, child.factIdentity,
					child.semanticRevision, child.itemIndex, child.providerTimestampNanos,
					child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.towerIdentity,
					child.radioType, child.registered, child.signalDbm, child.contentChecksum)
				val existing = dao.cellBackgroundChild(parent.archiveId, child.factIdentity,
					child.semanticRevision, child.itemIndex)
				if (existing == null) dao.insertCellBackground(listOf(row)) else check(existing == row)
			}
		}

	private suspend fun retainedState(expected: Long): SourceEvidenceState {
		if (database.activeRetentionFloorSettlement() != null)
			throw IOException("Recorded data cleanup is in progress")
		val state = database.sourceEvidenceStateDao().get()
			?: error("Recorded data is unavailable during radio import")
		check(state.collectedDataEpoch == expected) {
			"Recorded data changed during radio import"
		}
		return state
	}

	private fun checkCoverage(wallMs: Long, uncertaintyMs: Long,
		coverageStartMs: Long, latestPossibleMs: Long, floor: Long?) {
		check(radioChildFitsCoverage(wallMs, uncertaintyMs, coverageStartMs, latestPossibleMs)) {
			"Radio details are outside their saved observation"
		}
		check(floor == null || coverageStartMs >= floor) { "Radio observation is no longer retained" }
	}

	private suspend fun <T> attachBatches(
		children: List<T>, epoch: Long, attach: suspend (List<T>, SourceEvidenceState) -> Unit,
	) {
		forEachRadioChildBatch(children) { batch ->
			database.withTransaction {
				val state = retainedState(epoch)
				attach(batch, state)
				check(retainedState(epoch).retainedFromMs == state.retainedFromMs) {
					"Recorded data retention changed during radio import"
				}
			}
		}
	}

}

/** Keeps one import transaction bounded and lets a retry resume after a later failure. */
internal suspend fun <T> forEachRadioChildBatch(
	children: List<T>, block: suspend (List<T>) -> Unit,
) {
	currentCoroutineContext().ensureActive()
	if (children.isEmpty()) {
		block(emptyList())
		return
	}
	for (start in children.indices step RADIO_CHILD_IMPORT_BATCH_SIZE) {
		currentCoroutineContext().ensureActive()
		block(children.subList(start, minOf(start + RADIO_CHILD_IMPORT_BATCH_SIZE, children.size)))
	}
}

internal const val RADIO_CHILD_IMPORT_BATCH_SIZE = 256
