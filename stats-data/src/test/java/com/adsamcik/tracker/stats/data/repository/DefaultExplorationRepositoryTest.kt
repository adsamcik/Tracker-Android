package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DefaultExplorationRepository")
class DefaultExplorationRepositoryTest {

	private val dao: ExplorationCellDao = mockk()
	private val repository = DefaultExplorationRepository(dao)

	@Nested
	@DisplayName("observeCellCount")
	inner class ObserveCellCount {

		@Test
		fun `delegates to dao countAtLevelFlow`() = runTest {
			every { dao.countAtLevelFlow(14) } returns flowOf(42)

			val result = repository.observeCellCount(14).first()
			result shouldBe 42
		}

		@Test
		fun `passes level parameter to dao`() = runTest {
			every { dao.countAtLevelFlow(10) } returns flowOf(100)

			val result = repository.observeCellCount(10).first()
			result shouldBe 100
		}

		@Test
		fun `returns zero when dao returns zero`() = runTest {
			every { dao.countAtLevelFlow(14) } returns flowOf(0)

			val result = repository.observeCellCount(14).first()
			result shouldBe 0
		}
	}

	@Nested
	@DisplayName("observeStats")
	inner class ObserveStats {

		@Test
		fun `maps cell count to ExplorationStats`() = runTest {
			every { dao.countAtLevelFlow(14) } returns flowOf(50)

			val stats = repository.observeStats().first()
			stats.totalCells shouldBe 50
			stats.recentDiscoveries shouldBe 0
		}

		@Test
		fun `always uses level 14 for stats`() = runTest {
			every { dao.countAtLevelFlow(14) } returns flowOf(99)

			val stats = repository.observeStats().first()
			stats.totalCells shouldBe 99
		}

		@Test
		fun `recentDiscoveries is always zero`() = runTest {
			every { dao.countAtLevelFlow(14) } returns flowOf(1000)

			val stats = repository.observeStats().first()
			stats.recentDiscoveries shouldBe 0
		}
	}
}
