package com.adsamcik.tracker.game.goals

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.StepsGoalEffectWriteResult
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsGoalNotificationDispatcherTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var notificationManager: NotificationManager

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		notificationManager = context.getSystemService(NotificationManager::class.java)
		notificationManager.cancelAll()
		notificationManager.createNotificationChannel(
			NotificationChannel(
				context.getString(com.adsamcik.tracker.shared.base.R.string.channel_goals_id),
				"Goals",
				NotificationManager.IMPORTANCE_DEFAULT,
			),
		)
	}

	@After
	fun tearDown() {
		notificationManager.cancelAll()
		database.close()
	}

	@Test
	fun `disabled notification is durably suppressed and cannot appear after enabling`() = runTest {
		database.stepsGoalEffectDao().recordDecision(effect(complete = true)) shouldBe
			StepsGoalEffectWriteResult.INSERTED
		val dispatcher = dispatcher(ReadyGate, testScheduler)

		dispatcher.dispatch(IDENTITY, 1L, notificationsEnabled = false, claimedAtMs = 150L) shouldBe
			StepsGoalNotificationDispatchResult.CLAIMED_SUPPRESSED_DISABLED
		database.stepsGoalEffectDao().get(IDENTITY)?.notificationClaimedRevision shouldBe 1L
		shadowOf(notificationManager).allNotifications.size shouldBe 0

		dispatcher.dispatch(IDENTITY, 1L, notificationsEnabled = true, claimedAtMs = 200L) shouldBe
			StepsGoalNotificationDispatchResult.ALREADY_CLAIMED
		shadowOf(notificationManager).allNotifications.size shouldBe 0
	}

	@Test
	fun `qualified completion makes one platform delivery attempt`() = runTest {
		database.stepsGoalEffectDao().recordDecision(effect(complete = true)) shouldBe
			StepsGoalEffectWriteResult.INSERTED
		val dispatcher = dispatcher(ReadyGate, testScheduler)

		dispatcher.dispatch(IDENTITY, 1L, notificationsEnabled = true, claimedAtMs = 150L) shouldBe
			StepsGoalNotificationDispatchResult.CLAIMED_DELIVERY_ATTEMPTED
		dispatcher.dispatch(IDENTITY, 1L, notificationsEnabled = true, claimedAtMs = 151L) shouldBe
			StepsGoalNotificationDispatchResult.ALREADY_CLAIMED
		shadowOf(notificationManager).allNotifications.size shouldBe 1
	}

	@Test
	fun `incomplete or closed effects never claim a notification`() = runTest {
		database.stepsGoalEffectDao().recordDecision(effect(complete = false)) shouldBe
			StepsGoalEffectWriteResult.INSERTED

		dispatcher(ReadyGate, testScheduler).dispatch(
			IDENTITY,
			1L,
			notificationsEnabled = true,
			claimedAtMs = 150L,
		) shouldBe StepsGoalNotificationDispatchResult.NOT_ELIGIBLE
		database.stepsGoalEffectDao().get(IDENTITY)?.notificationClaimedRevision shouldBe null

		dispatcher(ClosedGate, testScheduler).dispatch(
			IDENTITY,
			1L,
			notificationsEnabled = true,
			claimedAtMs = 151L,
		) shouldBe StepsGoalNotificationDispatchResult.RETRYABLE_GENERATION_CHANGED
		database.stepsGoalEffectDao().get(IDENTITY)?.notificationClaimedRevision shouldBe null
		shadowOf(notificationManager).allNotifications.size shouldBe 0
	}

	private fun dispatcher(
		gate: TrackingStartupGate,
		testScheduler: TestCoroutineScheduler,
	) = StepsGoalNotificationDispatcher(
		context = context,
		database = database,
		dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		startupGate = gate,
	)

	private fun effect(complete: Boolean) = StepsGoalEffectEntity(
		effectIdentity = IDENTITY,
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = EPOCH_DAY,
		periodEndEpochDay = EPOCH_DAY,
		qualifiedThroughEpochDay = EPOCH_DAY,
		calendarAuthority = "$EPOCH_DAY=Europe/Prague",
		targetSteps = 4_000L,
		weeklyDailyLimitBits = null,
		decisionState = if (complete) {
			StepsGoalEffectEntity.STATE_READY_COMPLETE
		} else {
			StepsGoalEffectEntity.STATE_READY_INCOMPLETE
		},
		unavailableReason = null,
		qualifiedSteps = if (complete) 4_500L else 3_500L,
		sourceAuthorityDigest = "a".repeat(64),
		sourceEvidenceRevision = 1L,
		effectRevision = 1L,
		desiredPointsMicros = if (complete) 40_000_000L else 0L,
		desiredXp = if (complete) 50 else 0,
		firstCompletedAtMs = 100L.takeIf { complete },
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = 100L,
	)

	private data object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override val currentGeneration = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(false, 0L)
	}

	private data object ClosedGate : TrackingStartupGate {
		override val isReady = false
		override val currentGeneration = 2L
		override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Blocked(
			TrackingStartupStage.STORAGE,
			"TEST_CLOSED",
		)
	}

	private companion object {
		const val EPOCH_DAY = 20_000L
		val IDENTITY: String = StepsGoalEffectEntity.identity(
			StepsGoalEffectEntity.PERIOD_DAY,
			EPOCH_DAY,
		)
	}
}
