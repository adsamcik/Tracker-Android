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
	)

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
