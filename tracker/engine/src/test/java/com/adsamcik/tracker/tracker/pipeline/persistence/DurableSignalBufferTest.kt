package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
	 * Auto-generates IDs to simulate autoGenerate = true and returns them from
	 * [insertAll] just like a real Room `@Insert` returning `List<Long>`.
	 */
	private class FakePendingSignalDao : PendingSignalDao {
		val store = mutableListOf<PendingSignalEntity>()
		private var nextId = 1L

		/** When true, [insertAll] throws to simulate a failed checkpoint. */
		var failInsert = false
		var afterInsert: (() -> Unit)? = null

		override suspend fun insertAll(signals: List<PendingSignalEntity>): List<Long> {
			if (failInsert) error("simulated insert failure")
			val ids = signals.map { signal ->
				val id = nextId++
				store.add(signal.copy(id = id))
				id
			}
			afterInsert?.invoke()
			return ids
		}

		override suspend fun getOldest(sessionId: Long, limit: Int): List<PendingSignalEntity> {
			return store
				.filter { it.sessionId == sessionId }
				.sortedWith(compareBy({ it.createdAt }, { it.id }))
				.take(limit)
		}

		override suspend fun getOldestAcrossSessions(limit: Int): List<PendingSignalEntity> {
			return store
				.sortedWith(compareBy({ it.createdAt }, { it.id }))
				.take(limit)
		}

		override suspend fun deleteByIds(ids: List<Long>) {
			store.removeAll { it.id in ids }
		}

		override suspend fun countByIds(ids: List<Long>): Int =
			store.count { it.id in ids }

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

	// region stage

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

	// endregion

	// region checkpoint

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
	fun checkpointReturnsGeneratedIds() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(3).forEach { buffer.stage(it) }

		val ids = buffer.checkpoint()

		ids shouldHaveSize 3
		ids shouldBe fakeDao.store.map { it.id }
	}

	@Test
	fun emptyCheckpointIsNoop() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val ids = buffer.checkpoint()

		ids.shouldBeEmpty()
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
	fun checkpointFailureRetainsStagedSignals() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(3).forEach { buffer.stage(it) }
		fakeDao.failInsert = true

		shouldThrow<IllegalStateException> { buffer.checkpoint() }

		// Nothing durably written, and staging is intact for the next attempt.
		fakeDao.store.shouldBeEmpty()
		buffer.stagingSize shouldBe 3

		// A subsequent successful checkpoint recovers the staged signals.
		fakeDao.failInsert = false
		buffer.checkpoint()
		fakeDao.store shouldHaveSize 3
		buffer.stagingSize shouldBe 0
	}

	@Test
	fun cancellationAfterWalCommitDoesNotRestageCommittedSignals() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))
		buffer.stage(createSignal())
		lateinit var checkpointJob: Job
		fakeDao.afterInsert = { checkpointJob.cancel() }

		checkpointJob = launch { buffer.checkpoint() }
		checkpointJob.join()

		fakeDao.store shouldHaveSize 1
		buffer.stagingSize shouldBe 0
		fakeDao.afterInsert = null
		buffer.checkpoint()
		fakeDao.store shouldHaveSize 1
	}

	// endregion

	// region peekBatch (read without delete)

	@Test
	fun peekReturnsDeserializedSignalsWithoutDeleting() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val signals = createDistinctSignals(3)
		signals.forEach { buffer.stage(it) }
		buffer.checkpoint()

		val peeked = buffer.peekBatch()

		peeked shouldHaveSize 3
		peeked.mapNotNull { it.signal?.timestampMs } shouldBe signals.map { it.timestampMs }
		// peek MUST NOT delete — rows stay until explicitly acknowledged.
		fakeDao.store shouldHaveSize 3
	}

	@Test
	fun peekRespectsLimit() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		createDistinctSignals(10).forEach { buffer.stage(it) }
		buffer.checkpoint()

		val peeked = buffer.peekBatch(limit = 3)

		peeked shouldHaveSize 3
		peeked[0].signal?.timestampMs shouldBe EpochMs(1_700_000_000_000L)
		peeked[1].signal?.timestampMs shouldBe EpochMs(1_700_000_001_000L)
		peeked[2].signal?.timestampMs shouldBe EpochMs(1_700_000_002_000L)
		// All 10 remain — peek never deletes.
		fakeDao.store shouldHaveSize 10
	}

	@Test
	fun peekEmptyDaoReturnsEmpty() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.peekBatch().shouldBeEmpty()
	}

	@Test
	fun peekReturnsOldestAcrossAllSessions() = runTest {
		val sharedDispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))

		val buf42 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buf42.setSessionId(42L)
		buf42.stage(createSignal(1_700_000_000_000L))
		buf42.checkpoint()

		val buf99 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buf99.setSessionId(99L)
		buf99.stage(createSignal(1_700_000_099_000L))
		buf99.checkpoint()

		// Recovery is session-agnostic: peeking under session 42 still surfaces
		// session 99's row (oldest-first) so no session's WAL is orphaned.
		val peeked = buf42.peekBatch()

		peeked shouldHaveSize 2
		peeked[0].signal?.timestampMs shouldBe EpochMs(1_700_000_000_000L)
		peeked[1].signal?.timestampMs shouldBe EpochMs(1_700_000_099_000L)
		// Nothing deleted.
		fakeDao.store shouldHaveSize 2
	}

	@Test
	fun peekReturnsNullSignalForCorruptedRows() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		fakeDao.insertAll(
			listOf(
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(createSignal()),
					createdAt = 1L,
				),
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = "{corrupt garbage!!!",
					createdAt = 2L,
				),
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(createSignal(1_700_000_099_000L)),
					createdAt = 3L,
				),
			),
		)

		val peeked = buffer.peekBatch()

		peeked shouldHaveSize 3
		peeked[0].signal.shouldNotBeNull()
		peeked[1].signal.shouldBeNull()
		peeked[2].signal.shouldNotBeNull()
		// Corruption is surfaced to the caller, not silently deleted here.
		fakeDao.store shouldHaveSize 3
	}

	// endregion

	// region hasPendingEntries

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
	fun trueReflectsDaoNotStaging() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		// Only stage, don't checkpoint — DAO still empty.
		buffer.stage(createSignal())
		buffer.hasPendingEntries().shouldBeFalse()
	}

	@Test
	fun hasPendingEntriesSeesOtherSessions() = runTest {
		val sharedDispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))

		val bufA = DurableSignalBuffer(fakeDao, sharedDispatchers)
		bufA.setSessionId(1L)
		bufA.stage(createSignal())
		bufA.checkpoint()

		// A buffer for a brand-new session must still see the prior session's WAL.
		val bufB = DurableSignalBuffer(fakeDao, sharedDispatchers)
		bufB.setSessionId(2L)
		bufB.hasPendingEntries().shouldBeTrue()
	}

	// endregion

	// region clear

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

		buffer.stage(createSignal())
		buffer.checkpoint()

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

		fakeDao.store shouldHaveSize 1
		fakeDao.store.first().sessionId shouldBe 999L
	}

	// endregion

	// region cross-process recovery

	@Test
	fun newInstanceSeesCheckpointedSignals() = runTest {
		val sharedDispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))

		// "First process" — stage and checkpoint.
		val buffer1 = createBufferWithDispatchers(sharedDispatchers)
		val signals = createDistinctSignals(5)
		signals.forEach { buffer1.stage(it) }
		buffer1.checkpoint()

		// "New process" — a fresh buffer over the same DAO.
		val buffer2 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buffer2.setSessionId(sessionId)

		buffer2.hasPendingEntries().shouldBeTrue()
		buffer2.stagingSize shouldBe 0

		val recovered = buffer2.peekBatch()
		recovered shouldHaveSize 5
		recovered.mapNotNull { it.signal?.timestampMs } shouldBe signals.map { it.timestampMs }
		// Recovery reads without deleting.
		buffer2.hasPendingEntries().shouldBeTrue()
	}

	@Test
	fun stagingLostOnCrash() = runTest {
		val sharedDispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))

		val buffer1 = createBufferWithDispatchers(sharedDispatchers)
		buffer1.stage(createSignal())
		buffer1.stage(createSignal())
		// No checkpoint! Simulating crash before checkpoint.

		val buffer2 = DurableSignalBuffer(fakeDao, sharedDispatchers)
		buffer2.setSessionId(sessionId)

		buffer2.hasPendingEntries().shouldBeFalse()
		buffer2.stagingSize shouldBe 0
		buffer2.peekBatch().shouldBeEmpty()
	}

	// endregion
}
