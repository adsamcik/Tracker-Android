package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.data.NetworkTypeSignalRow
import com.adsamcik.tracker.shared.base.database.data.TopCellTowerRow
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.CellSignalReport
import com.adsamcik.tracker.stats.api.repository.CellSignalRepository
import com.adsamcik.tracker.stats.api.repository.CellTowerStat
import com.adsamcik.tracker.stats.api.repository.NetworkTypeSignalStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Derives the aggregate cell-signal report from raw cell samples.
 *
 * Signal strength is persisted as ASU, whose meaningful range depends on the radio technology, so a
 * raw average is not comparable across technologies. This repository normalizes each average against
 * the technology's max ASU (identical to the ranges the map's cell heatmap uses) to produce a 0..100
 * quality percentage that IS comparable across GSM/LTE/NR/etc.
 */
class DefaultCellSignalRepository @Inject constructor(
	private val cellSampleDao: CellSampleDao,
	private val dispatchers: DispatchersProvider,
) : CellSignalRepository {

	override suspend fun getReport(
		topTowerLimit: Int,
	): Either<StatsError, CellSignalReport> = withContext(dispatchers.io) {
		try {
			val totals = cellSampleDao.getSignalReportTotals()
			val networkTypeRows = cellSampleDao.getNetworkTypeSignalRows()
			val topTowerRows = cellSampleDao.getTopCellTowers(topTowerLimit.coerceAtLeast(1))

			CellSignalReport(
				totalSamples = totals.totalSamples.coerceAtLeast(0L),
				distinctTowers = totals.distinctTowers.coerceAtLeast(0L),
				networkTypes = networkTypeRows.map { it.toStat(totals.totalSamples) },
				topTowers = topTowerRows.map { it.toStat() },
			).right()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load cell signal report: ${e.message}", e).left()
		}
	}

	private fun NetworkTypeSignalRow.toStat(totalSamples: Long): NetworkTypeSignalStat =
		NetworkTypeSignalStat(
			networkType = networkType,
			sampleCount = sampleCount,
			sharePercent = percentageOf(sampleCount, totalSamples),
			avgQualityPercent = asuToQualityPercent(avgAsu, networkType),
			distinctCells = distinctCells,
		)

	private fun TopCellTowerRow.toStat(): CellTowerStat =
		CellTowerStat(
			cellId = cellId,
			mcc = mcc,
			mnc = mnc,
			networkType = networkType,
			sampleCount = sampleCount,
			avgQualityPercent = asuToQualityPercent(avgAsu, networkType),
		)

	private companion object {
		private const val PERCENT = 100.0

		// ASU maxima per radio technology, mirroring the map's CellHeatmapLayer normalization.
		private const val GSM_MAX_ASU = 31.0
		private const val CDMA_MAX_ASU = 16.0
		private const val WCDMA_MAX_ASU = 31.0
		private const val LTE_NR_MAX_ASU = 97.0

		private fun percentageOf(part: Long, total: Long): Double =
			if (total > 0L) (part.toDouble() / total.toDouble()) * PERCENT else 0.0

		private fun asuToQualityPercent(avgAsu: Double, networkType: Int): Double {
			if (avgAsu <= 0.0) return 0.0
			val maxAsu = when (CellType.entries.getOrNull(networkType)) {
				CellType.GSM -> GSM_MAX_ASU
				CellType.CDMA -> CDMA_MAX_ASU
				CellType.WCDMA -> WCDMA_MAX_ASU
				CellType.LTE,
				CellType.NR -> LTE_NR_MAX_ASU
				CellType.Unknown,
				CellType.None,
				null -> LTE_NR_MAX_ASU
			}
			return ((avgAsu / maxAsu).coerceIn(0.0, 1.0)) * PERCENT
		}
	}
}
