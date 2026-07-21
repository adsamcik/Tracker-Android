package com.adsamcik.tracker.tracker.pipeline.persistence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
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

	private class MutableLifecycleStore(
		var current: CollectedDataLifecycleSnapshot,
	) : CollectedDataLifecycleStore {
		var afterSnapshot: (() -> Unit)? = null

		override val snapshots = emptyFlow<CollectedDataLifecycleSnapshot>()

		override suspend fun snapshot(): CollectedDataLifecycleSnapshot {
			val observed = current
			afterSnapshot?.let { action ->
				afterSnapshot = null
				action()
			}
			return observed
		}

		override suspend fun beginFullDeletion(
			deletedAtMs: Long,
		): CollectedDataLifecycleSnapshot = error("Not used by this test")

		override suspend fun advanceRetainedFrom(
			retainedFromMs: Long,
		): CollectedDataLifecycleSnapshot = error("Not used by this test")
	}

	// region Fake DAO

	/**
	 * In-memory fake of [PendingSignalDao] for unit testing without Room.
	 * Auto-generates IDs to simulate autoGenerate = true and returns them from
	 * [insertAll] just like a real Room `@Insert` returning `List<Long>`.
	 */
	private class FakePendingSignalDao : PendingSignalDao {
		val store = mutableListOf<PendingSignalEntity>()
		private var nextId = 1L

		/** When true, pending admission throws to simulate a failed checkpoint. */
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

		override suspend fun insertAllIgnoringSignalIdConflicts(
			signals: List<PendingSignalEntity>,
		): List<Long> {
			if (failInsert) error("simulated insert failure")
			val ids = signals.map { signal ->
				val existing = store.firstOrNull { it.signalId == signal.signalId }
				if (existing != null) {
					-1L
				} else {
					val id = nextId++
					store.add(signal.copy(id = id))
					id
				}
			}
			afterInsert?.invoke()
			return ids
		}

		override suspend fun getBySignalIds(signalIds: List<String>): List<PendingSignalEntity> =
			store.filter { it.signalId in signalIds }

		override suspend fun getOldest(sessionId: Long, limit: Int): List<PendingSignalEntity> {
			return store
				.filter { it.sessionId == sessionId }
				.sortedBy(PendingSignalEntity::id)
				.take(limit)
		}

		override suspend fun getOldestAcrossSessions(limit: Int): List<PendingSignalEntity> {
			return store
				.sortedBy(PendingSignalEntity::id)
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

		override suspend fun hasAny(): Boolean = store.isNotEmpty()

		override fun deleteAll() {
			store.clear()
		}
	}

	private class FakePendingSignalClaimDao(
		private val pendingDao: FakePendingSignalDao,
	) : PendingSignalClaimDao {
		private val quarantined = mutableListOf<QuarantinedSignalEntity>()

		override suspend fun selectClaimable(
			nowMs: Long,
			limit: Int,
		): List<PendingSignalEntity> = pendingDao.store
			.filter { row ->
				val expiresAt = row.claimExpiresAt
				row.claimToken == null || expiresAt == null || expiresAt <= nowMs
			}
			.sortedBy(PendingSignalEntity::id)
			.take(limit)

		override suspend fun claimIds(
			ids: List<Long>,
			claimToken: String,
			nowMs: Long,
			leaseExpiresAtMs: Long,
		): Int {
			var claimed = 0
			pendingDao.store.indices.forEach { index ->
				val row = pendingDao.store[index]
				val expiresAt = row.claimExpiresAt
				if (row.id in ids &&
					(row.claimToken == null || expiresAt == null || expiresAt <= nowMs)
				) {
					pendingDao.store[index] = row.copy(
						claimToken = claimToken,
						claimExpiresAt = leaseExpiresAtMs,
						deliveryAttemptCount = row.deliveryAttemptCount + 1,
					)
					claimed++
				}
			}
			return claimed
		}

		override suspend fun getClaimedByToken(
			claimToken: String,
			limit: Int,
		): List<PendingSignalEntity> = pendingDao.store
			.filter { it.claimToken == claimToken }
			.sortedBy(PendingSignalEntity::id)
			.take(limit)

		override suspend fun deleteClaimedByIds(ids: List<Long>, claimToken: String): Int {
			val matching = pendingDao.store.count { it.id in ids && it.claimToken == claimToken }
			pendingDao.store.removeAll { it.id in ids && it.claimToken == claimToken }
			return matching
		}

		override suspend fun releaseClaim(claimToken: String): Int {
			var released = 0
			pendingDao.store.indices.forEach { index ->
				val row = pendingDao.store[index]
				if (row.claimToken == claimToken) {
					pendingDao.store[index] = row.copy(claimToken = null, claimExpiresAt = null)
					released++
				}
			}
			return released
		}

		override suspend fun insertQuarantine(signal: QuarantinedSignalEntity): Long {
			if (quarantined.any { it.sourcePendingId == signal.sourcePendingId }) return -1L
			quarantined.add(signal)
			return quarantined.size.toLong()
		}

		override suspend fun hasQuarantineForSource(sourcePendingId: Long): Boolean =
			quarantined.any { it.sourcePendingId == sourcePendingId }
	}

	// endregion

	// region Test fixtures

	private lateinit var fakeDao: FakePendingSignalDao
	private lateinit var fakeClaimDao: FakePendingSignalClaimDao
	private lateinit var buffer: DurableSignalBuffer

	private val sessionId = 42L

	@Before
	fun setup() {
		fakeDao = FakePendingSignalDao()
		fakeClaimDao = FakePendingSignalClaimDao(fakeDao)
	}

	private fun createBufferWithDispatchers(
		testDispatchers: DispatchersProvider,
		withClaims: Boolean = false,
	): DurableSignalBuffer {
		return DurableSignalBuffer(
			pendingSignalDao = fakeDao,
			dispatchers = testDispatchers,
			pendingSignalClaimDao = fakeClaimDao.takeIf { withClaims },
		).also {
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
	fun checkpointPersistsStableIdentityAndVerifiedEnvelope() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val staged = buffer.stage(createSignal())
		buffer.checkpoint()

		val persisted = fakeDao.store.single()
		persisted.signalId shouldBe staged.signalId
		persisted.envelopeVersion shouldBe SignalSerializer.CURRENT_ENVELOPE_VERSION
		persisted.payloadChecksum shouldBe SignalSerializer.payloadChecksum(persisted.signalJson)
		persisted.signalJson.contains("\"type\":\"tracking_signal\"") shouldBe true
	}

	@Test
	fun checkpointEmitsSignalOnlyAfterPendingRowCommit() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val staged = buffer.stage(createSignal())
		var committed = emptyList<DurableSignalBuffer.CheckpointedSignal>()

		buffer.checkpoint { committed = it }

		committed shouldHaveSize 1
		committed.single().id shouldBe fakeDao.store.single().id
		committed.single().signalId shouldBe staged.signalId
		committed.single().signal shouldBe staged.signal
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
	fun checkpointResolvesPreviouslyCommittedStableIdentityAfterAmbiguousFailure() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))
		val staged = buffer.stage(
			createSignal().copy(persistenceSignalId = "signal-retry-after-ambiguous-commit"),
		)

		// Simulate a database commit whose acknowledgement is lost to the caller. The first attempt
		// leaves staging intact even though the backing store now has the row.
		fakeDao.afterInsert = { error("simulated lost insert acknowledgement") }
		shouldThrow<IllegalStateException> { buffer.checkpoint() }
		fakeDao.store shouldHaveSize 1
		fakeDao.store.single().signalId shouldBe staged.signalId
		buffer.stagingSize shouldBe 1

		// A subsequent checkpoint must resolve the existing pending row instead of treating the
		// unique signal_id conflict as a failed admission or inserting a duplicate.
		fakeDao.afterInsert = null
		val resolvedIds = buffer.checkpoint()
		resolvedIds shouldBe listOf(fakeDao.store.single().id)
		fakeDao.store shouldHaveSize 1
		buffer.stagingSize shouldBe 0
	}

	@Test
	fun `idempotent retry reports lifecycle metadata from the committed pending row`() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		val lifecycle = MutableLifecycleStore(CollectedDataLifecycleSnapshot(epoch = 3L, retainedFromMs = null))
		buffer = DurableSignalBuffer(
			pendingSignalDao = fakeDao,
			dispatchers = TestDispatchersProvider(testDispatcher),
			collectedDataLifecycleStore = lifecycle,
		).also { it.setSessionId(sessionId) }
		val signal = createSignal(timestamp = 1_700_000_000_000L)

		buffer.stage(DurableSignalBuffer.StagedSignal("stable-retry-id", signal))
		buffer.checkpoint()
		val committedRow = fakeDao.store.single()
		committedRow.capturedEpoch shouldBe 3L

		// A retry after an ambiguous acknowledgement sees a new lifecycle snapshot. It resolves the
		// old row, so the callback must carry that row's metadata rather than the retry's epoch.
		lifecycle.current = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
		buffer.stage(DurableSignalBuffer.StagedSignal("stable-retry-id", signal))
		var checkpointed = emptyList<DurableSignalBuffer.CheckpointedSignal>()
		buffer.checkpoint { checkpointed = it }

		checkpointed.single().id shouldBe committedRow.id
		checkpointed.single().capturedEpoch shouldBe committedRow.capturedEpoch
		checkpointed.single().acquiredAtMs shouldBe committedRow.acquiredAtMs
	}

	@Test
	fun `admission rechecks authoritative lifecycle inside the Room transaction`() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		val database = AppDatabase.testDatabase(context)
		try {
			val lifecycle = MutableLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 0L, retainedFromMs = null),
			)
			lifecycle.afterSnapshot = {
				lifecycle.current = CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 5_000L)
			}
			val databaseBuffer = DurableSignalBuffer(
				pendingSignalDao = database.pendingSignalDao(),
				dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
				appDatabase = database,
				collectedDataLifecycleStore = lifecycle,
			).also { it.setSessionId(sessionId) }

			databaseBuffer.stage(
				DurableSignalBuffer.StagedSignal(
					signalId = "captured-before-deletion",
					signal = createSignal(timestamp = 1_000L),
				),
			)
			val admission = databaseBuffer.checkpointWithAdmission()

			admission.admittedIds.shouldBeEmpty()
			admission.lifecycleRejectedCount shouldBe 1
			database.pendingSignalDao().countAll() shouldBe 0
			val guard = requireNotNull(database.sourceEvidenceStateDao().get())
			guard.collectedDataEpoch shouldBe 1L
			guard.retainedFromMs shouldBe 5_000L
		} finally {
			database.close()
		}
	}

	@Test
	fun stageUsesProducerPersistenceIdentityWhenPresent() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))
		val signal = createSignal().copy(persistenceSignalId = "producer-signal-id")

		val staged = buffer.stage(signal)
		buffer.checkpoint()

		staged.signalId shouldBe "producer-signal-id"
		fakeDao.store.single().signalId shouldBe "producer-signal-id"
	}

	@Test
	fun stageCoalescesRepeatProducerIdentityBeforeCheckpoint() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))
		val signal = createSignal().copy(persistenceSignalId = "producer-redelivery")

		buffer.stage(signal)
		buffer.stage(signal)
		buffer.stagingSize shouldBe 1

		buffer.checkpoint()
		fakeDao.store shouldHaveSize 1
		fakeDao.store.single().signalId shouldBe "producer-redelivery"
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

	@Test
	fun peekSurfacesChecksumFailureForQuarantine() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		buffer.stage(createSignal())
		buffer.checkpoint()
		val persisted = fakeDao.store.single()
		fakeDao.store[0] = persisted.copy(signalJson = persisted.signalJson.replace("fused", "tampered"))

		val result = buffer.peekBatch().single().payload
			.shouldBeInstanceOf<PendingSignalDecodeResult.Malformed>()

		result.reason shouldBe PendingSignalDecodeFailure.PAYLOAD_CHECKSUM_MISMATCH
	}

	@Test
	fun peekSynthesizesDeterministicIdentityForLegacyRows() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(TestDispatchersProvider(testDispatcher))

		val id = fakeDao.insertAll(
			listOf(
				PendingSignalEntity(
					sessionId = sessionId,
					signalJson = SignalSerializer.serialize(createSignal()),
					createdAt = 1L,
				),
			),
		).single()

		val entry = buffer.peekBatch().single()
		entry.signalId shouldBe "legacy-pending-$id"
		entry.payload.shouldBeInstanceOf<PendingSignalDecodeResult.Valid>()
	}

	// endregion

	// region claimBatch

	@Test
	fun claimBatchLeasesVerifiedRowsAndReleaseMakesThemRetryable() = runTest {
		val testDispatcher = StandardTestDispatcher(testScheduler)
		buffer = createBufferWithDispatchers(
			testDispatchers = TestDispatchersProvider(testDispatcher),
			withClaims = true,
		)

		val staged = createDistinctSignals(2).map { buffer.stage(it) }
		buffer.checkpoint()

		val batch = buffer.claimBatch().shouldNotBeNull()

		batch.signals shouldHaveSize 2
		batch.signals.map { it.signalId } shouldBe staged.map { it.signalId }
		batch.signals.all { it.deliveryAttemptCount == 1 }.shouldBeTrue()
		fakeDao.store.all { it.claimToken == batch.claimToken }.shouldBeTrue()

		buffer.releaseClaim(batch.claimToken) shouldBe 2
		fakeDao.store.all { it.claimToken == null && it.claimExpiresAt == null }.shouldBeTrue()
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
