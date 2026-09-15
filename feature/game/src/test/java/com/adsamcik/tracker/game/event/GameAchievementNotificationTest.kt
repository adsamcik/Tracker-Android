package com.adsamcik.tracker.game.event

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.data.repository.DefaultAchievementRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameAchievementNotificationTest {
	private lateinit var context: Context
	private lateinit var notificationManager: NotificationManager
	private lateinit var progressDao: AchievementProgressDao
	private lateinit var events: RecordingEventRepository
	private lateinit var gate: TestTrackingStartupGate
	private lateinit var consumer: GameDomainEventConsumer

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		notificationManager = context.getSystemService(NotificationManager::class.java)
		notificationManager.cancelAll()
		notificationManager.createNotificationChannel(
			NotificationChannel(
				context.getString(com.adsamcik.tracker.shared.base.R.string.channel_achievements_id),
				"Achievements",
				NotificationManager.IMPORTANCE_DEFAULT,
			),
		)
		progressDao = mockk()
		coEvery { progressDao.getAll() } returns emptyList()
		coEvery { progressDao.getQualifiedStepsAchievementRows() } returns emptyList()
		events = RecordingEventRepository()
		gate = TestTrackingStartupGate()
		consumer = GameDomainEventConsumer(
			domainEventRepository = events,
			achievementEvaluationScheduler = mockk(relaxed = true),
			progressionRepository = mockk(),
			trackingStartupGate = gate,
			achievementRepository = DefaultAchievementRepository(progressDao),
			achievementProgressDao = progressDao,
			context = context,
		)
	}

	@Test
	fun `queued Steps XP and meta unlocks stay suppressed and are acknowledged`() = runTest {
		val metrics = listOf(
			MetricKey.STEPS_TOTAL,
			MetricKey.BEST_DAILY_STEPS,
			MetricKey.PLAYER_LEVEL,
			MetricKey.BEST_DAY_XP,
			MetricKey.ACHIEVEMENTS_UNLOCKED,
			MetricKey.CATEGORIES_COMPLETED,
		)
		coEvery { progressDao.getAll() } returns metrics.map {
			AchievementProgressEntity(it.storageKey, 99, 1_000_000.0, 1_000L)
		}
		events.persist(metrics.map { unlock(definition(it)) })
		events.persist(listOf(unlock(definition(MetricKey.DISTANCE_TOTAL_M)).copy(achievementId = "unknown")))

		consumer.processUnconsumed()

		shadowOf(notificationManager).allNotifications.size shouldBe 0
		events.acknowledgedIds shouldBe (1L..7L).toList()
		coVerify(exactly = 1) { progressDao.getAll() }
	}

	@Test
	fun `trusted first unlock is notified before progress persistence and wrong tier is suppressed`() = runTest {
		val trusted = unlock(definition(MetricKey.DISTANCE_TOTAL_M))
		events.persist(listOf(trusted.copy(tier = "not-a-tier"), trusted))

		consumer.processUnconsumed()

		shadowOf(notificationManager).allNotifications.size shouldBe 1
		events.acknowledgedIds shouldBe listOf(1L, 2L)
		coVerify(exactly = 1) { progressDao.getAll() }
	}

	@Test
	fun `retained Steps unlocks require exact current revision digest ready state and claimed tier`() = runTest {
		val metrics = listOf(MetricKey.STEPS_TOTAL, MetricKey.BEST_DAILY_STEPS)
		val definitions = metrics.map(::definition)
		val currentRevision = 3L
		val currentDigest = "c".repeat(64)

		setQualifiedRows(
			metrics.map { metric ->
				qualifiedProgress(metric, authorityRevision = currentRevision, authorityDigest = currentDigest)
			},
		)
		persistQualifiedUnlocks(definitions, authorityRevision = 2L, authorityDigest = currentDigest)
		consumer.processUnconsumed()
		shadowOf(notificationManager).allNotifications.size shouldBe 0

		persistQualifiedUnlocks(
			definitions,
			authorityRevision = currentRevision,
			authorityDigest = "d".repeat(64),
		)
		consumer.processUnconsumed()
		shadowOf(notificationManager).allNotifications.size shouldBe 0

		setQualifiedRows(
			listOf(
				qualifiedProgress(
					MetricKey.STEPS_TOTAL,
					authorityRevision = currentRevision,
					authorityDigest = currentDigest,
					authorityState = AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING,
				),
				qualifiedProgress(
					MetricKey.BEST_DAILY_STEPS,
					authorityRevision = currentRevision,
					authorityDigest = currentDigest,
					authorityState = AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE,
				),
			),
		)
		persistQualifiedUnlocks(definitions, currentRevision, currentDigest)
		consumer.processUnconsumed()
		shadowOf(notificationManager).allNotifications.size shouldBe 0

		setQualifiedRows(
			metrics.map { metric ->
				qualifiedProgress(
					metric,
					authorityRevision = currentRevision,
					authorityDigest = currentDigest,
					claimedTierIndex = null,
				)
			},
		)
		persistQualifiedUnlocks(definitions, currentRevision, currentDigest)
		consumer.processUnconsumed()
		shadowOf(notificationManager).allNotifications.size shouldBe 0

		setQualifiedRows(
			metrics.map { metric ->
				qualifiedProgress(metric, authorityRevision = currentRevision, authorityDigest = currentDigest)
			},
		)
		persistQualifiedUnlocks(definitions, currentRevision, currentDigest)
		consumer.processUnconsumed()

		shadowOf(notificationManager).allNotifications.size shouldBe 2
		events.acknowledgedIds shouldBe (1L..10L).toList()
	}

	@Test
	fun `qualified Steps goal unlocks remain authorized by exact current authority`() = runTest {
		val metrics = listOf(MetricKey.GOAL_STREAK_DAYS, MetricKey.PERFECT_WEEKS)
		val revision = 5L
		val digest = "e".repeat(64)
		setQualifiedRows(
			metrics.map { metric ->
				qualifiedProgress(metric, authorityRevision = revision, authorityDigest = digest)
			},
		)

		persistQualifiedUnlocks(metrics.map(::definition), revision, digest)
		consumer.processUnconsumed()

		shadowOf(notificationManager).allNotifications.size shouldBe 2
		events.acknowledgedIds shouldBe listOf(1L, 2L)
	}

	@Test
	fun `qualification read failure retries without notification or acknowledgement`() = runTest {
		events.persist(listOf(unlock(definition(MetricKey.DISTANCE_TOTAL_M))))
		coEvery { progressDao.getAll() } throws IllegalStateException("unavailable")

		consumer.processUnconsumed()

		shadowOf(notificationManager).allNotifications.size shouldBe 0
		events.acknowledgedIds shouldBe emptyList()
		coEvery { progressDao.getAll() } returns emptyList()
		consumer.processUnconsumed()
		shadowOf(notificationManager).allNotifications.size shouldBe 1
		events.acknowledgedIds shouldBe listOf(1L)
	}

	@Test
	fun `qualification cancellation propagates without acknowledgement`() = runTest {
		events.persist(listOf(unlock(definition(MetricKey.DISTANCE_TOTAL_M))))
		coEvery { progressDao.getAll() } throws CancellationException("cancelled")

		val failure = runCatching { consumer.processUnconsumed() }.exceptionOrNull()

		(failure is CancellationException) shouldBe true
		shadowOf(notificationManager).allNotifications.size shouldBe 0
		events.acknowledgedIds shouldBe emptyList()
	}

	@Test
	fun `deletion waits for notification lease and prevents stale acknowledgement`() = runTest {
		events.persist(listOf(unlock(definition(MetricKey.DISTANCE_TOTAL_M))))
		gate.afterSecondOperation = {
			gate.retireAndReopen()
			events.deleteAll()
		}

		consumer.processUnconsumed()

		shadowOf(notificationManager).allNotifications.size shouldBe 1
		events.acknowledgedIds shouldBe emptyList()
		gate.operationGenerations shouldBe listOf(1L, 1L, 1L)
	}

	private fun definition(metric: MetricKey): AchievementDefinition =
		AchievementCatalog.definitions.first { it.metric == metric }

	private fun unlock(definition: AchievementDefinition) = DomainEvent.AchievementUnlocked(
		timestampMs = EpochMs(1_000L),
		processorId = "test",
		achievementId = definition.id,
		tier = definition.tier.name,
	)

	private fun setQualifiedRows(rows: List<AchievementProgressEntity>) {
		coEvery { progressDao.getAll() } returns rows
		coEvery { progressDao.getQualifiedStepsAchievementRows() } returns rows
	}

	private suspend fun persistQualifiedUnlocks(
		definitions: List<AchievementDefinition>,
		authorityRevision: Long,
		authorityDigest: String,
	) {
		events.persist(
			definitions.map { definition ->
				unlock(definition).copy(
					processorId = "generic-or-stale-producer",
					authorityRevision = authorityRevision,
					authorityDigest = authorityDigest,
				)
			},
		)
	}

	private fun qualifiedProgress(
		metric: MetricKey,
		authorityRevision: Long,
		authorityDigest: String,
		authorityState: String = AchievementProgressEntity.AUTHORITY_STATE_READY,
		claimedTierIndex: Int? = 0,
		lastTierIndex: Int = 0,
	) = AchievementProgressEntity(
		metricKey = metric.storageKey,
		lastTierIndex = lastTierIndex,
		lastValue = if (lastTierIndex >= 0) 3.0 else 0.0,
		updatedAt = 1_000L,
		authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
		authorityRevision = authorityRevision,
		authorityDigest = authorityDigest,
		authorityState = authorityState,
		qualifiedNotificationClaimedTierIndex = claimedTierIndex,
	)

	private class RecordingEventRepository : DomainEventRepository {
		private val rows = mutableListOf<UnconsumedEvent>()
		val acknowledgedIds = mutableListOf<Long>()
		private var cursor = 0L

		override suspend fun persist(events: List<DomainEvent>) {
			events.forEach { rows += UnconsumedEvent(it, rows.size.toLong() + 1L) }
		}

		override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> = emptyFlow()

		override suspend fun getUnconsumedBatchWithIds(consumerId: String, limit: Int): List<UnconsumedEvent> =
			rows.filter { it.persistedId > cursor }.take(limit)

		override suspend fun markBatchConsumed(consumerId: String, upToTimestamp: EpochMs, upToEventId: Long) {
			acknowledgedIds += upToEventId
			cursor = upToEventId
		}

		fun deleteAll() {
			rows.clear()
			acknowledgedIds.clear()
			cursor = 0L
		}
	}

	private class TestTrackingStartupGate : TrackingStartupGate {
		private var generation = 1L
		var afterSecondOperation: (() -> Unit)? = null
		val operationGenerations = mutableListOf<Long>()
		override val isReady: Boolean = true
		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? {
			operationGenerations += expectedGeneration
			val result = if (isReadyGeneration(expectedGeneration)) {
				operation()
			} else {
				null
			}
			if (operationGenerations.size == 2) {
				afterSecondOperation?.invoke()
			}
			return result
		}

		fun retireAndReopen() {
			generation += 1L
		}
	}
}
