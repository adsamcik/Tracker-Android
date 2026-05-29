package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.PlayerProfileDao
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("UnlockRepository")
class UnlockRepositoryTest {

	private lateinit var unlockRepository: UnlockRepository
	private lateinit var database: AppDatabase
	private lateinit var profileDao: PlayerProfileDao

	@BeforeEach
	fun setup() {
		unlockRepository = UnlockRepository()
		database = mockk(relaxed = true)
		profileDao = mockk(relaxed = true)
		every { database.playerProfileDao() } returns profileDao
	}

	@AfterEach
	fun teardown() {
		unmockkAll()
	}

	@Nested
	@DisplayName("isUnlockedSync")
	inner class IsUnlockedSync {
		@Test
		fun `level 1 unlocks base features`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 1)
			unlockRepository.isUnlockedSync(database, UnlockableFeature.BASE_CHALLENGES) shouldBe true
		}

		@Test
		fun `level 1 does not unlock level 2 feature`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 1)
			unlockRepository.isUnlockedSync(database, UnlockableFeature.PERSONAL_RECORDS) shouldBe false
		}

		@Test
		fun `level 10 unlocks everything`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 10)
			UnlockableFeature.entries.forEach { feature ->
				unlockRepository.isUnlockedSync(database, feature) shouldBe true
			}
		}

		@Test
		fun `null profile defaults to level 1`() = runTest {
			coEvery { profileDao.get() } returns null
			unlockRepository.isUnlockedSync(database, UnlockableFeature.BASE_CHALLENGES) shouldBe true
			unlockRepository.isUnlockedSync(database, UnlockableFeature.PERSONAL_RECORDS) shouldBe false
		}
	}

	@Nested
	@DisplayName("getUnlockedFeatures")
	inner class GetUnlockedFeatures {
		@Test
		fun `level 1 unlocks exactly 4 base features`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 1)
			val unlocked = unlockRepository.getUnlockedFeatures(database)
			unlocked shouldHaveSize 4
			unlocked shouldContain UnlockableFeature.BASE_CHALLENGES
			unlocked shouldContain UnlockableFeature.MEDALS
			unlocked shouldContain UnlockableFeature.STREAKS
			unlocked shouldContain UnlockableFeature.TROPHY_CASE
		}

		@Test
		fun `level 5 includes level 2 through 5 features`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 5)
			val unlocked = unlockRepository.getUnlockedFeatures(database)
			unlocked shouldContain UnlockableFeature.PERSONAL_RECORDS
			unlocked shouldContain UnlockableFeature.OUTRUN_MINI_GAME
			unlocked shouldContain UnlockableFeature.STREAK_FREEZE
			unlocked shouldContain UnlockableFeature.SPEED_CHALLENGE
		}
	}

	@Nested
	@DisplayName("getNewUnlocksAtLevel")
	inner class GetNewUnlocksAtLevel {
		@Test
		fun `level 2 returns only PERSONAL_RECORDS`() {
			val unlocks = unlockRepository.getNewUnlocksAtLevel(2)
			unlocks shouldHaveSize 1
			unlocks[0] shouldBe UnlockableFeature.PERSONAL_RECORDS
		}

		@Test
		fun `level 1 returns base features`() {
			val unlocks = unlockRepository.getNewUnlocksAtLevel(1)
			unlocks shouldHaveSize 4
		}

		@Test
		fun `non-existent level returns empty`() {
			val unlocks = unlockRepository.getNewUnlocksAtLevel(99)
			unlocks shouldHaveSize 0
		}
	}

	@Nested
	@DisplayName("getNextUnlock")
	inner class GetNextUnlock {
		@Test
		fun `returns next unlock at level 1`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 1)
			val result = unlockRepository.getNextUnlock(database)
			result.shouldNotBeNull()
			result.first shouldBe UnlockableFeature.PERSONAL_RECORDS
			result.second shouldBe 2
		}

		@Test
		fun `returns null when all unlocked`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 10)
			unlockRepository.getNextUnlock(database).shouldBeNull()
		}

		@Test
		fun `returns null when above max level`() = runTest {
			coEvery { profileDao.get() } returns PlayerProfileEntity(level = 50)
			unlockRepository.getNextUnlock(database).shouldBeNull()
		}
	}
}
