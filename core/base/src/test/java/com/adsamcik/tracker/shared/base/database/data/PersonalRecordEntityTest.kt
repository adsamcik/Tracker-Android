package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PersonalRecordEntity - personal record tracking")
class PersonalRecordEntityTest {

	private fun record(
		id: Long = 0,
		metric: String = "max_speed",
		value: Double = 12.5,
		achievedAt: Long = 1000L,
		updatedAt: Long = 2000L
	) = PersonalRecordEntity(id, metric, value, achievedAt, updatedAt)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val r = record()
			r.metric shouldBe "max_speed"
			r.value shouldBe 12.5
			r.achievedAt shouldBe 1000L
			r.updatedAt shouldBe 2000L
		}

		@Test
		fun `id defaults to 0`() {
			record().id shouldBe 0
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			record() shouldBe record()
		}

		@Test
		fun `inequality`() {
			record(metric = "a") shouldNotBe record(metric = "b")
		}

		@Test
		fun `copy`() {
			val r = record().copy(value = 25.0, updatedAt = 5000L)
			r.value shouldBe 25.0
			r.updatedAt shouldBe 5000L
		}
	}
}
