package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.data.CellSampleTotals
import com.adsamcik.tracker.shared.base.database.data.NetworkTypeSignalRow
import com.adsamcik.tracker.shared.base.database.data.TopCellTowerRow
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.CellTowerStat
import com.adsamcik.tracker.stats.api.repository.NetworkTypeSignalStat
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCellSignalRepositoryTest {

	private val cellSampleDao: CellSampleDao = mockk()
	private val testDispatcher = StandardTestDispatcher()
	private val dispatchers = object : DispatchersProvider {
		override val io: CoroutineDispatcher = testDispatcher
		override val default: CoroutineDispatcher = testDispatcher
		override val main: CoroutineDispatcher = testDispatcher
		override val unconfined: CoroutineDispatcher = testDispatcher
	}
	private val repository = DefaultCellSignalRepository(
		cellSampleDao = cellSampleDao,
		dispatchers = dispatchers,
	)

	private companion object {
		private const val GSM_ORDINAL = 1
		private const val LTE_ORDINAL = 4
	}

	@Test
	fun `getReport normalizes ASU per technology and computes share`() = runTest(testDispatcher) {
		coEvery { cellSampleDao.getSignalReportTotals() } returns
			CellSampleTotals(totalSamples = 100L, distinctTowers = 8L)
		coEvery { cellSampleDao.getNetworkTypeSignalRows() } returns listOf(
			// LTE: 48.5 / 97 -> 50 %
			NetworkTypeSignalRow(LTE_ORDINAL, sampleCount = 80L, avgAsu = 48.5, distinctCells = 5L),
			// GSM: 15.5 / 31 -> 50 %
			NetworkTypeSignalRow(GSM_ORDINAL, sampleCount = 20L, avgAsu = 15.5, distinctCells = 3L),
		)
		coEvery { cellSampleDao.getTopCellTowers(10) } returns listOf(
			// LTE tower at full signal: 97 / 97 -> 100 %
			TopCellTowerRow(
				cellId = 123L, mcc = 230, mnc = 1, networkType = LTE_ORDINAL,
				sampleCount = 40L, avgAsu = 97.0,
			),
		)

		val report = repository.getReport().fold(
			ifLeft = { error("Expected cell signal report but got $it") },
			ifRight = { it },
		)

		report.totalSamples shouldBe 100L
		report.distinctTowers shouldBe 8L
		report.networkTypes shouldBe listOf(
			NetworkTypeSignalStat(LTE_ORDINAL, 80L, sharePercent = 80.0, avgQualityPercent = 50.0, distinctCells = 5L),
			NetworkTypeSignalStat(GSM_ORDINAL, 20L, sharePercent = 20.0, avgQualityPercent = 50.0, distinctCells = 3L),
		)
		report.topTowers shouldBe listOf(
			CellTowerStat(123L, 230, 1, LTE_ORDINAL, 40L, avgQualityPercent = 100.0),
		)
	}

	@Test
	fun `getReport maps zero ASU to zero quality`() = runTest(testDispatcher) {
		coEvery { cellSampleDao.getSignalReportTotals() } returns
			CellSampleTotals(totalSamples = 10L, distinctTowers = 1L)
		coEvery { cellSampleDao.getNetworkTypeSignalRows() } returns listOf(
			NetworkTypeSignalRow(LTE_ORDINAL, sampleCount = 10L, avgAsu = 0.0, distinctCells = 1L),
		)
		coEvery { cellSampleDao.getTopCellTowers(10) } returns emptyList()

		val report = repository.getReport().fold(
			ifLeft = { error("Expected cell signal report but got $it") },
			ifRight = { it },
		)

		report.networkTypes.single().avgQualityPercent shouldBe 0.0
	}

	@Test
	fun `getReport returns DatabaseError when the dao throws`() = runTest(testDispatcher) {
		coEvery { cellSampleDao.getSignalReportTotals() } throws IllegalStateException("boom")

		val error = repository.getReport().fold(
			ifLeft = { it },
			ifRight = { error("Expected failure but got $it") },
		)

		(error as StatsError.DatabaseError).message shouldBe "Failed to load cell signal report: boom"
		error.cause?.message shouldBe "boom"
	}
}
