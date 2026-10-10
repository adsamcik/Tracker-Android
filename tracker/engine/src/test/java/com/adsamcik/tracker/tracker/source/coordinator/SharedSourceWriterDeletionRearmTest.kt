package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedSourceWriterDeletionRearmTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: TestLifecycle
	private val catalog = ExecutableSourceLaneCatalog()

	@Before
	fun setUp() = runTest {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		lifecycle = TestLifecycle()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `actual clear resets all recognized siblings before completing its exact receipt`() = runTest {
		val specs = SourceWriterTransitionSpec.entries
		putOwner(specs[0], specs[0].candidateOwner, 2L)
		putOwner(specs[1], specs[1].containedOwner, 3L)
		putOwner(specs[2], specs[2].candidateOwner, 4L)
		putOwner(specs[3], specs[3].containedOwner, 5L)
		val locationOwner = SourceDestinationOwnerEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			owner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
			ownerGeneration = 1L,
			updatedAtMs = 0L,
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(locationOwner)
		appendOriginalLocationWal("first", 1L)
		database.sourceEventWalDao().deleteAll()
		appendOriginalLocationWal("second", 2L)
		database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe 2L
		clear("clear-1", 1L, 100L)

		rearm().rearmAfterFullDeletion("clear-1", 1L, 100L)

		for (spec in specs) assertFinalOwner(spec)
		database.sourceDestinationOwnerDao().get(locationOwner.sourceKind, locationOwner.destination) shouldBe
			locationOwner
		database.collectedDataDeletionOperationDao().get("clear-1")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED
		database.sourceEvidenceStateDao().get()?.let {
			it.collectedDataEpoch shouldBe 1L
			it.retainedFromMs shouldBe 100L
			it.deletedSourceEventHighWaterOrdinal shouldBe 2L
		}
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceProjectionStateDao().allActiveProductLanes() shouldBe emptyList()
		val rollout = RoomTrackingRolloutStateStore(database, catalog).load()
		SourceKind.entries.all { !rollout.isAcquisitionReachable(it) } shouldBe true
	}

	@Test
	fun `known original final and contained states reuse final4 after successive genuine clears`() = runTest {
		putOwner(SourceWriterTransitionSpec.ACTIVITY,
			requireNotNull(SourceWriterTransitionSpec.ACTIVITY.initialOwner), 1L)
		putOwner(SourceWriterTransitionSpec.PRESSURE,
			requireNotNull(SourceWriterTransitionSpec.PRESSURE.initialOwner), 1L)
		// Wi-Fi/Cell's original absent destinations are admitted only in the actual clear transaction.
		clear("initial-clear", 1L, 100L)
		rearm().rearmAfterFullDeletion("initial-clear", 1L, 100L)
		for (spec in SourceWriterTransitionSpec.entries) assertFinalOwner(spec)

		for (generation in listOf(2L, 3L, 4L, 5L)) {
			val epoch = generation
			for (spec in SourceWriterTransitionSpec.entries) {
				val name = if (generation % 2L == 0L) spec.candidateOwner else spec.containedOwner
				// Seed each recognized historical state before another real full clear.
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_destination_owner SET owner = ?, owner_generation = ? " +
						"WHERE source_kind = ? AND destination = ?",
					arrayOf(name, generation, spec.source.stableCode, spec.destination),
				)
			}
			clear("clear-$epoch", epoch, epoch * 100L)
			rearm().rearmAfterFullDeletion("clear-$epoch", epoch, epoch * 100L)
			for (spec in SourceWriterTransitionSpec.entries) assertFinalOwner(spec)
			database.collectedDataDeletionOperationDao().get("clear-$epoch")?.phase shouldBe
				CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED
		}
	}

	@Test
	fun `completed replay does not reset newer same-epoch data or advance owner again`() = runTest {
		clear("clear-once", 1L, 100L)
		val rearm = rearm()
		rearm.rearmAfterFullDeletion("clear-once", 1L, 100L)
		val ownerBefore = database.sourceDestinationOwnerDao().get(3, "SESSION_STEPS")
		val rolloutBefore = database.trackingRolloutStateDao().get()
		database.stepIntervalDao().insert(stepInterval())

		rearm.rearmAfterFullDeletion("clear-once", 1L, 100L)

		database.stepIntervalDao().hasAny() shouldBe true
		database.sourceDestinationOwnerDao().get(3, "SESSION_STEPS") shouldBe ownerBefore
		database.trackingRolloutStateDao().get() shouldBe rolloutBefore
	}

	@Test
	fun `unknown sibling owner stays preserved and contained while known sources reset`() = runTest {
		val unknown = putOwner(SourceWriterTransitionSpec.ACTIVITY, "unrecognized-local-owner", 19L)
		putOwner(SourceWriterTransitionSpec.PRESSURE, SourceWriterTransitionSpec.PRESSURE.containedOwner, 5L)
		clear("unknown-owner-clear", 1L, 100L)

		rearm().rearmAfterFullDeletion("unknown-owner-clear", 1L, 100L)

		database.sourceDestinationOwnerDao().get(unknown.sourceKind, unknown.destination) shouldBe unknown
		assertFinalOwner(SourceWriterTransitionSpec.PRESSURE)
		assertFinalOwner(SourceWriterTransitionSpec.WIFI)
		assertFinalOwner(SourceWriterTransitionSpec.CELL)
		RoomTrackingRolloutStateStore(database, catalog).load()
			.isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false
		database.collectedDataDeletionOperationDao().get("unknown-owner-clear")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED
	}

	@Test
	fun `missing receipt wrong epoch and different retention floor cannot reset known owner`() = runTest {
		val original = putOwner(SourceWriterTransitionSpec.CELL, SourceWriterTransitionSpec.CELL.containedOwner, 5L)
		shouldThrow<IllegalArgumentException> {
			rearm().rearmAfterFullDeletion("not-a-clear", 1L, 100L)
		}
		clear("exact-clear", 1L, 100L)
		lifecycle.snapshots.value = CollectedDataLifecycleSnapshot(2L, 100L)
		shouldThrow<IllegalStateException> { rearm().rearmAfterFullDeletion("exact-clear", 1L, 100L) }
		lifecycle.snapshots.value = CollectedDataLifecycleSnapshot(1L, 101L)
		shouldThrow<IllegalStateException> { rearm().rearmAfterFullDeletion("exact-clear", 1L, 100L) }
		lifecycle.snapshots.value = CollectedDataLifecycleSnapshot(1L, 100L)
		database.sourceEvidenceStateDao().updateLifecycle(2L, 100L, 101L)
		shouldThrow<IllegalStateException> { rearm().rearmAfterFullDeletion("exact-clear", 1L, 100L) }
		database.sourceEvidenceStateDao().updateLifecycle(1L, 100L, 102L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE collected_data_deletion_operation SET phase = 'RETENTION_PREPARED' " +
				"WHERE operation_id = ?", arrayOf("exact-clear"),
		)
		shouldThrow<IllegalStateException> { rearm().rearmAfterFullDeletion("exact-clear", 1L, 100L) }
		database.sourceDestinationOwnerDao().get(original.sourceKind, original.destination) shouldBe original
		database.collectedDataDeletionOperationDao().get("exact-clear")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED
	}

	@Test
	fun `rows appearing after clear keep its marker pending and all owners unchanged`() = runTest {
		val original = putOwner(SourceWriterTransitionSpec.PRESSURE, SourceWriterTransitionSpec.PRESSURE.containedOwner, 5L)
		clear("residual-clear", 1L, 100L)
		appendOriginalLocationWal("unexpected-new-row", 3L)

		shouldThrow<IllegalStateException> {
			rearm().rearmAfterFullDeletion("residual-clear", 1L, 100L)
		}

		database.sourceDestinationOwnerDao().get(original.sourceKind, original.destination) shouldBe original
		database.sourceEventWalDao().countAll() shouldBe 1L
		database.collectedDataDeletionOperationDao().get("residual-clear")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED
	}

	@Test
	fun `allocator advance without remaining row cannot impersonate the completed allocation fence`() = runTest {
		val original = putOwner(SourceWriterTransitionSpec.CELL, SourceWriterTransitionSpec.CELL.containedOwner, 5L)
		clear("allocator-clear", 1L, 100L)
		appendOriginalLocationWal("late-deleted-row", 3L)
		database.sourceEventWalDao().deleteAll()

		shouldThrow<IllegalStateException> {
			rearm().rearmAfterFullDeletion("allocator-clear", 1L, 100L)
		}

		database.sourceDestinationOwnerDao().get(original.sourceKind, original.destination) shouldBe original
		database.collectedDataDeletionOperationDao().get("allocator-clear")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED
	}

	@Test
	fun `Steps fault rolls back already-reset sibling owners rollout and operation phase together`() = runTest {
		val original = putOwner(SourceWriterTransitionSpec.PRESSURE, SourceWriterTransitionSpec.PRESSURE.containedOwner, 5L)
		clear("fault-clear", 1L, 100L)
		val failed = rearm(StepsWriterTransitionFaultInjector { checkpoint ->
			if (checkpoint == StepsWriterTransitionCheckpoint.AFTER_DELETION_OWNER_REARM) throw RearmFailure
		})

		shouldThrow<RearmFailure> { failed.rearmAfterFullDeletion("fault-clear", 1L, 100L) }

		database.sourceDestinationOwnerDao().get(original.sourceKind, original.destination) shouldBe original
		database.sourceDestinationOwnerDao().get(5, "SESSION_WIFI") shouldBe null
		database.sourceDestinationOwnerDao().get(3, "SESSION_STEPS") shouldBe null
		database.trackingRolloutStateDao().get() shouldBe null
		database.collectedDataDeletionOperationDao().get("fault-clear")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED
		rearm().rearmAfterFullDeletion("fault-clear", 1L, 100L)
		assertFinalOwner(SourceWriterTransitionSpec.PRESSURE)
	}

	@Test
	fun `original Steps canonical contract becomes final future contract after actual clear`() = runTest {
		val store = RoomTrackingRolloutStateStore(database, catalog)
		database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(
			3, "SESSION_STEPS", SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL, 1L, 0L,
		))
		store.load()
		store.installInertShadowLane(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1, 2L, 10L)
		StepsSessionFactWriterTransitionCoordinator(database, catalog,
			StepsWriterTransitionTestDependencies(ReadyTrackingStartupGate))
			.activateCandidate(2L, 20L).shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		clear("Steps-original-clear", 1L, 100L)

		rearm().rearmAfterFullDeletion("Steps-original-clear", 1L, 100L)

		val lane = requireNotNull(database.sourceProjectionStateDao().activeProductLane(3))
		lane.bindingGeneration shouldBe 2L
		lane.captureModeMask shouldBe 3L
		database.sourceDestinationOwnerDao().get(3, "SESSION_STEPS")?.ownerGeneration shouldBe 3L
		store.load().isCaptureReachable(SourceKind.STEPS,
			CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE) shouldBe true
	}

	@Test
	fun `narrow clear CAS cannot reset an arbitrary owner source epoch or completed operation`() = runTest {
		val spec = SourceWriterTransitionSpec.CELL
		putOwner(spec, spec.containedOwner, 5L)
		clear("cas-clear", 1L, 100L)
		val dao = database.sourceDestinationOwnerDao()
		suspend fun reset(operationId: String, epoch: Long, source: Int = 6,
			destination: String = spec.destination, owner: String = spec.containedOwner,
			generation: Long = 5L) = dao.resetKnownCaptureOwnerAfterFullDeletion(
			source, destination, owner, generation, operationId, epoch, 100L,
		)
		reset("another-operation", 1L) shouldBe 0
		reset("cas-clear", 2L) shouldBe 0
		reset("cas-clear", 1L, source = 1, destination = "SESSION_LOCATION") shouldBe 0
		reset("cas-clear", 1L, owner = "unknown") shouldBe 0
		reset("cas-clear", 1L, generation = 19L) shouldBe 0
		rearm().rearmAfterFullDeletion("cas-clear", 1L, 100L)
		reset("cas-clear", 1L, owner = spec.candidateOwner, generation = 4L) shouldBe 0
		assertFinalOwner(spec)
	}

	private fun rearm(fault: StepsWriterTransitionFaultInjector = StepsWriterTransitionFaultInjector.NONE) =
		SharedSourceWriterDeletionRearm(database, lifecycle,
			StepsWriterDeletionRearm(StepsWriterTransitionState(database, catalog),
				StepsWriterTransitionRuntimeHooks.forTest(StepsWriterTransitionTestDependencies(
					startupGate = ReadyTrackingStartupGate, faultInjector = fault,
				))),
		)

	private suspend fun clear(operationId: String, epoch: Long, time: Long) {
		lifecycle.snapshots.value = CollectedDataLifecycleSnapshot(epoch, time)
		AppDatabase.deleteAllCollectedDataForTesting(database, operationId, epoch, time, time)
	}

	private suspend fun putOwner(spec: SourceWriterTransitionSpec, name: String, generation: Long): SourceDestinationOwnerEntity =
		SourceDestinationOwnerEntity(spec.source.stableCode, spec.destination, name, generation, 0L).also {
			database.sourceDestinationOwnerDao().insertIfAbsent(it)
		}

	private suspend fun assertFinalOwner(spec: SourceWriterTransitionSpec) {
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(spec.source.stableCode, spec.destination))
		owner.owner shouldBe spec.candidateOwner
		owner.ownerGeneration shouldBe 4L
	}

	private suspend fun appendOriginalLocationWal(identity: String, sequence: Long) {
		// Original WAL allocation fixture; no claim that these payload bytes are a Location fix.
		database.sourceEventWalDao().insertIgnoringDuplicate(SourceEventWalEntity(
			eventId = identity, providerDedupKey = null, logicalTrackingId = null, serviceRunId = null,
			sourceKind = 1, sourceInstanceId = "old-location-source", registrationGeneration = 1L,
			sourceSequence = sequence, configRevision = 1L, planAttribution = 0, clockDomainId = "old-boot",
			observedElapsedNanos = sequence, receivedElapsedNanos = sequence, wallTimeMs = 1L,
			wallTimeUncertaintyMs = 0L, capturedCollectedDataEpoch = 0L, acquiredAtMs = 1L,
			qualityFlags = 0L, qualityConfidence = null, payloadVersion = 1, payload = byteArrayOf(1),
			payloadChecksum = "checksum", createdAtMs = 1L,
		))
	}

	private fun stepInterval() = StepInterval(
		startTimeMs = 101L, endTimeMs = 102L, stepCount = 1,
		sensorValueStart = 10, sensorValueEnd = 11, sensorReset = false, createdAt = 102L,
	)
}

private class TestLifecycle : CollectedDataLifecycleStore {
	override val snapshots = MutableStateFlow(CollectedDataLifecycleSnapshot(0L, null))
	override suspend fun snapshot() = snapshots.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		error("The existing acknowledged lifecycle boundary is a fixture; the reader must not start deletion")
	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		error("Writer re-arm must not change retention")
}

private object RearmFailure : RuntimeException()
