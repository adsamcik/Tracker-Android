package com.adsamcik.tracker.game.progression

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerProgressionRepositoryTest {

	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(context)
		kotlinx.coroutines.runBlocking { database.sourceEvidenceStateDao().ensure() }
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `distance and duration independently award xp while raw steps stay excluded`() = runTest {
		val gate = TestTrackingStartupGate()
		val dirtyTracker = RecordingMetricDirtyTracker()
		val repository = repository(gate, dirtyTracker, testScheduler)
		val distanceSessionId = insertSegment(
			startTimeMs = 1_000L,
			endTimeMs = 1_000L,
			distanceM = 1_000f,
			steps = 1_000_000,
		)
		val durationSessionId = insertSegment(
			startTimeMs = 2_000L,
			endTimeMs = 602_000L,
			distanceM = 0f,
			steps = 1_000_000,
		)

		repository.awardSessionXp(
			sessionEnded(distanceSessionId),
			gate.currentGeneration,
		) shouldBe SessionXpAwardResult.COMPLETED
		repository.awardSessionXp(
			sessionEnded(durationSessionId),
			gate.currentGeneration,
		) shouldBe SessionXpAwardResult.COMPLETED

		val ledger = database.xpLedgerDao().getRecent(limit = 10)
		assertEquals(setOf(50, 2), ledger.map { it.amount }.toSet())
		assertEquals(52L, database.xpLedgerDao().getTotalXp())
		assertEquals(52L, database.playerProfileDao().get()?.totalXp)
		assertEquals(2, dirtyTracker.markCalls)

		// A replay is not an accepted insert and must not publish a new dirty generation.
		repository.awardSessionXp(
			sessionEnded(distanceSessionId),
			gate.currentGeneration,
		) shouldBe SessionXpAwardResult.COMPLETED
		assertEquals(2, database.xpLedgerDao().getRecent(limit = 10).size)
		assertEquals(2, dirtyTracker.markCalls)
	}

	@Test
	fun `delayed session uses segment end day for timestamp and daily cap`() = runTest {
		val gate = TestTrackingStartupGate()
		val dirtyTracker = RecordingMetricDirtyTracker()
		val repository = repository(gate, dirtyTracker, testScheduler)
		val sessionEndMs = java.time.Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()
		val delayedProcessingMs = java.time.Instant.parse("2024-01-17T12:00:00Z").toEpochMilli()
		val sessionId = insertSegment(
			startTimeMs = sessionEndMs - 60_000L,
			endTimeMs = sessionEndMs,
			distanceM = 1_000f,
			steps = 0,
		)
		database.xpLedgerDao().insertOrIgnore(
			XpLedgerEntity(
				amount = XpCalculator.DAILY_CAP.toInt(),
				source = XpSource.MINI_GAME.name,
				sourceId = 99L,
				earnedAt = delayedProcessingMs,
			),
		)
		mockkObject(Time)
		every { Time.nowMillis } returns delayedProcessingMs

		try {
			repository.awardSessionXp(
				sessionEnded(sessionId),
				gate.currentGeneration,
			) shouldBe SessionXpAwardResult.COMPLETED
		} finally {
			unmockkObject(Time)
		}

		val sessionAward = database.xpLedgerDao().getRecent(limit = 10)
			.single { it.source == XpSource.SESSION.name }
		sessionAward.earnedAt shouldBe sessionEndMs
		sessionAward.amount shouldBe 50
		dirtyTracker.markCalls shouldBe 1
	}

	@Test
	fun `session daily cap includes day start and excludes both adjacent days`() = runTest {
		val gate = TestTrackingStartupGate()
		val dirtyTracker = RecordingMetricDirtyTracker()
		val repository = repository(gate, dirtyTracker, testScheduler)
		val zone = java.time.ZoneId.systemDefault()
		val day = java.time.LocalDate.of(2024, 1, 15)
		val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		val sessionId = insertSegment(start, start, 1_000f, 0)
		listOf(
			start - 1L to XpCalculator.DAILY_CAP.toInt(),
			start to XpCalculator.DAILY_CAP.toInt() - 7,
			end to XpCalculator.DAILY_CAP.toInt(),
		).forEachIndexed { index, (earnedAt, amount) ->
			database.xpLedgerDao().insertOrIgnore(
				XpLedgerEntity(
					amount = amount,
					source = XpSource.MINI_GAME.name,
					sourceId = index.toLong(),
					earnedAt = earnedAt,
				),
			)
		}

		repository.awardSessionXp(sessionEnded(sessionId), gate.currentGeneration)
			.shouldBe(SessionXpAwardResult.COMPLETED)

		val sessionAward = database.xpLedgerDao().getRecent(10)
			.single { it.source == XpSource.SESSION.name }
		sessionAward.amount shouldBe 7
		sessionAward.earnedAt shouldBe start
		dirtyTracker.markCalls shouldBe 1
	}

	@Test
	fun `deletion generation winning after event acceptance cannot resurrect xp or profile`() = runTest {
		val gate = TestTrackingStartupGate()
		val dirtyTracker = RecordingMetricDirtyTracker()
		val repository = repository(gate, dirtyTracker, testScheduler)
		val sessionId = insertSegment(
			startTimeMs = 1_000L,
			endTimeMs = 601_000L,
			distanceM = 1_000f,
			steps = 1_000_000,
		)
		database.xpLedgerDao().insertOrIgnore(
			XpLedgerEntity(
				amount = 9,
				source = XpSource.MINI_GAME.name,
				sourceId = 77L,
				earnedAt = 77L,
			),
		)
		database.playerProfileDao().insert(PlayerProfileEntity(totalXp = 9L))
		gate.beforeOperation = {
			gate.closeAdmission()
			database.withTransaction {
				database.sessionSegmentDao().deleteAll()
				database.xpLedgerDao().deleteAll()
				database.playerProfileDao().deleteAll()
			}
		}

		repository.awardSessionXp(
			sessionEnded(sessionId),
			gate.currentGeneration,
		) shouldBe SessionXpAwardResult.RETRY_NEEDED

		assertEquals(0L, database.sessionSegmentDao().countTotal())
		assertEquals(0L, database.xpLedgerDao().getTotalXp())
		assertTrue(database.xpLedgerDao().getRecent(limit = 10).isEmpty())
		assertNull(database.playerProfileDao().get())
		assertEquals(0, dirtyTracker.markCalls)
	}

	private fun repository(
		gate: TrackingStartupGate,
		dirtyTracker: MetricDirtyTracker,
		testScheduler: TestCoroutineScheduler,
	) = PlayerProgressionRepository(
		database = database,
		dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		metricDirtyTracker = dirtyTracker,
		trackingStartupGate = gate,
		trackingHistory = io.mockk.mockk<com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository> {
			// Reward economy fixture; source qualification is separately covered by real stats-data Room reads.
			io.mockk.coEvery { readSavedSessionReward(any()) } coAnswers {
				val id = firstArg<Long>()
				val segment = database.sessionSegmentDao().getById(id)
				if (segment == null) com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Removed(0, 2)
				else com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
					com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 1,
						segment.endTimeMs, segment.endTimeMs - segment.startTimeMs, segment.distanceM.toDouble()))
			}
		},
	)

	@Test fun `saved correction and deletion retract session XP and level with one durable receipt`() = runTest {
		val gate = TestTrackingStartupGate()
		val dirty = RecordingMetricDirtyTracker()
		val id = insertSegment(1_000, 601_000, 999_999f, 1_000_000)
		var current: com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead =
			com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
				com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 1, 601_000, 600_000, 1_000.0))
		val history = io.mockk.mockk<com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository> {
			io.mockk.coEvery { readSavedSessionReward(id) } coAnswers { current }
		}
		val repo = PlayerProgressionRepository(database, TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
			dirty, gate, history)
		repo.awardSessionXp(sessionEnded(id), gate.currentGeneration)
		assertEquals(52L, database.xpLedgerDao().getTotalXp())
		current = com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
			com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 2, 601_000, 600_000, 100.0))
		repo.awardSessionXp(sessionEnded(id), gate.currentGeneration)
		assertEquals(7L, database.xpLedgerDao().getTotalXp())
		assertEquals(7L, database.playerProfileDao().get()?.totalXp)
		current = com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Removed(0, 3)
		repo.reconcileSessionRewards(gate.currentGeneration)
		assertEquals(0L, database.xpLedgerDao().getTotalXp())
		assertEquals(0L, database.playerProfileDao().get()?.totalXp)
		val receipt = requireNotNull(database.xpLedgerDao().sessionEffect(id))
		assertEquals(3L, receipt.sourceRevision)
		current = com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
			com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 1, 601_000, 600_000, 1_000.0))
		repo.awardSessionXp(sessionEnded(id), gate.currentGeneration)
		assertEquals(receipt, database.xpLedgerDao().sessionEffect(id))
		assertEquals(0L, database.xpLedgerDao().getTotalXp())
	}

	@Test fun `restored session XP keeps one original receipt when its current data changes or is removed`() = runTest {
        val gate = TestTrackingStartupGate()
        val dirty = RecordingMetricDirtyTracker()
        val id = insertSegment(1_000, 601_000, 0f, 0)
        var current: com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead =
            com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
                com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 1, 601_000, 600_000, 1_000.0))
        val history = io.mockk.mockk<com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository> {
            io.mockk.coEvery { readSavedSessionReward(id) } coAnswers { current }
        }
        val repo = PlayerProgressionRepository(database, TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
            dirty, gate, history)
        repo.awardSessionXp(sessionEnded(id), gate.currentGeneration)
        assertEquals(52L, database.xpLedgerDao().getTotalXp())
        database.openHelper.writableDatabase.execSQL("UPDATE source_evidence_state SET collected_data_epoch = 7, " +
            "restored_data_epochs = '0', revision = 100 WHERE id = 1")
        current = com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
            com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 102, 601_000, 600_000, 100.0))
        repo.reconcileSessionRewards(gate.currentGeneration)
        assertEquals(7L, database.xpLedgerDao().getTotalXp())
        assertEquals("recording:0:$id", database.xpLedgerDao().sessionEffect(id)?.sourceKey)
        assertEquals(1, database.xpLedgerDao().sessionEffectPage(0, 10).size)
        current = com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Removed(7, 103)
        repo.reconcileSessionRewards(gate.currentGeneration)
        val zero = requireNotNull(database.xpLedgerDao().sessionEffect(id))
        assertEquals("recording:0:$id", zero.sourceKey)
        assertEquals(103L, zero.sourceRevision)
        assertEquals(0L, database.xpLedgerDao().getTotalXp())
        assertEquals(0L, database.playerProfileDao().get()?.totalXp)
        current = com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready(
            com.adsamcik.tracker.stats.api.repository.SavedSessionReward(id, 0, 1, 601_000, 600_000, 1_000.0))
        repo.awardSessionXp(sessionEnded(id), gate.currentGeneration)
        assertEquals(zero, database.xpLedgerDao().sessionEffect(id))
    }

	private suspend fun insertSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		distanceM: Float,
		steps: Int,
	): Long = database.sessionSegmentDao().insert(
		SessionSegment(
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			distanceM = distanceM,
			steps = steps,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 1,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = endTimeMs,
		),
	)

	private fun sessionEnded(sessionId: Long) = DomainEvent.SessionEnded(
		timestampMs = EpochMs(999_999L),
		processorId = "test",
		sessionId = sessionId,
		totalDistance = DistanceM(999_999f),
		totalSteps = StepCount(Int.MAX_VALUE),
		duration = DurationMs(Long.MAX_VALUE),
	)

	private class TestTrackingStartupGate : TrackingStartupGate {
		private var ready = true
		private var generation = 1L
		var beforeOperation: (suspend () -> Unit)? = null

		override val isReady: Boolean
			get() = ready

		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			if (ready) {
				TrackingStartupResult.Ready(
					legacyRecoveryPartial = false,
					liveCompletedThroughOrdinal = 0L,
				)
			} else {
				TrackingStartupResult.Blocked(
					stage = TrackingStartupStage.STORAGE,
					failureCode = "TEST_GATE_CLOSED",
				)
			}

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? {
			beforeOperation?.also { beforeOperation = null }?.invoke()
			return if (isReadyGeneration(expectedGeneration)) {
				operation()
			} else {
				null
			}
		}

		fun closeAdmission() {
			ready = false
			generation += 1L
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
