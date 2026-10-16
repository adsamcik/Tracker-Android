package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.CellObservedTower
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact imported Cell details never imply a tower position or native provider authority. */
internal suspend fun AppDatabase.withImportedCellDetails(
	evaluation: ImportedCellProductEvaluation,
	publicEntry: CellHistoryEntry,
): CellHistoryEntry {
	val readable = evaluation as? ImportedCellProductEvaluation.Readable ?: return publicEntry
	if (publicEntry.observations.isEmpty()) {
		return publicEntry
	}
	val visible = readable.entry.runs.filterNot { it.identity.value in readable.deletedRunIdentities }
		.flatMap { run -> run.observations.map { run to it } }
	check(visible.size == publicEntry.observations.size)
	val owners = visible.associate { (run, observation) ->
		observation.identity.value to (run.identity.value to observation)
	}
	check(owners.size == visible.size)
	val rows = mutableListOf<ImportedCellChildEntity>()
	for ((runIdentity, observations) in owners.entries.groupBy { it.value.first }) {
		for (ids in observations.map { it.key }.chunked(IMPORTED_CELL_OWNER_BATCH)) {
			currentCoroutineContext().ensureActive()
			rows += importedRadioChildDao().cellSessionChildrenForObservations(readable.entry.identity.value,
				readable.candidate.importRevision, runIdentity, ids, MAX_IMPORTED_CELL_DETAILS - rows.size + 1)
			if (rows.size > MAX_IMPORTED_CELL_DETAILS) {
				return publicEntry.copy(
				state = CellHistoryProductState.FAILED, coverage = CellHistoryCoverage.NONE,
				observations = emptyList(), causes = setOf(CellHistoryCause.READ_BUDGET_EXCEEDED))
			}
		}
	}
	// An imported correction can retain children for an erased run. Keep its details hidden.
	val byObservation = rows.groupBy { row ->
		val owner = requireNotNull(owners[row.observationIdentity])
		validateImportedCellChild(row, readable.entry.identity.value, readable.candidate.importRevision,
			owner.first, owner.second)
		row.observationIdentity
	}
	check(visible.all { (_, observation) ->
		// The current format permits an absent detail group; a present group must be complete.
		byObservation[observation.identity.value]?.size?.let { it == observation.acceptedChildCount } != false
	})
	return publicEntry.copy(observations = visible.mapIndexed { index, (_, observation) ->
		publicEntry.observations[index].copy(towers = byObservation[observation.identity.value]
			.orEmpty().map { row -> CellObservedTower(row.observedWallTimeMs,
				row.wallTimeUncertaintyMs, row.towerIdentity?.takeIf(String::isNotBlank),
				CellHistoryTechnology.valueOf(row.radioType), row.registered, row.signalDbm) })
	})
}

private const val MAX_IMPORTED_CELL_DETAILS = 4_096
private const val IMPORTED_CELL_OWNER_BATCH = 64

/** Exact selected imported parent details; legacy absent groups remain typed summaries. */
internal suspend fun AppDatabase.measuredImportedCellChildren(
	evaluation: ImportedCellProductEvaluation.Readable,
	runIdentity: String,
	parent: com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1,
): List<ImportedCellChildEntity> {
	val owner = evaluation.entry.runs.singleOrNull { it.identity.value == runIdentity }
	check(owner != null && owner.observations.singleOrNull { it.identity == parent.identity } == parent)
	val rows = importedRadioChildDao().cellSessionChildrenForObservations(evaluation.entry.identity.value,
		evaluation.candidate.importRevision, runIdentity, listOf(parent.identity.value),
            Math.addExact(parent.acceptedChildCount, 1))
	check(rows.size <= parent.acceptedChildCount && (rows.isEmpty() || rows.size == parent.acceptedChildCount))
	check(rows.map { it.itemIndex }.distinct().size == rows.size)
	rows.forEach { row -> validateImportedCellChild(row, evaluation.entry.identity.value,
		evaluation.candidate.importRevision, runIdentity, parent) }
	return rows
}

private fun validateImportedCellChild(row: ImportedCellChildEntity, entryIdentity: String,
	importRevision: Long, runIdentity: String,
	parent: com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1) {
	check(row.entryIdentity == entryIdentity && row.entryImportRevision == importRevision &&
		row.runIdentity == runIdentity && row.observationIdentity == parent.identity.value)
	check(row.itemIndex >= 0 && row.providerTimestampNanos > 0L && row.wallTimeUncertaintyMs >= 0L &&
		row.observedWallTimeMs >= row.wallTimeUncertaintyMs &&
            row.observedWallTimeMs - row.wallTimeUncertaintyMs >= parent.coverageStartTimeMs &&
		row.observedWallTimeMs <= Long.MAX_VALUE - row.wallTimeUncertaintyMs &&
		row.observedWallTimeMs + row.wallTimeUncertaintyMs <= parent.latestPossibleTimeMs)
	check(row.contentChecksum == PortableRadioChildIntegrity.digest("cell",
		listOf(row.entryIdentity, row.runIdentity, row.observationIdentity),
		listOf(parent.semanticRevision, row.itemIndex, row.providerTimestampNanos, row.observedWallTimeMs,
			row.wallTimeUncertaintyMs, row.towerIdentity, row.radioType, row.registered, row.signalDbm)))
}
