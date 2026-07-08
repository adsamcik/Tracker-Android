package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DurableSignalBufferTest {

	// region Fake DAO

	/**
	 * In-memory fake of [PendingSignalDao] for unit testing without Room.
	 * Auto-generates IDs to simulate autoGenerate = true.
	 */
	private class FakePendingSignalDao : PendingSignalDao {
		val store = mutableListOf<PendingSignalEntity>()
		private var nextId = 1L

		override suspend fun insertAll(signals: List<PendingSignalEntity>) {
			signals.forEach { signal ->
				store.add(signal.copy(id = nextId++))
			}
		}

		override suspend fun getOldest(sessionId: Long, limit: Int): List<PendingSignalEntity> {
			return store
				.filter { it.sessionId == sessionId }
				.sortedWith(compareBy({ it.createdAt }, { it.id }))
				.take(limit)
		}

		override suspend fun deleteByIds(ids: List<Long>) {
			store.removeAll { it.id in ids }
		}

		override suspend fun deleteBySession(sessionId: Long) {
			store.removeAll { it.sessionId == sessionId }
		}

		override suspend fun countForSession(sessionId: Long): Int {
			return store.count { it.sessionId == sessionId }
		}

		override suspend fun countAll(): Int = store.size

		override fun deleteAll() {
			store.clear()
		}
	}

	// endregion

	// region Test fixtures

	private lateinit var fakeDao: FakePendingSignalDao
	private lateinit var dispatchers: DispatchersProvider
	private lateinit var buffer: DurableSignalBuffer

	private val sessionId = 42L

	@Before
	fun setup() {
		fakeDao = FakePendingSignalDao()
	}

	private fun createBufferWithDispatchers(
		testDispatchers: DispatchersProvider,
	): DurableSignalBuffer {
		return DurableSignalBuffer(fakeDao, testDispatchers).also {
			it.setSessionId(sessionId)
		}
	}

	private fun createSignal(
		timestamp: Long = 1_700_000_000_000L,
	) = TrackingSignal(
		timestampMs = EpochMs(timestamp),
		elapsedRealtimeNanos = timestamp,
		location = LocationSignal(
			coordinate = CoordinateE7(
				lat = LatE7(500_000_000),
				lon = LonE7(140_000_000),
			),
			horizontalAccuracyM = 5.0f,
			speed = SpeedMps(1.0f),
			provider = "fused",
		),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(80),
		),
	)

	private fun createDistinctSignals(count: Int): List<TrackingSignal> {
		return (0 until count).map { i ->
			createSignal(timestamp = 1_700_000_000_000L + i * 1000L)
		}
	}

	// endregion


	@Test
	fun stageAddsToMemory() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.stagingSize shouldBe 0

		buffer.stage(createSignal(1_700_000_000_000L))
		buffer.stage(createSignal(1_700_000_001_000L))
		buffer.stage(createSignal(1_700_000_002_000L))

		buffer.stagingSize shouldBe 3
	}

	@Test
	fun stageDoesNotTouchDao() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.stage(createSignal())

		fakeDao.store.shouldBeEmpty()
	}


	@Test
	fun checkpointMovesToDao() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val signals = createDistinctSignals(3)
		signals.forEach { buffer.stage(it) }
		buffer.stagingSize shouldBe 3

		buffer.checkpoint()

		buffer.stagingSize shouldBe 0
		fakeDao.store shouldHaveSize 3
		fakeDao.store.all { it.sessionId == sessionId }.shouldBeTrue()
	}

	@Test
	fun emptyCheckpointIsNoop() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.checkpoint()

		fakeDao.store.shouldBeEmpty()
	}

	@Test
	fun multipleCheckpoints() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.stage(createSignal(1_700_000_000_000L))
		buffer.checkpoint()

		buffer.stage(createSignal(1_700_000_001_000L))
		buffer.stage(createSignal(1_700_000_002_000L))
		buffer.checkpoint()

		fakeDao.store shouldHaveSize 3
	}


	@Test
	fun returnsDeserializedSignals() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val signals = createDistinctSignals(3)
		signals.forEach { buffer.stage(it) }
		buffer.checkpoint()

		val drained = buffer.drainBatch()

		drained shouldHaveSize 3
		drained.map { it.timestampMs } shouldBe signals.map { it.timestampMs }
	}

	@Test
	fun deletesFromDao() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(3).forEach { buffer.stage(it) }
		buffer.checkpoint()

		buffer.drainBatch()

		fakeDao.store.shouldBeEmpty()
	}

	@Test
	fun respectsLimit() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(10).forEach { buffer.stage(it) }
		buffer.checkpoint()

		val drained = buffer.drainBatch(limit = 3)

		drained shouldHaveSize 3
		// Should have returned the oldest 3
		drained[0].timestampMs shouldBe EpochMs(1_700_000_000_000L)
		drained[1].timestampMs shouldBe EpochMs(1_700_000_001_000L)
		drained[2].timestampMs shouldBe EpochMs(1_700_000_002_000L)
		// 7 should remain
		fakeDao.store shouldHaveSize 7
	}

	@Test
	fun emptyDaoReturnsEmpty() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val drained = buffer.drainBatch()

		drained.shouldBeEmpty()
	}

	@Test
	fun multipleDrains() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(5).forEach { buffer.stage(it) }
		buffer.checkpoint()

		val first = buffer.drainBatch(limit = 2)
		first shouldHaveSize 2

		val second = buffer.drainBatch(limit = 2)
		second shouldHaveSize 2

		val third = buffer.drainBatch(limit = 2)
		third shouldHaveSize 1

		val fourth = buffer.drainBatch(limit = 2)
		fourth.shouldBeEmpty()

		fakeDao.store.shouldBeEmpty()
	}


	@Test
	fun falseWhenEmpty() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.hasPendingEntries().shouldBeFalse()
	}

	@Test
	fun trueAfterCheckpoint() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.stage(createSignal())
		buffer.checkpoint()

		buffer.hasPendingEntries().shouldBeTrue()
	}

	@Test
	fun falseAfterDrainAll() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.stage(createSignal())
		buffer.checkpoint()
		buffer.drainBatch()

		buffer.hasPendingEntries().shouldBeFalse()
	}

	@Test
	fun trueReflectsDao() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		// Only stage, don't checkpoint — DAO still empty
		buffer.stage(createSignal())
		buffer.hasPendingEntries().shouldBeFalse()
	}


	@Test
	fun clearRemovesAll() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(5).forEach { buffer.stage(it) }
		buffer.checkpoint()
		fakeDao.store shouldHaveSize 5

		buffer.clear()

		fakeDao.store.shouldBeEmpty()
		buffer.hasPendingEntries().shouldBeFalse()
	}

	@Test
	fun clearOnlyCurrentSession() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		// Stage and checkpoint for current session
		buffer.stage(createSignal())
		buffer.checkpoint()

		// Manually insert an entry for a different session
		fakeDao.insertAll(
			listOf(
				PendingSignalEntity(
					sessionId = 999L,
					signalJson = SignalSerializer.serialize(createSignal()),
					createdAt = System.currentTimeMillis(),
				),
			),
		)
		fakeDao.store shouldHaveSize 2

		buffer.clear()

		// Only the other session's entry should remain
		fakeDao.store shouldHaveSize 1
		fakeDao.store.first().sessionId shouldBe 999L
	}


	@Test
	fun newInstanceRecovery() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val sharedDispatchers = TestDispatchersProvider(testDispatcher)

		// "First process" — stage and checkpoint
		val buffer1 = createBufferWithDispatchers(sharedDispatchers)
		val signals = createDistinctSignals(5)
		signals.forEach { buffer1.stage(it) }
		buffer1.checkpoint()

		// "Process dies" — buffer1 is lost, but DAO persists
		// "New process" — create a new buffer with the same DAO
		val buffer2 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buffer2.setSessionId(sessionId)

		// Recovery: new buffer should see the checkpointed signals
		buffer2.hasPendingEntries().shouldBeTrue()
		buffer2.stagingSize shouldBe 0 // In-memory staging is gone

		val recovered = buffer2.drainBatch()
		recovered shouldHaveSize 5
		recovered.map { it.timestampMs } shouldBe signals.map { it.timestampMs }

		buffer2.hasPendingEntries().shouldBeFalse()
	}

	@Test
	fun stagingLostOnCrash() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val sharedDispatchers = TestDispatchersProvider(testDispatcher)

		// Stage without checkpoint
		val buffer1 = createBufferWithDispatchers(sharedDispatchers)
		buffer1.stage(createSignal())
		buffer1.stage(createSignal())
		// No checkpoint! Simulating crash before checkpoint.

		// New buffer — staging is gone, DAO is empty
		val buffer2 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buffer2.setSessionId(sessionId)

		buffer2.hasPendingEntries().shouldBeFalse()
		buffer2.stagingSize shouldBe 0
		buffer2.drainBatch().shouldBeEmpty()
	}


	@Test
	fun drainOnlyCurrentSession() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val sharedDispatchers = TestDispatchersProvider(testDispatcher)

		// Buffer for session 42
		val buf42 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buf42.setSessionId(42L)
		buf42.stage(createSignal(1_700_000_000_000L))
		buf42.checkpoint()

		// Buffer for session 99
		val buf99 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buf99.setSessionId(99L)
		buf99.stage(createSignal(1_700_000_099_000L))
		buf99.checkpoint()

		fakeDao.store shouldHaveSize 2

		// Drain only session 42
		val drained = buf42.drainBatch()
		drained shouldHaveSize 1
		drained.first().timestampMs shouldBe EpochMs(1_700_000_000_000L)

		// Session 99 still there
		fakeDao.store shouldHaveSize 1
		fakeDao.store.first().sessionId shouldBe 99L
	}


	@Test
	fun corruptedEntriesSkipped() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		// Insert valid and corrupted entries directly
		val validSignal = createSignal()
		fakeDao.insertAll(
			listOf(
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(validSignal),
					createdAt = 1L,
				),
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = "{corrupt garbage!!!",
					createdAt = 2L,
				),
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(
						createSignal(1_700_000_099_000L),
					),
					createdAt = 3L,
				),
			),
		)

		val drained = buffer.drainBatch()

		// Corrupted entry is silently skipped; 2 valid signals returned
		drained shouldHaveSize 2
		// But all 3 entries are deleted from DAO (including the corrupted one)
		fakeDao.store.shouldBeEmpty()
	}
}
