package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ExplorationCellEntity - discovered S2 cell entity")
class ExplorationCellEntityTest {

	private fun entity(
		id: Long = 0,
		cellToken: String = "abc123",
		level: Int = 14,
		quality: Int = 2,
		firstDiscoveredAt: Long = 1000L,
		lastVisitedAt: Long = 2000L,
		visitCount: Int = 1,
		seasonBitmask: Int = 0,
		centerLatE7: Int = 500000000,
		centerLonE7: Int = 140000000,
		createdAt: Long = 1000L
	) = ExplorationCellEntity(
		id, cellToken, level, quality, firstDiscoveredAt, lastVisitedAt,
		visitCount, seasonBitmask, centerLatE7, centerLonE7, createdAt
	)

	@Nested
	@DisplayName("Construction and defaults")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val e = entity()
			e.cellToken shouldBe "abc123"
			e.level shouldBe 14
			e.quality shouldBe 2
			e.visitCount shouldBe 1
			e.seasonBitmask shouldBe 0
			e.centerLatE7 shouldBe 500000000
			e.centerLonE7 shouldBe 140000000
		}

		@Test
		fun `visitCount defaults to 1`() {
			ExplorationCellEntity(
				cellToken = "x", level = 14, quality = 0,
				firstDiscoveredAt = 0, lastVisitedAt = 0,
				centerLatE7 = 0, centerLonE7 = 0, createdAt = 0
			).visitCount shouldBe 1
		}

		@Test
		fun `seasonBitmask defaults to 0`() {
			ExplorationCellEntity(
				cellToken = "x", level = 14, quality = 0,
				firstDiscoveredAt = 0, lastVisitedAt = 0,
				centerLatE7 = 0, centerLonE7 = 0, createdAt = 0
			).seasonBitmask shouldBe 0
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
			entity(cellToken = "a") shouldNotBe entity(cellToken = "b")
		}

		@Test
		fun `copy`() {
			val e = entity().copy(visitCount = 5, seasonBitmask = 0b1010)
			e.visitCount shouldBe 5
			e.seasonBitmask shouldBe 0b1010
		}
	}
}
