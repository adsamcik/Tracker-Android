package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ExplorationStreakEntity - exploration streak tracking")
class ExplorationStreakEntityTest {

	private fun entity(
		type: String = "DAILY_DISCOVERY",
		currentCount: Int = 5,
		bestCount: Int = 10,
		lastIncrementDay: Long = 19800L,
		updatedAt: Long = 1000L
	) = ExplorationStreakEntity(type, currentCount, bestCount, lastIncrementDay, updatedAt)

	@Nested
	@DisplayName("Construction and defaults")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val e = entity()
			e.type shouldBe "DAILY_DISCOVERY"
			e.currentCount shouldBe 5
			e.bestCount shouldBe 10
			e.lastIncrementDay shouldBe 19800L
			e.updatedAt shouldBe 1000L
		}

		@Test
		fun `currentCount defaults to 0`() {
			ExplorationStreakEntity(type = "TEST").currentCount shouldBe 0
		}

		@Test
		fun `bestCount defaults to 0`() {
			ExplorationStreakEntity(type = "TEST").bestCount shouldBe 0
		}

		@Test
		fun `lastIncrementDay defaults to 0`() {
			ExplorationStreakEntity(type = "TEST").lastIncrementDay shouldBe 0
		}

		@Test
		fun `updatedAt defaults to 0`() {
			ExplorationStreakEntity(type = "TEST").updatedAt shouldBe 0
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			entity() shouldBe entity()
		}

		@Test
		fun `inequality`() {
			entity(currentCount = 1) shouldNotBe entity(currentCount = 2)
		}

		@Test
		fun `copy`() {
			val e = entity().copy(currentCount = 0, bestCount = 15)
			e.currentCount shouldBe 0
			e.bestCount shouldBe 15
		}
	}
}
