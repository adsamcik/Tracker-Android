package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsSessionFactWriterDeletionRearmTest {
	private lateinit var database: AppDatabase
	private lateinit var catalog: ExecutableSourceLaneCatalog
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore
	private lateinit var coordinator: StepsSessionFactWriterTransitionCoordinator

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		catalog = ExecutableSourceLaneCatalog()
		rolloutStore = RoomTrackingRolloutStateStore(database, catalog)
		coordinator = newCoordinator()
		database.sourceDestinationOwnerDao().insertIfAbsent(legacyOwner())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `full deletion rearms an empty candidate lane and advances the aba fence`() = runTest {
		installShadow()
		activate()
		appendStepsWalEvent()
		// Preserve a real AUTOINCREMENT gap: row count is not an admission high-water mark.
		database.sourceEventWalDao().deleteAll()
		appendStepsWalEvent()
		val admittedHighWater = requireNotNull(
			database.sourceEventWalDao().maximumAdmissionOrdinal(),
		)
		admittedHighWater shouldBe 2L
		database.sourceEventWalDao().countAll() shouldBe 1L
		recordFullDeletion(admittedHighWater, epoch = 1L, updatedAtMs = 50L)

		coordinator.rearmAfterFullDeletion(60L)

		assertCandidateAuthority(ownerGeneration = 3L, rolloutRevision = 4L)
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.activationOrdinal shouldBe 3L
			lane.contiguousAdmissionOrdinal shouldBe 2L
		}

		// A later retry must replace the empty generation rather than reuse its ABA identity.
		recordFullDeletion(admittedHighWater, epoch = 2L, updatedAtMs = 70L)
		coordinator.rearmAfterFullDeletion(80L)

		assertCandidateAuthority(ownerGeneration = 4L, rolloutRevision = 5L)
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.activationOrdinal shouldBe 3L
			lane.contiguousAdmissionOrdinal shouldBe 2L
		}
	}

	@Test
	fun `full deletion under legacy ownership advances generation without inventing a lane`() = runTest {
		coordinator.rearmAfterFullDeletion(10L)

		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 2L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
		rolloutStore.load().stepsAreUnavailableForCapture() shouldBe true
	}

	@Test
	fun `full deletion rearm rejects a residual legacy step interval`() = runTest {
		database.stepIntervalDao().insert(stepInterval())

		val failure = shouldThrow<IllegalStateException> {
			coordinator.rearmAfterFullDeletion(10L)
		}

		failure.message shouldBe "Steps writer deletion re-arm blocked: DELETION_ROWS_REMAIN"
		database.stepIntervalDao().hasAny() shouldBe true
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 1L
		}
		database.trackingRolloutStateDao().get() shouldBe null
	}

	@Test
	fun `full deletion during candidate retirement restores legacy containment`() = runTest {
		installShadow()
		activate()
		coordinator.beginCandidateRollback(3L, 30L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		database.sourceProjectionStateDao().deleteAllProductLanes()

		coordinator.rearmAfterFullDeletion(40L)

		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 3L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
		rolloutStore.load().let { rollout ->
			rollout.revision shouldBe 5L
			rollout.stepsAreUnavailableForCapture() shouldBe true
		}
	}

	@Test
	fun `every deletion rearm checkpoint rolls back lane owner and rollout together`() = runTest {
		installShadow()
		activate()
		database.sourceProjectionStateDao().deleteAllProductLanes()

		listOf(
			StepsWriterTransitionCheckpoint.AFTER_DELETION_LANE_REARM,
			StepsWriterTransitionCheckpoint.AFTER_DELETION_OWNER_REARM,
			StepsWriterTransitionCheckpoint.AFTER_DELETION_ROLLOUT_REARM,
		).forEach { checkpoint ->
			shouldThrow<DeletionRearmFailure> {
				failingAt(checkpoint).rearmAfterFullDeletion(60L)
			}
			database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)
				?.ownerGeneration shouldBe 2L
			database.trackingRolloutStateDao().get()?.revision shouldBe 3L
		}
	}

	private suspend fun recordFullDeletion(highWater: Long, epoch: Long, updatedAtMs: Long) {
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = epoch,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = highWater,
			updatedAtMs = updatedAtMs,
		)
		database.sourceEventWalDao().deleteAll()
		database.sourceProjectionStateDao().deleteAllProductLanes()
	}

	private suspend fun assertCandidateAuthority(ownerGeneration: Long, rolloutRevision: Long) {
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe CANDIDATE_OWNER
			owner.ownerGeneration shouldBe ownerGeneration
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.productStage shouldBe
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
		rolloutStore.load().let { rollout ->
			rollout.revision shouldBe rolloutRevision
			rollout.isCaptureReachable(
				SourceKind.STEPS,
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
			) shouldBe true
		}
	}

	private suspend fun installShadow() {
		rolloutStore.load() shouldBe TrackingRolloutState.contained(revision = 1L)
		rolloutStore.installInertShadowLane(STEPS_BINDING, 2L, 10L)
	}

	private suspend fun activate() = coordinator.activateCandidate(2L, 20L)

	private suspend fun appendStepsWalEvent() {
		database.sourceEventWalDao().insertIgnoringDuplicate(stepsWalEvent())
	}

	private fun stepsWalEvent() = SourceEventWalEntity(
		eventId = "steps-event-1",
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		sourceKind = STEPS_SOURCE,
		sourceInstanceId = "steps-instance",
		registrationGeneration = 1L,
		sourceSequence = 1L,
		configRevision = 1L,
		planAttribution = 0,
		clockDomainId = BOOT_ID,
		observedElapsedNanos = 1L,
		receivedElapsedNanos = 1L,
		wallTimeMs = 1L,
		wallTimeUncertaintyMs = 0L,
		capturedCollectedDataEpoch = 0L,
		acquiredAtMs = 1L,
		qualityFlags = 0L,
		qualityConfidence = null,
		payloadVersion = 1,
		payload = byteArrayOf(1),
		payloadChecksum = "checksum",
		createdAtMs = 1L,
	)

	private fun newCoordinator(
		faultInjector: StepsWriterTransitionFaultInjector = StepsWriterTransitionFaultInjector.NONE,
	) = StepsSessionFactWriterTransitionCoordinator(
		database,
		catalog,
		StepsWriterTransitionTestDependencies(
			startupGate = ReadyTrackingStartupGate,
			faultInjector = faultInjector,
		),
	)

	private fun failingAt(checkpoint: StepsWriterTransitionCheckpoint) = newCoordinator(
		StepsWriterTransitionFaultInjector { observed ->
			if (observed == checkpoint) {
				throw DeletionRearmFailure
			}
		},
	)

	private fun stepInterval() = StepInterval(
		startTimeMs = 1L,
		endTimeMs = 2L,
		stepCount = 1,
		sensorValueStart = 10,
		sensorValueEnd = 11,
		sensorReset = false,
		createdAt = 2L,
	)

	private fun legacyOwner() = SourceDestinationOwnerEntity(
		sourceKind = STEPS_SOURCE,
		destination = STEPS_DESTINATION,
		owner = LEGACY_OWNER,
		ownerGeneration = 1L,
		updatedAtMs = 0L,
	)

	private fun TrackingRolloutState.stepsAreUnavailableForCapture(): Boolean =
		!isCaptureReachable(SourceKind.STEPS, CaptureReachabilityMode.MANUAL_SESSION_CAPTURE)

	private companion object {
		const val BOOT_ID = "boot-1"
		const val STEPS_SOURCE = SourceDestinationOwnerEntity.SOURCE_STEPS
		const val STEPS_DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		val STEPS_BINDING = ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS
	}
}

private object DeletionRearmFailure : RuntimeException()
