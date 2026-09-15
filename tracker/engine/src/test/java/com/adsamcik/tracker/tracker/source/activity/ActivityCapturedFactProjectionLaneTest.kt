package com.adsamcik.tracker.tracker.source.activity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionEventIdentityRow
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass", "TooManyFunctions")
class ActivityCapturedFactProjectionLaneTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(activityOwner())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `production catalog keeps the captured Activity writer dormant`() = runTest {
		installLane()
		val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
		val writer = mockk<ActivityCapturedFactWriter>()

		ActivityCapturedFactProjectionLane(
			database,
			adapter,
			writer,
		).drainThrough(1L) shouldBe ActivityCapturedFactDrainResult.Inactive(
			ActivityCapturedLaneInactiveReason.BINARY_BINDING_NOT_ENABLED,
		)

		coVerify(exactly = 0) { adapter.admit(any()) }
		coVerify(exactly = 0) { writer.write(any()) }
	}

	@Test
	fun `explicit canonical lane waits for settlement then commits one terminal window and cursor`() =
		runTest {
			installLane()
			insertTerminalSession()
			insertWalIdentity()
			val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
			val writer = mockk<ActivityCapturedFactWriter>()
			coEvery { adapter.admit(EVENT_ID) } returns
				ActivityCapturedWalAdmissionResult.Unavailable(
					ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
				)
			val subject = lane(adapter, writer)

			subject.drainThrough(1L) shouldBe ActivityCapturedFactDrainResult.Deferred(
				lastCompletedOrdinal = 0L,
				blockedOrdinal = 1L,
				reason = ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW.name,
			)
			activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
			coVerify(exactly = 0) { writer.write(any()) }

			coEvery { adapter.admit(EVENT_ID) } returns admitted()
			coEvery { writer.write(any()) } returns ActivityCapturedWriteResult.Applied(
				logicalWindowId = "activity-window",
				semanticRevision = 1L,
				cursorRevision = 1L,
			)

			subject.drainThrough(1L) shouldBe ActivityCapturedFactDrainResult.Complete(
				lastCompletedOrdinal = 1L,
				windowsApplied = 1,
				eventsValidated = 1,
			)
			activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
			coVerify(exactly = 1) { writer.write(any()) }
		}

	@Test
	fun `replacement service runs retain separate physical window ownership`() = runTest {
		val replacementEventId = SourceEventId("activity-event-2")
		installLane()
		insertTerminalSession(finalAdmissionOrdinal = 2L)
		insertWalIdentity()
		insertWalIdentity(
			ordinal = 2L,
			eventId = replacementEventId,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
		)
		val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
		val writer = mockk<ActivityCapturedFactWriter>()
		coEvery { adapter.admit(EVENT_ID) } returns admitted()
		coEvery { adapter.admit(replacementEventId) } returns
			admitted(2L, replacementEventId, REPLACEMENT_SERVICE_RUN_ID)
		coEvery { writer.write(any()) } returnsMany listOf(
			ActivityCapturedWriteResult.Applied("window-1", 1L, 1L),
			ActivityCapturedWriteResult.Applied("window-2", 1L, 1L),
		)

		lane(adapter, writer).drainThrough(2L) shouldBe
			ActivityCapturedFactDrainResult.Complete(2L, 2, 2)

		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L
		coVerify(exactly = 2) { writer.write(any()) }
	}

	@Test
	fun `later oversized payload poisons its own ordinal without writing the valid prefix`() = runTest {
		installLane()
		insertTerminalSession(finalAdmissionOrdinal = 2L)
		insertWalIdentity()
		val oversizedId = SourceEventId("activity-event-oversized")
		insertWalIdentity(2L, oversizedId, payload = ByteArray(22))
		val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
		val writer = mockk<ActivityCapturedFactWriter>()
		coEvery { adapter.admit(EVENT_ID) } returns admitted()
		coEvery { adapter.admit(oversizedId) } returns ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE,
		)

		lane(adapter, writer).drainThrough(2L) shouldBe
			ActivityCapturedFactDrainResult.Failed(
				lastCompletedOrdinal = 0L,
				failedOrdinal = 2L,
				failureCode = "ACTIVITY_WAL_DELIVERY_TOO_LARGE",
				terminal = true,
			)

		activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION,
			2L,
		)?.terminal shouldBe true
		coVerify(exactly = 0) { writer.write(any()) }
	}

	@Test
	fun `control-only Activity advances its source cursor without invoking captured persistence`() =
		runTest {
			installLane()
			insertWalIdentity()
			val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
			val writer = mockk<ActivityCapturedFactWriter>()
			coEvery { adapter.admit(EVENT_ID) } returns ActivityCapturedWalAdmissionResult.Rejected(
				ActivityCapturedWalAdmissionRejection.CONTROL_ONLY,
			)

			lane(adapter, writer).drainThrough(1L) shouldBe
				ActivityCapturedFactDrainResult.Complete(1L, 0, 1)

			activeLane()?.contiguousAdmissionOrdinal shouldBe 1L
			coVerify(exactly = 0) { writer.write(any()) }
		}

	@Test
	fun `committed deletion releases a later oversized poison on cleanup retry`() = runTest {
		installLane()
		insertTerminalSession(finalAdmissionOrdinal = 2L)
		insertWalIdentity()
		val oversizedId = SourceEventId("activity-event-oversized")
		insertWalIdentity(2L, oversizedId, payload = ByteArray(22))
		val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
		val writer = mockk<ActivityCapturedFactWriter>()
		coEvery { adapter.admit(EVENT_ID) } returns admitted()
		coEvery { adapter.admit(oversizedId) } returns ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE,
		)
		val subject = lane(adapter, writer)
		subject.drainThrough(2L) shouldBe ActivityCapturedFactDrainResult.Failed(
			0L,
			2L,
			"ACTIVITY_WAL_DELIVERY_TOO_LARGE",
			terminal = true,
		)

		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = COLLECTED_DATA_EPOCH + 1L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 2L,
			updatedAtMs = 2_000L,
		) shouldBe 1
		coEvery { adapter.admit(EVENT_ID) } returns ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE,
		)
		coEvery { adapter.admit(oversizedId) } returns ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE,
		)

		subject.drainThrough(2L) shouldBe ActivityCapturedFactDrainResult.Complete(
			lastCompletedOrdinal = 2L,
			windowsApplied = 0,
			eventsValidated = 2,
		)
		activeLane()?.contiguousAdmissionOrdinal shouldBe 2L
		database.sourceProjectionStateDao().failure(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION,
			2L,
		) shouldBe null
		coVerify(exactly = 0) { writer.write(any()) }
	}

	@Test
	fun `writer cancellation rolls back the Activity cursor and never creates a failure`() = runTest {
		installLane()
		insertTerminalSession()
		insertWalIdentity()
		val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
		val writer = mockk<ActivityCapturedFactWriter>()
		coEvery { adapter.admit(EVENT_ID) } returns admitted()
		coEvery { writer.write(any()) } throws CancellationException("cancel writer")

		shouldThrow<CancellationException> { lane(adapter, writer).drainThrough(1L) }

		activeLane()?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION,
			1L,
		) shouldBe null
	}

	@Test
	fun `bounded preflight reports the exact first overflow ordinal`() {
		val preflight = listOf(
			SourceProjectionEventIdentityRow(EVENT_ID.value, 1L),
			SourceProjectionEventIdentityRow("second", 2L),
			SourceProjectionEventIdentityRow("overflow", 3L),
		)

		preflight.firstOverflowOrdinal(2) shouldBe 3L
		preflight.firstOverflowOrdinal(3) shouldBe null
	}

	private fun lane(
		adapter: ActivityCapturedWalAdmissionAdapter,
		writer: ActivityCapturedFactWriter,
	) = ActivityCapturedFactProjectionLane(
		database,
		adapter,
		writer,
		ExecutableSourceLaneCatalog.explicit(activityBinding()),
	)

	private suspend fun installLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				bindingGeneration = ActivityCapturedFactProjectionLane.BINDING_GENERATION,
				projectionId = ActivityCapturedFactProjectionLane.WRITER_ID,
				projectionVersion = ActivityCapturedFactProjectionLane.WRITER_VERSION,
				captureModeMask = ActivityCapturedFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private suspend fun insertTerminalSession(finalAdmissionOrdinal: Long = 1L) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = 1_000L,
				startedElapsedNanos = WINDOW_START_NANOS,
				cutoffAtMs = 2_000L,
				cutoffElapsedNanos = WINDOW_END_NANOS,
				completedAtMs = 2_000L,
				finalAdmissionOrdinal = finalAdmissionOrdinal,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
	}

	private suspend fun insertWalIdentity(
		ordinal: Long = 1L,
		eventId: SourceEventId = EVENT_ID,
		serviceRunId: String = SERVICE_RUN_ID,
		payload: ByteArray = byteArrayOf(1),
	) {
		val raw = SourceEventWalEntity(
			admissionOrdinal = ordinal,
			eventId = eventId.value,
			providerDedupKey = "activity-dedup-$ordinal",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = serviceRunId,
			sourceKind = SourceKind.ACTIVITY.stableCode,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = plan().physicalConfigurationFingerprint(),
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "activity-capture",
			sourceSequence = ordinal,
			configRevision = PLAN_REVISION,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = OBSERVATION_NANOS,
			receivedElapsedNanos = OBSERVATION_NANOS + 10L,
			wallTimeMs = 1_100L,
			wallTimeUncertaintyMs = 5L,
			capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = 1_100L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = "test-only",
			integrityIdentity = "test-only",
			createdAtMs = 1_100L,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(raw) shouldBe ordinal
	}

	private fun admitted(
		ordinal: Long = 1L,
		eventId: SourceEventId = EVENT_ID,
		serviceRunId: String = SERVICE_RUN_ID,
	): ActivityCapturedWalAdmissionResult.Admitted {
		val authority = captureAuthority(serviceRunId)
		val reference = ActivityCapturedObservationReference(
			sourceEventId = eventId,
			admissionOrdinal = ordinal,
			sourceSequence = ordinal,
			providerElapsedRealtimeNanos = OBSERVATION_NANOS,
			receivedElapsedRealtimeNanos = OBSERVATION_NANOS + 10L,
			observationKind = ActivityCapturedObservationKind.TRANSITION,
			observedActivity = CapturedActivityType.WALKING,
			transitionChange = ActivityTransitionChange.ENTER,
			confidencePercent = null,
			coverageEndExclusiveElapsedRealtimeNanos = null,
		)
		return ActivityCapturedWalAdmissionResult.Admitted(
			observation = ActivityCapturedObservation.Transition(
				reference = reference,
				authority = authority.captureAuthority,
				activity = CapturedActivityType.WALKING,
				observedWallTimeMs = 1_100L,
				wallTimeUncertaintyMs = 5L,
				change = ActivityTransitionChange.ENTER,
			),
			acquisitionAuthority = authority,
			settledWindow = ActivityCapturedSettledWindow(
				interval = ActivityProviderTimeInterval(WINDOW_START_NANOS, WINDOW_END_NANOS),
				sessionSegmentId = 42L,
				storedZoneId = "Europe/Prague",
				manifestRevision = MANIFEST_REVISION,
			),
			deliveryIdentity = SourceDeliveryIdentity("0".repeat(64)),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
		)
	}

	private fun captureAuthority(
		serviceRunId: String = SERVICE_RUN_ID,
	): ActivityCaptureAcquisitionAuthority {
		val plan = plan()
		val encoded = SourcePlanCodec().encode(plan)
		val interval = ActivityProviderTimeInterval(WINDOW_START_NANOS, WINDOW_END_NANOS)
		val authority = ActivityCaptureAuthority(
			logicalTrackingId = LogicalTrackingId(LOGICAL_TRACKING_ID),
			serviceRunId = ServiceRunId(serviceRunId),
			sourceInstanceId = SourceInstanceId(SOURCE_INSTANCE_ID),
			registrationGeneration = REGISTRATION_GENERATION,
			configurationRevision = PLAN_REVISION,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationFingerprint = "activity-capture",
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			clockDomainId = BOOT_ID,
			temporalAuthority = ActivityCaptureTemporalAuthority(interval, interval, interval),
		)
		return ActivityCaptureAcquisitionAuthority(
			authority,
			ActivityHistoricalAcquisitionConfiguration.fromSerializedPlan(
				identity = ActivityAcquisitionConfigurationIdentity(
					authority.sourceInstanceId,
					authority.registrationGeneration,
					authority.configurationRevision,
					authority.physicalConfigurationFingerprint,
					authority.authorizationRevision,
					authority.authorizationFingerprint,
				),
				providerAcceptance = interval,
				authorizationEffect = interval,
				sessionRunEffect = interval,
				desiredPlanPayloadVersion = 1,
				desiredPlanPayloadChecksum = encoded.checksum,
				desiredPlanPayload = encoded.bytes,
			),
		)
	}

	private fun plan() = ActivityPlan(
		revision = PLAN_REVISION,
		mode = ActivityMode.TRANSITIONS_ONLY,
		desiredDetectionLatencyMs = 30_000L,
		confidenceThresholdPercent = 70,
		transitionTypes = setOf(StableActivityTypeCode.WALKING),
	)

	private fun activityBinding() = ExecutableSourceLaneBinding(
		source = SourceKind.ACTIVITY,
		bindingGeneration = ActivityCapturedFactProjectionLane.BINDING_GENERATION,
		projectionId = ActivityCapturedFactProjectionLane.WRITER_ID,
		projectionVersion = ActivityCapturedFactProjectionLane.WRITER_VERSION,
		captureModes = setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
	)

	private fun activityOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		owner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
		ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		updatedAtMs = 1_000L,
	)

	private suspend fun activeLane() = database.sourceProjectionStateDao()
		.activeProductLane(SourceKind.ACTIVITY.stableCode)

	private companion object {
		val EVENT_ID = SourceEventId("activity-event-1")
		const val LOGICAL_TRACKING_ID = "activity-logical"
		const val SERVICE_RUN_ID = "activity-run"
		const val REPLACEMENT_SERVICE_RUN_ID = "activity-run-replacement"
		const val SOURCE_INSTANCE_ID = "activity-provider"
		const val BOOT_ID = "boot-activity"
		const val PLAN_REVISION = 5L
		const val POLICY_REVISION = 7L
		const val CONSENT_EPOCH = 11L
		const val MANIFEST_REVISION = 13L
		const val LEASE_GENERATION = 17L
		const val REGISTRATION_GENERATION = 19L
		const val AUTHORIZATION_REVISION = 23L
		const val COLLECTED_DATA_EPOCH = 29L
		const val WINDOW_START_NANOS = 100L
		const val OBSERVATION_NANOS = 200L
		const val WINDOW_END_NANOS = 1_000L
	}
}
