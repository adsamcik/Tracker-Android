package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityAutomationProjectionLaneTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `callback lane projects activity in source order without reading unrelated wal rows`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val events = listOf(activityEvent(2L), activityEvent(5L))
		coEvery {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 0L, 5L, 64)
		} returns events
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.drainThrough(5L) shouldBe CoordinatorDrainResult.Complete(5L, 2)

		database.sourceProjectionStateDao().checkpoint(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.contiguousAdmissionOrdinal shouldBe 5L
		database.sourceProjectionStateDao().pendingOutbox(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
			ActivityAutomationProjection.OUTBOX_KIND,
			10,
		).map { it.stableId } shouldContainExactly listOf(
			"${ActivityAutomationProjection.ID}:activity-2",
			"${ActivityAutomationProjection.ID}:activity-5",
		)
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 0L, 5L, 64)
		}
		coVerify(exactly = 0) { ingress.committedBatch(any(), any()) }
	}

	@Test
	fun `callback lane starts after deletion high water and applies first activity once`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 2L,
				deletedSourceEventHighWaterOrdinal = 5L,
			),
		)
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 5L, 8L, 64)
		} returns listOf(activityEvent(8L, collectedDataEpoch = 2L))
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.drainThrough(8L) shouldBe CoordinatorDrainResult.Complete(8L, 1)
		lane.drainThrough(8L) shouldBe CoordinatorDrainResult.Complete(8L, 0)

		database.sourceProjectionStateDao().registration(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.activationOrdinal shouldBe 6L
		database.sourceProjectionStateDao().pendingOutbox(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
			ActivityAutomationProjection.OUTBOX_KIND,
			10,
		).map { it.stableId } shouldContainExactly listOf(
			"${ActivityAutomationProjection.ID}:activity-8",
		)
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 5L, 8L, 64)
		}
		coVerify(exactly = 0) { ingress.committedBatch(any(), any()) }
	}

	@Test
	fun `empty wal still demotes a retained automation registration`() = runTest {
		database.sourceProjectionStateDao().register(
			SourceProjectionRegistrationEntity(
				projectionId = ActivityAutomationProjection.ID,
				projectionVersion = ActivityAutomationProjection.VERSION,
				activationOrdinal = 1L,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = 1L,
			),
		)
		database.sourceProjectionStateDao().saveCheckpoint(
			SourceProjectionCheckpointEntity(
				projectionId = ActivityAutomationProjection.ID,
				projectionVersion = ActivityAutomationProjection.VERSION,
				contiguousAdmissionOrdinal = 0L,
				stateVersion = 1,
				updatedAtMs = 1L,
			),
		)
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = mockk(),
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.drainAvailable() shouldBe CoordinatorDrainResult.Complete(0L, 0)
		database.sourceProjectionStateDao().registration(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.retentionRequired shouldBe false
		database.sourceProjectionStateDao().minimumRequiredCheckpoint() shouldBe null
	}

	@Test
	fun `pre-registered live tail projects the first real wal row once across crash retry and duplicate`() = runTest {
		val codec = DefaultSourcePayloadCodec()
		val ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = mockk<CollectedDataLifecycleStore>(),
			payloadCodec = codec,
			executableLaneCatalog = ExecutableSourceLaneCatalog.explicit(),
			trackingStartupGateProvider = mockk<Provider<TrackingStartupGate>>(),
		)
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.ensureRegisteredAtLiveTail()
		val firstCallback = activityWal(codec)
		database.sourceEventWalDao().insertIgnoringDuplicate(firstCallback) shouldBe 1L
		// A process retry after registration must preserve the original tail instead of skipping
		// evidence admitted between the durable registration and the retry.
		lane.ensureRegisteredAtLiveTail()

		lane.drainAvailable() shouldBe CoordinatorDrainResult.Complete(1L, 1)
		database.sourceEventWalDao().insertIgnoringDuplicate(firstCallback) shouldBe -1L
		lane.drainAvailable() shouldBe CoordinatorDrainResult.Complete(1L, 0)

		database.sourceProjectionStateDao().registration(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.activationOrdinal shouldBe 1L
		database.sourceProjectionStateDao().pendingOutbox(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
			ActivityAutomationProjection.OUTBOX_KIND,
			10,
		).map { it.stableId } shouldContainExactly listOf(
			"${ActivityAutomationProjection.ID}:first-real-callback",
		)
	}

	@Test
	fun `Activity outbox identity collision is quarantined without retaining wal or replacing owner`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 0L, 1L, 64)
		} returns listOf(activityEvent(1L))
		database.sourceProjectionStateDao().register(
			SourceProjectionRegistrationEntity(
				projectionId = ActivityAutomationProjection.ID,
				projectionVersion = ActivityAutomationProjection.VERSION,
				activationOrdinal = 1L,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = 1L,
			),
		)
		database.sourceProjectionStateDao().saveCheckpoint(
			SourceProjectionCheckpointEntity(
				projectionId = ActivityAutomationProjection.ID,
				projectionVersion = ActivityAutomationProjection.VERSION,
				contiguousAdmissionOrdinal = 0L,
				stateVersion = 1,
				updatedAtMs = 1L,
			),
		)
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "${ActivityAutomationProjection.ID}:activity-1",
				projectionId = "foreign-owner",
				projectionVersion = 9,
				admissionOrdinal = 99L,
				effectKind = "different",
				payloadVersion = 2,
				payload = byteArrayOf(9),
				createdAtMs = 1L,
				deliveredAtMs = null,
			),
		)
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.drainThrough(1L) shouldBe CoordinatorDrainResult.Complete(1L, 1)

		database.sourceProjectionStateDao().checkpoint(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.contiguousAdmissionOrdinal shouldBe 1L
		database.sourceProjectionStateDao().failure(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
			1L,
		)?.terminal shouldBe true
		database.sourceProjectionStateDao().registration(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.retentionRequired shouldBe false
		database.sourceProjectionStateDao().minimumRequiredCheckpoint() shouldBe null
		database.sourceProjectionStateDao().pendingOutbox(10).single().also { foreign ->
			foreign.projectionId shouldBe "foreign-owner"
			foreign.admissionOrdinal shouldBe 99L
			foreign.payload.toList() shouldBe listOf(9.toByte())
			foreign.deliveredAtMs shouldBe null
		}
	}

	private fun activityWal(codec: DefaultSourcePayloadCodec): SourceEventWalEntity {
		val encoded = codec.encode(ActivityRecognitionPayload(1, 80, 1_000L), payloadVersion = 1)
		val row = SourceEventWalEntity(
			eventId = "first-real-callback",
			providerDedupKey = "first-real-callback",
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = SourceKind.ACTIVITY.stableCode,
			sourceInstanceId = "activity-provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "activity-config",
			authorizationRevision = 7L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			authorizationFingerprint = "activity-control",
			sourceSequence = 1L,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = "boot-1",
			observedElapsedNanos = 1_000L,
			receivedElapsedNanos = 1_010L,
			wallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 1L,
			activityAutomationEpoch = 17L,
			acquiredAtMs = 1_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			createdAtMs = 1_000L,
		)
		return row.copy(integrityIdentity = row.calculatedIntegrityIdentity())
	}

	private fun activityEvent(ordinal: Long, collectedDataEpoch: Long = 1L) = AdmittedSourceEvent(
		eventId = SourceEventId("activity-$ordinal"),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = "activity-$ordinal",
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId("activity-provider"),
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "activity-config",
			authorizationRevision = 7L,
			registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			registrationEligibilityFingerprint = "activity-control",
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot-1",
			observedElapsedRealtimeNanos = ordinal * 1_000L,
			receivedElapsedRealtimeNanos = ordinal * 1_000L + 10L,
			wallTimeMs = ordinal * 1_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = collectedDataEpoch,
			activityAutomationEpoch = 17L,
			acquiredAtMs = ordinal * 1_000L,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = ActivityRecognitionPayload(1, 80, ordinal * 1_000L),
		),
	)
}
