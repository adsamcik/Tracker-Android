package com.adsamcik.tracker.notification

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.shared.preferences.Preferences
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class GoalNotificationWorkerTest {

	@Test
	fun `only progress thresholds produce notifications`() {
		GoalNotificationWorker.progressNotificationThreshold(0.74f) shouldBe null
		GoalNotificationWorker.progressNotificationThreshold(0.75f) shouldBe GoalNotificationWorker.THRESHOLD_75
		GoalNotificationWorker.progressNotificationThreshold(0.90f) shouldBe GoalNotificationWorker.THRESHOLD_90
	}

	@Test
	fun `completion is left to GoalTracker`() {
		GoalNotificationWorker.progressNotificationThreshold(1f) shouldBe null
	}

	@Test
	fun `disabled repository setting skips worker without changing progress`() {
		val goalProgressProvider = FakeGoalProgressProvider(
			GoalProgress(
				stepsToday = QualifiedStepCount.Ready(8_000),
				goalSteps = 10_000,
				gamificationEnabled = true,
			),
		)
		val worker = GoalNotificationWorker(
			appContext = mockk<Context>(relaxed = true),
			workerParams = mockk<WorkerParameters>(relaxed = true),
			goalProgressProvider = goalProgressProvider,
			preferences = mockk<Preferences>(relaxed = true),
			goalsSettingsRepository = FakeWorkerGoalsSettingsRepository(notificationsEnabled = false),
		)

		runBlocking { worker.doWork() } shouldBe ListenableWorker.Result.success()
		goalProgressProvider.goalProgressFlow.value.stepsToday shouldBe QualifiedStepCount.Ready(8_000)
	}

	@Test
	fun `unavailable qualified Steps cannot produce a notification threshold`() {
		val preferences = mockk<Preferences>(relaxed = true)
		val worker = GoalNotificationWorker(
			appContext = mockk<Context>(relaxed = true),
			workerParams = mockk<WorkerParameters>(relaxed = true),
			goalProgressProvider = FakeGoalProgressProvider(
				GoalProgress(
					stepsToday = QualifiedStepCount.Unavailable(
						QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
					),
					goalSteps = 10_000,
					gamificationEnabled = true,
				),
			),
			preferences = preferences,
			goalsSettingsRepository = FakeWorkerGoalsSettingsRepository(notificationsEnabled = true),
		)

		runBlocking { worker.doWork() } shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 0) { preferences.fetchLong(any(), any()) }
		coVerify(exactly = 0) { preferences.fetchInt(any(), any()) }
	}
}

private class FakeGoalProgressProvider(
	initial: GoalProgress,
) : GoalProgressProvider {
	override val goalProgressFlow = MutableStateFlow(initial)
}

private class FakeWorkerGoalsSettingsRepository(
	notificationsEnabled: Boolean,
) : GoalsSettingsRepository {
	private val state = MutableStateFlow(
		GoalsSettingsState.defaults().copy(notificationsEnabled = notificationsEnabled),
	)
	override val data: Flow<GoalsSettingsState> = state

	override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
	override suspend fun setDailyStepGoal(steps: Int) = Unit
	override suspend fun setWeeklyStepGoal(steps: Int) = Unit
	override suspend fun setWeeklyDailyLimit(fraction: Float) = Unit
	override suspend fun setGameHapticsEnabled(enabled: Boolean) = Unit
	override suspend fun setQuietCoachingEnabled(enabled: Boolean) = Unit
	override suspend fun setRememberLastSetup(enabled: Boolean) = Unit
	override suspend fun setRememberedOutrunSetup(goalMeters: Int, difficulty: String?) = Unit
	override suspend fun setRememberedTerritorySetup(goalCells: Int, difficulty: String?) = Unit
	override suspend fun setRememberedZenSetup(goalMinutes: Int, difficulty: String?) = Unit
	override suspend fun setRememberedFuseRunSetup(goalCharges: Int, difficulty: String?) = Unit
	override suspend fun setRememberedSwitchbackSetup(goalTurns: Int, difficulty: String?) = Unit
	override suspend fun setDailyGoalReachedPeriod(period: Int?) = Unit
	override suspend fun setWeeklyGoalReachedPeriod(period: Int?) = Unit
}
