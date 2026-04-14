package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AchievementProgressEntity - achievement tracking entity")
class AchievementProgressEntityTest {

	private fun entity(
		id: Long = 0,
		achievementId: String = "first_steps",
		currentValue: Long = 50,
		targetValue: Long = 100,
		tier: Int? = null,
		unlockedAt: Long? = null,
		updatedAt: Long = 1000L,
		notifiedAt: Long? = null
	) = AchievementProgressEntity(id, achievementId, currentValue, targetValue, tier, unlockedAt, updatedAt, notifiedAt)

	@Nested
	@DisplayName("Construction and defaults")
	inner class Construction {
		@Test
		fun `stores required fields`() {
			val e = entity()
			e.achievementId shouldBe "first_steps"
			e.currentValue shouldBe 50
			e.targetValue shouldBe 100
		}

		@Test
		fun `id defaults to 0`() {
			entity().id shouldBe 0
		}

		@Test
		fun `currentValue defaults to 0`() {
			AchievementProgressEntity(achievementId = "test", targetValue = 100).currentValue shouldBe 0
		}

		@Test
		fun `nullable fields default to null`() {
			val e = entity()
			e.tier shouldBe null
			e.unlockedAt shouldBe null
			e.notifiedAt shouldBe null
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
			entity(currentValue = 10) shouldNotBe entity(currentValue = 20)
		}

		@Test
		fun `copy`() {
			val e = entity().copy(currentValue = 100, unlockedAt = 5000L)
			e.currentValue shouldBe 100
			e.unlockedAt shouldBe 5000L
		}
	}
}
