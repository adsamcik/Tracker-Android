package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DailySummaryEntity - materialized daily aggregate")
class DailySummaryEntityTest {

	private fun entity(
		dateEpochDay: Long = 19800L,
		totalDistanceM: Float = 5000f,
		totalSteps: Int = 8000,
		totalDurationMs: Long = 7200000L,
		tripCount: Int = 3,
		activeTrackingMs: Long = 3600000L,
		lastUpdatedMs: Long = 1000L,
		createdAt: Long = 500L
	) = DailySummaryEntity(
		dateEpochDay, totalDistanceM, totalSteps, totalDurationMs,
		tripCount, activeTrackingMs, lastUpdatedMs, createdAt
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val e = entity()
			e.dateEpochDay shouldBe 19800L
			e.totalDistanceM shouldBe 5000f
			e.totalSteps shouldBe 8000
			e.totalDurationMs shouldBe 7200000L
			e.tripCount shouldBe 3
			e.activeTrackingMs shouldBe 3600000L
			e.lastUpdatedMs shouldBe 1000L
			e.createdAt shouldBe 500L
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
			entity(tripCount = 1) shouldNotBe entity(tripCount = 2)
		}

		@Test
		fun `copy`() {
			val e = entity().copy(totalSteps = 15000)
			e.totalSteps shouldBe 15000
			e.dateEpochDay shouldBe 19800L
		}
	}
}
