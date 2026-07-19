package com.adsamcik.tracker.game.goals.settings

import androidx.datastore.core.DataStore
import com.adsamcik.tracker.game.goals.data.GoalsSettingsGoalPersistence
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGoalsSettingsRepositoryTest {
	private val dispatcher = StandardTestDispatcher()

	@Test
	fun `fresh proto emits product defaults and migrates once`() = runTest(dispatcher) {
		val legacy = FakeLegacyGoalsSettingsSource()
		val store = FakeDataStore()
		val repository = DefaultGoalsSettingsRepository(store, dispatcher, legacy)

		repository.data.first() shouldBe GoalsSettingsState.defaults()
		repository.data.first() shouldBe GoalsSettingsState.defaults()

		legacy.readCount shouldBe 1
		store.current.legacyMigrated shouldBe true
	}

	@Test
	fun `legacy goals and reached markers migrate into proto`() = runTest(dispatcher) {
		val legacy = FakeLegacyGoalsSettingsSource(
			LegacyGoalsSettings(
				notificationsEnabled = false,
				dailyStepGoal = 8_000,
				weeklyStepGoal = 55_000,
				weeklyProgressDailyLimit = 0.5f,
				dailyGoalReachedPeriod = 2_026_199,
				weeklyGoalReachedPeriod = 202_629,
			),
		)
		val store = FakeDataStore()
		val repository = DefaultGoalsSettingsRepository(store, dispatcher, legacy)

		val state = repository.data.first()

		state.notificationsEnabled shouldBe false
		state.dailyStepGoal shouldBe 8_000
		state.weeklyStepGoal shouldBe 55_000
		state.weeklyProgressDailyLimit shouldBe 0.5f
		state.dailyGoalReachedPeriod shouldBe 2_026_199
		state.weeklyGoalReachedPeriod shouldBe 202_629
		store.current.hasNotificationsEnabled() shouldBe true
		store.current.hasDailyGoalReachedPeriod() shouldBe true
		store.current.hasWeeklyGoalReachedPeriod() shouldBe true
	}

	@Test
	fun `presence aware false values override enabled defaults`() = runTest(dispatcher) {
		val store = FakeDataStore(
			GoalsSettingsProto.newBuilder()
				.setLegacyMigrated(true)
				.setNotificationsEnabled(false)
				.setGameHapticsEnabled(false)
				.setQuietCoachingEnabled(false)
				.setRememberLastSetup(false)
				.build(),
		)
		val repository = DefaultGoalsSettingsRepository(store, dispatcher, FakeLegacyGoalsSettingsSource())

		val state = repository.data.first()

		state.notificationsEnabled shouldBe false
		state.gameHapticsEnabled shouldBe false
		state.quietCoachingEnabled shouldBe false
		state.rememberLastSetup shouldBe false
	}

	@Test
	fun `setters mutate and coerce settings`() = runTest(dispatcher) {
		val repository = DefaultGoalsSettingsRepository(
			FakeDataStore(),
			dispatcher,
			FakeLegacyGoalsSettingsSource(),
		)

		repository.setNotificationsEnabled(false)
		repository.setDailyStepGoal(0)
		repository.setWeeklyStepGoal(-10)
		repository.setWeeklyDailyLimit(2f)
		repository.setGameHapticsEnabled(false)
		repository.setQuietCoachingEnabled(false)

		repository.data.first().let { state ->
			state.notificationsEnabled shouldBe false
			state.dailyStepGoal shouldBe 1
			state.weeklyStepGoal shouldBe 1
			state.weeklyProgressDailyLimit shouldBe 1f
			state.gameHapticsEnabled shouldBe false
			state.quietCoachingEnabled shouldBe false
		}
	}

	@Test
	fun `remembered setup values are coerced to supported configurations`() = runTest(dispatcher) {
		val repository = DefaultGoalsSettingsRepository(
			FakeDataStore(),
			dispatcher,
			FakeLegacyGoalsSettingsSource(),
		)

		repository.setRememberLastSetup(true)
		repository.setRememberedOutrunSetup(goalMeters = 999, difficulty = "IMPOSSIBLE")
		repository.setRememberedTerritorySetup(goalCells = 20, difficulty = "HARD")
		repository.setRememberedZenSetup(goalMinutes = 5, difficulty = "EASY")
		repository.setRememberedFuseRunSetup(goalCharges = 999, difficulty = "IMPOSSIBLE")
		repository.setRememberedSwitchbackSetup(goalTurns = 4, difficulty = "EASY")

		repository.data.first().let { state ->
			state.rememberedOutrunSetup shouldBe RememberedGameSetup(50, "NORMAL")
			state.rememberedTerritorySetup shouldBe RememberedGameSetup(20, null)
			state.rememberedZenSetup shouldBe RememberedGameSetup(5, "EASY")
			state.rememberedFuseRunSetup shouldBe RememberedGameSetup(5, "NORMAL")
			state.rememberedSwitchbackSetup shouldBe RememberedGameSetup(4, "EASY")
		}
	}

	@Test
	fun `disabling remember clears every remembered value`() = runTest(dispatcher) {
		val store = FakeDataStore()
		val repository = DefaultGoalsSettingsRepository(store, dispatcher, FakeLegacyGoalsSettingsSource())
		repository.setRememberLastSetup(true)
		repository.setRememberedOutrunSetup(100, "HARD")
		repository.setRememberedTerritorySetup(20, null)
		repository.setRememberedZenSetup(20, "EASY")
		repository.setRememberedFuseRunSetup(8, "HARD")
		repository.setRememberedSwitchbackSetup(10, "NORMAL")

		repository.setRememberLastSetup(false)

		repository.data.first().let { state ->
			state.rememberLastSetup shouldBe false
			state.rememberedOutrunSetup shouldBe null
			state.rememberedTerritorySetup shouldBe null
			state.rememberedZenSetup shouldBe null
			state.rememberedFuseRunSetup shouldBe null
			state.rememberedSwitchbackSetup shouldBe null
		}
		store.current.hasRememberedOutrunGoal() shouldBe false
		store.current.hasRememberedOutrunDifficulty() shouldBe false
		store.current.hasRememberedTerritoryGoal() shouldBe false
		store.current.hasRememberedTerritoryDifficulty() shouldBe false
		store.current.hasRememberedZenGoal() shouldBe false
		store.current.hasRememberedZenDifficulty() shouldBe false
		store.current.hasRememberedFuseRunGoal() shouldBe false
		store.current.hasRememberedFuseRunDifficulty() shouldBe false
		store.current.hasRememberedSwitchbackGoal() shouldBe false
		store.current.hasRememberedSwitchbackDifficulty() shouldBe false
	}

	@Test
	fun `goal reached persistence never requires legacy writes`() = runTest(dispatcher) {
		val legacy = FakeLegacyGoalsSettingsSource()
		val repository = DefaultGoalsSettingsRepository(FakeDataStore(), dispatcher, legacy)
		val persistence = GoalsSettingsGoalPersistence(repository)

		persistence.persist("goalDayReached", 2_026_199)
		persistence.persist("goalWeekReached", 202_629)

		persistence.load("goalDayReached") shouldBe 2_026_199
		persistence.load("goalWeekReached") shouldBe 202_629
		legacy.readCount shouldBe 1
	}

	private class FakeDataStore(
		initial: GoalsSettingsProto = GoalsSettingsProto.getDefaultInstance(),
	) : DataStore<GoalsSettingsProto> {
		private val mutex = Mutex()
		private val state = MutableStateFlow(initial)
		override val data: Flow<GoalsSettingsProto> = state
		val current: GoalsSettingsProto get() = state.value

		override suspend fun updateData(
			transform: suspend (t: GoalsSettingsProto) -> GoalsSettingsProto,
		): GoalsSettingsProto = mutex.withLock {
			transform(state.value).also { state.value = it }
		}
	}

	private class FakeLegacyGoalsSettingsSource(
		private val settings: LegacyGoalsSettings = LegacyGoalsSettings.defaults(),
	) : LegacyGoalsSettingsSource {
		var readCount: Int = 0
			private set

		override suspend fun read(): LegacyGoalsSettings {
			readCount++
			return settings
		}
	}
}
