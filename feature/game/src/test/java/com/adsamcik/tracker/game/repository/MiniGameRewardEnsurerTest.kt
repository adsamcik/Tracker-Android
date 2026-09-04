package com.adsamcik.tracker.game.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class MiniGameRewardEnsurerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `points insertion uses exact per game source and is idempotent`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val dao = RecordingPointsDao()
		val gate = SerializedTestTrackingStartupGate()
		val progressionAttempts = mutableListOf<Pair<Int, Long>>()
		val ensurer = MiniGameRewardEnsurer(
			pointsDao = dao,
			dispatchers = TestDispatchersProvider(dispatcher),
			trackingStartupGate = gate,
			awardProgression = { points, time, _ ->
				progressionAttempts += points to time
				true
			},
			markMiniGameMetricsDirty = {},
			scheduleAchievementEvaluation = {},
		)
		val reward = GameReward(
			rewardId = miniGameRewardId("outrun", 123L),
			gameId = "outrun",
			points = 40,
			earnedAtMs = 123L,
		)

		ensurer.ensure(reward) shouldBe GameRewardEnsureResult.Created
		ensurer.ensure(reward) shouldBe GameRewardEnsureResult.AlreadyEnsured

		dao.inserted.shouldHaveSize(1)
		dao.inserted.single().source.value shouldBe "minigame:outrun"
		dao.inserted.single().time shouldBe 123L
		progressionAttempts shouldBe listOf(40 to 123L, 40 to 123L)
	}

	@Test
	fun `existing points row still retries idempotent progression repair`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val dao = RecordingPointsDao().apply { existing += 456L to "minigame:territory" }
		val gate = SerializedTestTrackingStartupGate()
		var progressionAttempts = 0
		val ensurer = MiniGameRewardEnsurer(
			pointsDao = dao,
			dispatchers = TestDispatchersProvider(dispatcher),
			trackingStartupGate = gate,
			awardProgression = { _, _, _ ->
				progressionAttempts++
				true
			},
			markMiniGameMetricsDirty = {},
			scheduleAchievementEvaluation = {},
		)

		val result = ensurer.ensure(
			GameReward(
				rewardId = miniGameRewardId("territory", 456L),
				gameId = "territory",
				points = 50,
				earnedAtMs = 456L,
			),
		)

		result shouldBe GameRewardEnsureResult.AlreadyEnsured
		dao.inserted shouldBe emptyList()
		progressionAttempts shouldBe 1
	}

	@Test
	fun `admitted points write quiesces before deletion and stale generation cannot restore xp`() =
		runTest {
			val dispatcher = StandardTestDispatcher(testScheduler)
			val insertStarted = CompletableDeferred<Unit>()
			val releaseInsert = CompletableDeferred<Unit>()
			val dao = RecordingPointsDao(
				beforeInsert = {
					insertStarted.complete(Unit)
					releaseInsert.await()
				},
			)
			val gate = SerializedTestTrackingStartupGate()
			val dirtyTracker = RecordingMetricDirtyTracker()
			val progressionGenerations = mutableListOf<Long>()
			val progression = progressionRepository(dispatcher, dirtyTracker, gate)
			var scheduledEvaluations = 0
			val ensurer = MiniGameRewardEnsurer(
				pointsDao = dao,
				dispatchers = TestDispatchersProvider(dispatcher),
				trackingStartupGate = gate,
				awardProgression = { points, earnedAtMs, expectedGeneration ->
					progressionGenerations += expectedGeneration
					progression.awardMiniGameXpForGeneration(
						points,
						earnedAtMs,
						expectedGeneration,
					)
				},
				markMiniGameMetricsDirty = { error("stale reward marked metrics dirty") },
				scheduleAchievementEvaluation = { scheduledEvaluations++ },
			)
			val reward = reward(gameId = "outrun", points = 40, earnedAtMs = 789L)

			val ensure = async { ensurer.ensure(reward) }
			insertStarted.await()
			val deletion = async {
				gate.closeDeleteReopen { deleteRewardState(dao) }
			}
			runCurrent()

			deletion.isCompleted shouldBe false
			releaseInsert.complete(Unit)
			deletion.await()

			ensure.await() shouldBe GameRewardEnsureResult.Rejected(
				GameRewardRejectionReason.PERSISTENCE_UNAVAILABLE,
			)
			dao.inserted shouldBe emptyList()
			database.xpLedgerDao().getTotalXp() shouldBe 0L
			database.playerProfileDao().get() shouldBe null
			dirtyTracker.markCalls shouldBe 0
			scheduledEvaluations shouldBe 0
			progressionGenerations shouldBe listOf(1L)
			gate.operationGenerations shouldBe listOf(1L)
		}

	@Test
	fun `generation retired after acceptance rejects points before either database write`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val dao = RecordingPointsDao()
		val gate = SerializedTestTrackingStartupGate().apply {
			afterNextReconcile = {
				closeDeleteReopen { deleteRewardState(dao) }
			}
		}
		val dirtyTracker = RecordingMetricDirtyTracker()
		val progression = progressionRepository(dispatcher, dirtyTracker, gate)
		val ensurer = MiniGameRewardEnsurer(
			pointsDao = dao,
			dispatchers = TestDispatchersProvider(dispatcher),
			trackingStartupGate = gate,
			awardProgression = progression::awardMiniGameXpForGeneration,
			markMiniGameMetricsDirty = { error("retired reward marked metrics dirty") },
			scheduleAchievementEvaluation = { error("retired reward scheduled evaluation") },
		)

		ensurer.ensure(reward(gameId = "territory", points = 50, earnedAtMs = 987L)) shouldBe
			GameRewardEnsureResult.Rejected(
			GameRewardRejectionReason.PERSISTENCE_UNAVAILABLE,
		)

		dao.inserted shouldBe emptyList()
		database.xpLedgerDao().getTotalXp() shouldBe 0L
		database.playerProfileDao().get() shouldBe null
		dirtyTracker.markCalls shouldBe 0
		gate.operationGenerations shouldBe listOf(1L)
	}

	private fun progressionRepository(
		dispatcher: CoroutineDispatcher,
		dirtyTracker: MetricDirtyTracker,
		gate: TrackingStartupGate,
	) = PlayerProgressionRepository(
		database = database,
		dispatchers = TestDispatchersProvider(dispatcher),
		metricDirtyTracker = dirtyTracker,
		trackingStartupGate = gate,
	)

	private fun reward(gameId: String, points: Int, earnedAtMs: Long) = GameReward(
		rewardId = miniGameRewardId(gameId, earnedAtMs),
		gameId = gameId,
		points = points,
		earnedAtMs = earnedAtMs,
	)

	private suspend fun deleteRewardState(dao: RecordingPointsDao) {
		dao.deleteAll()
		database.withTransaction {
			database.xpLedgerDao().deleteAll()
			database.playerProfileDao().deleteAll()
		}
	}

	private class RecordingPointsDao(
		private val beforeInsert: suspend () -> Unit = {},
	) : PointsAwardedDao {
		val inserted = mutableListOf<PointsAwarded>()
		val existing = mutableSetOf<Pair<Long, String>>()

		override fun countBetween(from: Long, to: Long): Double = 0.0
		override fun countBetweenFlow(from: Long, to: Long): Flow<Double> = flowOf(0.0)
		override fun hasAwardAt(time: Long, source: String): Boolean = time to source in existing
		override suspend fun insert(obj: PointsAwarded): Long {
			beforeInsert()
			inserted += obj
			existing += obj.time to obj.source.value
			return inserted.size.toLong()
		}
		override suspend fun insert(obj: Collection<PointsAwarded>): List<Long> = obj.map { insert(it) }
		override suspend fun update(obj: PointsAwarded) = Unit
		override suspend fun update(obj: Collection<PointsAwarded>) = Unit
		override suspend fun delete(obj: PointsAwarded) = Unit
		override suspend fun delete(obj: Collection<PointsAwarded>) = Unit
		override fun deleteAll() {
			inserted.clear()
			existing.clear()
		}
	}

	private class SerializedTestTrackingStartupGate : TrackingStartupGate {
		@Volatile private var ready = true
		@Volatile private var generation = 1L
		private val operationMutex = Mutex()
		var afterNextReconcile: (suspend () -> Unit)? = null
		val operationGenerations = mutableListOf<Long>()

		override val isReady: Boolean
			get() = ready

		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
			val result = if (ready) {
				TrackingStartupResult.Ready(false, 0L)
			} else {
				TrackingStartupResult.Blocked(
					TrackingStartupStage.STORAGE,
					"TEST_DELETION_CLOSED",
				)
			}
			afterNextReconcile?.also { afterNextReconcile = null }?.invoke()
			return result
		}

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? {
			operationGenerations += expectedGeneration
			return operationMutex.withLock {
				if (isReadyGeneration(expectedGeneration)) {
					operation()
				} else {
					null
				}
			}
		}

		override fun <T> withReadyGeneration(
			expectedGeneration: Long,
			operation: () -> T,
		): T? = if (isReadyGeneration(expectedGeneration)) {
			operation()
		} else {
			null
		}

		suspend fun closeDeleteReopen(delete: suspend () -> Unit) {
			ready = false
			generation += 1L
			operationMutex.withLock {
				delete()
				ready = true
			}
		}
	}

	private class RecordingMetricDirtyTracker : MetricDirtyTracker {
		var markCalls = 0
			private set

		override fun markDirty(table: String) {
			markCalls += 1
		}

		override fun markDirty(tables: Set<String>) {
			if (tables.isNotEmpty()) {
				markCalls += 1
			}
		}

		override suspend fun snapshotDirty(
			consumer: MetricDirtyTracker.Consumer,
		): MetricDirtyTracker.DirtySnapshot = MetricDirtyTracker.DirtySnapshot(emptyMap())

		override suspend fun acknowledgeDirty(
			consumer: MetricDirtyTracker.Consumer,
			snapshot: MetricDirtyTracker.DirtySnapshot,
		): Boolean = true
	}
}
