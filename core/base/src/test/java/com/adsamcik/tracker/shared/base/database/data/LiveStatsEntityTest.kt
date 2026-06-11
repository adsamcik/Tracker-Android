package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LiveStatsEntity - real-time dashboard stats")
class LiveStatsEntityTest {

	private fun entity(
		id: Int = 0,
		dateEpochDay: Long = 19800L,
		sessionDistanceM: Float = 1000f,
		sessionSteps: Int = 2000,
		sessionDurationMs: Long = 3600000L,
		dayTotalDistanceM: Float = 5000f,
		dayTotalSteps: Int = 10000,
		dayTotalDurationMs: Long = 18000000L,
		lastUpdatedMs: Long = 1000L
	) = LiveStatsEntity(
		id, dateEpochDay, sessionDistanceM, sessionSteps, sessionDurationMs,
		dayTotalDistanceM, dayTotalSteps, dayTotalDurationMs, lastUpdatedMs
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val e = entity()
			e.id shouldBe 0
			e.dateEpochDay shouldBe 19800L
			e.sessionDistanceM shouldBe 1000f
			e.sessionSteps shouldBe 2000
			e.sessionDurationMs shouldBe 3600000L
			e.dayTotalDistanceM shouldBe 5000f
			e.dayTotalSteps shouldBe 10000
			e.dayTotalDurationMs shouldBe 18000000L
			e.lastUpdatedMs shouldBe 1000L
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
			entity(sessionSteps = 100) shouldNotBe entity(sessionSteps = 200)
		}

		@Test
		fun `copy`() {
			val e = entity().copy(sessionDistanceM = 9999f)
			e.sessionDistanceM shouldBe 9999f
		}
	}
}
