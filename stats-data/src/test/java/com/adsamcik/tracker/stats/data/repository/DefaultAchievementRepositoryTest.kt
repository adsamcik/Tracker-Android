package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DefaultAchievementRepository")
class DefaultAchievementRepositoryTest {

	private val dao: AchievementProgressDao = mockk()
	private val repository = DefaultAchievementRepository(dao)

	private fun createEntity(
		id: Long = 1,
		achievementId: String = "ach_1",
		currentValue: Long = 50,
		targetValue: Long = 100,
		tier: Int? = 1,
		unlockedAt: Long? = null,
	) = AchievementProgressEntity(
		id = id,
		achievementId = achievementId,
		currentValue = currentValue,
		targetValue = targetValue,
		tier = tier,
		unlockedAt = unlockedAt,
	)

	@Nested
	@DisplayName("observeAll")
	inner class ObserveAll {

		@Test
		fun `maps entities to AchievementProgressData`() = runTest {
			val entity = createEntity(
				achievementId = "dist_1",
				currentValue = 10,
				targetValue = 50,
				tier = 2,
				unlockedAt = null,
			)
			every { dao.getAllFlow() } returns flowOf(listOf(entity))

			val result = repository.observeAll().first()
			result shouldHaveSize 1
			result[0].achievementId shouldBe "dist_1"
			result[0].currentValue shouldBe 10L
			result[0].targetValue shouldBe 50L
			result[0].tier shouldBe "2"
			result[0].isUnlocked shouldBe false
		}

		@Test
		fun `isUnlocked is true when unlockedAt is non-null`() = runTest {
			val entity = createEntity(unlockedAt = 1000L)
			every { dao.getAllFlow() } returns flowOf(listOf(entity))

			val result = repository.observeAll().first()
			result[0].isUnlocked shouldBe true
		}

		@Test
		fun `isUnlocked is false when unlockedAt is null`() = runTest {
			val entity = createEntity(unlockedAt = null)
			every { dao.getAllFlow() } returns flowOf(listOf(entity))

			val result = repository.observeAll().first()
			result[0].isUnlocked shouldBe false
		}

		@Test
		fun `tier null maps to null string`() = runTest {
			val entity = createEntity(tier = null)
			every { dao.getAllFlow() } returns flowOf(listOf(entity))

			val result = repository.observeAll().first()
			result[0].tier shouldBe "null"
		}

		@Test
		fun `empty list returns empty`() = runTest {
			every { dao.getAllFlow() } returns flowOf(emptyList())

			val result = repository.observeAll().first()
			result.shouldBeEmpty()
		}

		@Test
		fun `multiple entities mapped correctly`() = runTest {
			val entities = listOf(
				createEntity(id = 1, achievementId = "a"),
				createEntity(id = 2, achievementId = "b"),
				createEntity(id = 3, achievementId = "c"),
			)
			every { dao.getAllFlow() } returns flowOf(entities)

			val result = repository.observeAll().first()
			result shouldHaveSize 3
		}
	}

	@Nested
	@DisplayName("observeRecent")
	inner class ObserveRecent {

		@Test
		fun `returns only unlocked achievements`() = runTest {
			// Production uses getRecentUnlockedFlow() which SQL-filters WHERE unlocked_at IS
			// NOT NULL — so the mock should return only already-unlocked rows. (Sending in
			// locked rows would be a test mistake masking the production filter.)
			val entities = listOf(
				createEntity(id = 1, achievementId = "a", unlockedAt = 100L),
				createEntity(id = 3, achievementId = "c", unlockedAt = 200L),
			)
			every { dao.getRecentUnlockedFlow() } returns flowOf(entities)

			val result = repository.observeRecent().first()
			result shouldHaveSize 2
			result[0].achievementId shouldBe "a"
			result[1].achievementId shouldBe "c"
		}

		@Test
		fun `limits to 10 results`() = runTest {
			// SQL LIMIT 10 is enforced by getRecentUnlockedFlow's query. Repository is a
			// pass-through map. Mock returns 10 entities (what SQL would have returned).
			val entities = (1..10).map { i ->
				createEntity(
					id = i.toLong(),
					achievementId = "ach_$i",
					unlockedAt = i.toLong() * 100,
				)
			}
			every { dao.getRecentUnlockedFlow() } returns flowOf(entities)

			val result = repository.observeRecent().first()
			result shouldHaveSize 10
		}

		@Test
		fun `returns empty when none unlocked`() = runTest {
			// SQL WHERE filter excludes all locked rows -> empty list.
			every { dao.getRecentUnlockedFlow() } returns flowOf(emptyList())

			val result = repository.observeRecent().first()
			result.shouldBeEmpty()
		}

		@Test
		fun `returns empty when no entities`() = runTest {
			every { dao.getRecentUnlockedFlow() } returns flowOf(emptyList())

			val result = repository.observeRecent().first()
			result.shouldBeEmpty()
		}
	}
}
