package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import javax.inject.Inject
import javax.inject.Singleton

/** One bounded Room snapshot composing local and portable-origin Pressure-only rows. */
@Singleton
internal class PressureHistoryPageReader @Inject constructor(
	private val database: AppDatabase,
	private val liveSelector: PressureHistorySelector,
	private val importedEvaluator: ImportedPressureHistoryEvaluator,
	private val portableReader: PortablePressureRoomReader,
) {
	internal suspend fun selectRecent(limit: Int): List<PressureOnlyHistoryEntry> =
		database.withTransaction {
			require(limit in 1..ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES)
			val live = liveSelector.discoverRecentPressureOnlyInTransaction(limit)
			val imported = importedEvaluator.selectRecentInTransaction(limit)
			PressureHistoryPageComposer.compose(
				live = live.mapNotNull { entry ->
					entry.toPublicPressureOnlyEntryOrNull()?.let { public ->
						LocalPressurePageCandidate(
							entry = public,
							portable = portableReader.portableEntryForComparison(entry),
						)
					}
				},
				imported = imported,
				limit = limit,
			)
		}
	}

internal data class LocalPressurePageCandidate(
	val entry: PressureOnlyHistoryEntry,
	val portable: PortablePressureEntryV1?,
)

internal object PressureHistoryPageComposer {
	fun compose(
		live: List<LocalPressurePageCandidate>,
		imported: List<ImportedPressureHistoryEvaluation>,
		limit: Int,
	): List<PressureOnlyHistoryEntry> {
		require(limit > 0)
		val livePortable = live.mapNotNull(LocalPressurePageCandidate::portable).toSet()
		val rows = buildList<PressurePageRow> {
			live.mapTo(this) { candidate ->
				candidate.entry.toPageRow(LOCAL_ORIGIN_ORDER)
			}
			imported.forEach { evaluation ->
				val exactDuplicate = evaluation is ImportedPressureHistoryEvaluation.Readable &&
					evaluation.isReExportable && evaluation.latest.entry in livePortable
				if (!exactDuplicate) {
					add(evaluation.toPublicPressureOnlyEntry().toPageRow(IMPORTED_ORIGIN_ORDER))
				}
			}
		}
		return rows.sortedWith(
			compareByDescending<PressurePageRow>(PressurePageRow::recencyStartMs)
				.thenByDescending(PressurePageRow::recencyEndMs)
				.thenBy(PressurePageRow::originOrder),
		).take(limit).map(PressurePageRow::entry)
	}

	private fun PressureOnlyHistoryEntry.toPageRow(originOrder: Int) = PressurePageRow(
		entry = this,
		recencyStartMs = startTime.raw,
		recencyEndMs = endTime.raw,
		originOrder = originOrder,
	)

	private data class PressurePageRow(
		val entry: PressureOnlyHistoryEntry,
		val recencyStartMs: Long,
		val recencyEndMs: Long,
		val originOrder: Int,
	)

	private companion object {
		const val LOCAL_ORIGIN_ORDER = 0
		const val IMPORTED_ORIGIN_ORDER = 1
	}
}
