package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.error.StatsError

const val DEFAULT_TOP_CELL_TOWER_LIMIT = 10

/**
 * Signal quality for one radio technology.
 *
 * @property networkType `CellType` ordinal as persisted by the tracking pipeline; the UI resolves
 *   it to a localized technology name.
 * @property sampleCount number of cell samples recorded on this technology.
 * @property sharePercent this technology's share of all cell samples, 0..100.
 * @property avgQualityPercent mean signal strength normalized against the technology's ASU range,
 *   0..100 (comparable across technologies).
 * @property distinctCells distinct towers seen on this technology.
 */
data class NetworkTypeSignalStat(
	val networkType: Int,
	val sampleCount: Long,
	val sharePercent: Double,
	val avgQualityPercent: Double,
	val distinctCells: Long,
)

/**
 * A frequently-observed cell tower.
 *
 * @property avgQualityPercent mean signal strength normalized against the technology's ASU range,
 *   0..100.
 */
data class CellTowerStat(
	val cellId: Long,
	val mcc: Int,
	val mnc: Int,
	val networkType: Int,
	val sampleCount: Long,
	val avgQualityPercent: Double,
)

/**
 * Aggregate cell-signal report derived from raw cell samples.
 */
data class CellSignalReport(
	val totalSamples: Long,
	val distinctTowers: Long,
	val networkTypes: List<NetworkTypeSignalStat>,
	val topTowers: List<CellTowerStat>,
)

interface CellSignalRepository {
	suspend fun getReport(
		topTowerLimit: Int = DEFAULT_TOP_CELL_TOWER_LIMIT,
	): Either<StatsError, CellSignalReport>
}
