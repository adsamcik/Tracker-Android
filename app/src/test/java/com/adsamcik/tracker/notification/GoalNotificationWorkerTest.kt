package com.adsamcik.tracker.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.robolectric.annotation.Config

@Config(sdk = [34])
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
	fun `materializing and storage unavailable Steps request a retry`() {
		listOf(
			QualifiedStepCountUnavailableReason.MATERIALIZING,
			QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
		).forEach { reason ->
			val preferences = mockk<Preferences>(relaxed = true)
			val worker = worker(
				steps = QualifiedStepCount.Unavailable(reason),
				preferences = preferences,
			)

			runBlocking { worker.doWork() } shouldBe ListenableWorker.Result.retry()
			coVerify(exactly = 0) { preferences.fetchLong(any(), any()) }
			coVerify(exactly = 0) { preferences.fetchInt(any(), any()) }
		}
	}

	@Test
	fun `terminal nonnumeric Steps states finish without reading notification claims`() {
		listOf(
			QualifiedStepCountUnavailableReason.NOT_CAPTURED,
			QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
			QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			QualifiedStepCountUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
		).forEach { reason ->
			val preferences = mockk<Preferences>(relaxed = true)
			val worker = worker(
				steps = QualifiedStepCount.Unavailable(reason),
				preferences = preferences,
			)

			runBlocking { worker.doWork() } shouldBe ListenableWorker.Result.success()
			coVerify(exactly = 0) { preferences.fetchLong(any(), any()) }
			coVerify(exactly = 0) { preferences.fetchInt(any(), any()) }
		}
	}

	@Test
	fun `ready zero and below-threshold positive Steps finish without a claim`() {
		listOf(0, 7_499).forEach { count ->
			val preferences = mockk<Preferences>(relaxed = true)
			val worker = worker(
				steps = QualifiedStepCount.Ready(count),
				preferences = preferences,
			)

			runBlocking { worker.doWork() } shouldBe ListenableWorker.Result.success()
			coVerify(exactly = 0) { preferences.fetchLong(any(), any()) }
			coVerify(exactly = 0) { preferences.fetchInt(any(), any()) }
		}
	}

	@Test
	fun `denied notification permission does not consume the threshold claim`() {
		val context = mockk<Context>(relaxed = true)
		every { context.applicationContext } returns context
		every {
			context.checkPermission(
				Manifest.permission.POST_NOTIFICATIONS,
				any(),
				any(),
			)
		} returns PackageManager.PERMISSION_DENIED
		val preferences = mockk<Preferences>(relaxed = true)
		val worker = worker(
			steps = QualifiedStepCount.Ready(8_000),
			preferences = preferences,
			context = context,
		)

		runBlocking { worker.doWork() } shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 0) { preferences.editSuspend(any()) }
	}

	@Test
	fun `missing replay sentinel times out and worker finishes without notification`() = runTest {
		val preferences = mockk<Preferences>(relaxed = true)
		val worker = GoalNotificationWorker(
			appContext = mockk<Context>(relaxed = true),
			workerParams = mockk<WorkerParameters>(relaxed = true),
			goalProgressProvider = FakeGoalProgressProvider(
				GoalProgress(
					stepsToday = QualifiedStepCount.Unavailable(
						QualifiedStepCountUnavailableReason.MISSING,
					),
					goalSteps = 10_000,
					gamificationEnabled = true,
				),
			),
			preferences = preferences,
			goalsSettingsRepository = FakeWorkerGoalsSettingsRepository(notificationsEnabled = true),
		)

		worker.doWork() shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 0) { preferences.fetchLong(any(), any()) }
		coVerify(exactly = 0) { preferences.fetchInt(any(), any()) }
	}

	private fun worker(
		steps: QualifiedStepCount,
		preferences: Preferences,
		context: Context = mockk(relaxed = true),
	) = GoalNotificationWorker(
		appContext = context,
		workerParams = mockk<WorkerParameters>(relaxed = true),
		goalProgressProvider = FakeGoalProgressProvider(
			GoalProgress(
				stepsToday = steps,
				goalSteps = 10_000,
				gamificationEnabled = true,
			),
		),
		preferences = preferences,
		goalsSettingsRepository = FakeWorkerGoalsSettingsRepository(notificationsEnabled = true),
	)
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
