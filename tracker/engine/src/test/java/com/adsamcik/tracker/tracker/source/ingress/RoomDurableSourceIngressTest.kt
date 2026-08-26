package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.coordinator.SourceRecoveryResult
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.runtime.RuntimeCheckpointLifecycle
import com.adsamcik.tracker.tracker.source.runtime.RuntimeAdmissionSnapshot
import com.adsamcik.tracker.tracker.source.runtime.SensorAdmissionCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.SensorRuntimeCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.decodeSensorRuntimeCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.encodeSensorRuntimeCheckpoint
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class RoomDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: FakeLifecycleStore
	private lateinit var subject: RoomDurableSourceIngress
	private val startupGate = FakeTrackingStartupGate()
	private var testPolicyRevision = 0L
	private var testCaptureEpoch = 0L
	private var testAmbientEpoch = 0L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		lifecycle = FakeLifecycleStore(CollectedDataLifecycleSnapshot(0L, null))
		subject = RoomDurableSourceIngress(
			database,
			lifecycle,
			DefaultSourcePayloadCodec(),
			TEST_EXECUTABLE_LANE_CATALOG,
			Provider { startupGate },
		)
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot", 40L, 40L)
		}
		val initial = policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val ambient = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_AMBIENT_ELIGIBILITY",
		)
		testPolicyRevision = ambient.revision
		testCaptureEpoch = requireNotNull(ambient[TrackingSourceComponent.ACTIVITY].captureConsentEpoch)
		testAmbientEpoch = requireNotNull(ambient[TrackingSourceComponent.ACTIVITY].ambientConsentEpoch)
		installCaptureLane(SourceKind.ACTIVITY)
		installCaptureLane(SourceKind.STEPS)
		RoomTrackingRolloutStateStore(database, TEST_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.eventCanonical(
				sources = setOf(SourceKind.ACTIVITY, SourceKind.STEPS),
				captureModes = setOf(SourceKind.ACTIVITY, SourceKind.STEPS).associateWith {
					CaptureReachabilityMode.entries.toSet()
				},
			),
			updatedAtMs = 1L,
		)
		installRegistrationGeneration()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admission is durable ordered replayable and idempotent by source sequence`() = runTest {
		val candidate = candidate(sequence = 1L)

		val admitted = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val duplicate = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Duplicate>()

		duplicate.eventId shouldBe admitted.eventId
		duplicate.existingAdmissionOrdinal shouldBe admitted.admissionOrdinal
		subject.committedBatch(0L, 1).single().eventId shouldBe admitted.eventId
	}

	@Test
	fun `delayed pre cutoff capture is rejected after the durable lane admission fence`() = runTest {
		val candidate = candidate(
			sequence = 1L,
			observedElapsedNanos = 100L,
			receivedElapsedNanos = 200L,
		)
		database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			bindingGeneration = 1L,
			projectionId = "ingress-test-activity",
			projectionVersion = 1,
			cutoffOrdinal = 0L,
			updatedAtMs = 150L,
		) shouldBe 1

		subject.admit(candidate) shouldBe AdmissionResult.PermanentFailure(
			AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `executable inert shadow cannot admit under retained provider authorization`() = runTest {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		installCaptureLane(
			source = SourceKind.ACTIVITY,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		)
		RoomTrackingRolloutStateStore(database, TEST_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.contained(revision = 2L),
			updatedAtMs = 2L,
		)

		subject.admit(candidate(sequence = 1L)) shouldBe AdmissionResult.PermanentFailure(
			AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `capture fence preserves idempotent replay of a fact admitted before containment`() = runTest {
		val candidate = candidate(sequence = 1L)
		val first = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Admitted>()
		database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			bindingGeneration = 1L,
			projectionId = "ingress-test-activity",
			projectionVersion = 1,
			cutoffOrdinal = first.admissionOrdinal,
			updatedAtMs = 150L,
		) shouldBe 1

		val replay = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Duplicate>()

		replay.existingAdmissionOrdinal shouldBe first.admissionOrdinal
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `conflicting active product lane closes capture admission`() = runTest {
		installCaptureLane(
			source = SourceKind.ACTIVITY,
			bindingGeneration = 2L,
			projectionId = "conflicting-activity-writer",
		)

		subject.admit(candidate(sequence = 1L)) shouldBe AdmissionResult.PermanentFailure(
			AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `unknown active product stage closes capture admission`() = runTest {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		installCaptureLane(
			source = SourceKind.ACTIVITY,
			productStage = "UNKNOWN_STAGE",
		)

		subject.admit(candidate(sequence = 1L)) shouldBe AdmissionResult.PermanentFailure(
			AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `recognized active lane with unknown writer binding closes capture admission`() = runTest {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		installCaptureLane(
			source = SourceKind.ACTIVITY,
			bindingGeneration = 9L,
			projectionId = "unknown-activity-writer",
		)
		database.sourceProjectionStateDao()
			.isCaptureAdmissionOpen(SourceKind.ACTIVITY.stableCode) shouldBe true

		subject.admit(candidate(sequence = 1L)) shouldBe AdmissionResult.PermanentFailure(
			AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `exact duplicate retry heals a missing sensor checkpoint with the existing ordinal`() = runTest {
		installStepRegistrationGeneration()
		val evidence = stepCandidate(
			startNanos = 100L,
			endNanos = 100L,
			sourceSequence = 1L,
			providerDedupKey = "step-provider-1",
		)
		val admitted = subject.admit(evidence).shouldBeInstanceOf<AdmissionResult.Admitted>()
		database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			STEP_OWNER_SCOPE,
		) shouldBe null

		val duplicate = subject.admit(evidence, stepCheckpoint(providerSequenceThrough = 2L))
			.shouldBeInstanceOf<AdmissionResult.Duplicate>()
		val state = requireNotNull(database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			STEP_OWNER_SCOPE,
		))
		val checkpoint = requireNotNull(decodeSensorRuntimeCheckpoint(state, 1))

		duplicate.existingAdmissionOrdinal shouldBe admitted.admissionOrdinal
		state.lastProviderSequence shouldBe 2L
		state.lastAdmissionOrdinal shouldBe admitted.admissionOrdinal
		checkpoint.metrics.lastDurablyAdmittedSequence shouldBe 2L
		checkpoint.metrics.lastAdmissionOrdinal shouldBe admitted.admissionOrdinal
		checkpoint.componentPayload.toList() shouldBe listOf<Byte>(2)
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `sensor checkpoint write failure rolls back a newly admitted WAL fact`() = runTest {
		installStepRegistrationGeneration()
		val evidence = stepCandidate(
			startNanos = 100L,
			endNanos = 100L,
			sourceSequence = 1L,
			providerDedupKey = "step-provider-rollback",
		)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER fail_sensor_checkpoint BEFORE INSERT ON source_runtime_state " +
				"BEGIN SELECT RAISE(ABORT, 'sensor checkpoint fault'); END",
		)

		subject.admit(evidence, stepCheckpoint(providerSequenceThrough = 2L)) shouldBe
			AdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE)

		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			STEP_OWNER_SCOPE,
		) shouldBe null

		database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_sensor_checkpoint")
		subject.admit(evidence, stepCheckpoint(providerSequenceThrough = 2L))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `delayed duplicate checkpoint cannot regress a newer sensor high water`() = runTest {
		installStepRegistrationGeneration()
		val first = stepCandidate(
			startNanos = 100L,
			endNanos = 100L,
			sourceSequence = 1L,
			providerDedupKey = "step-provider-first",
		)
		val second = stepCandidate(
			startNanos = 100L,
			endNanos = 101L,
			observedNanos = 101L,
			sourceSequence = 2L,
			providerDedupKey = "step-provider-second",
			lastProviderSequence = 3L,
		)
		subject.admit(first, stepCheckpoint(2L, componentByte = 2))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()
		val secondAdmission = subject.admit(second, stepCheckpoint(3L, componentByte = 3, updatedAtMs = 101L))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		subject.admit(first, stepCheckpoint(2L, componentByte = 2))
			.shouldBeInstanceOf<AdmissionResult.Duplicate>()
		val state = requireNotNull(database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			STEP_OWNER_SCOPE,
		))
		val checkpoint = requireNotNull(decodeSensorRuntimeCheckpoint(state, 1))

		state.lastProviderSequence shouldBe 3L
		state.lastAdmissionOrdinal shouldBe secondAdmission.admissionOrdinal
		checkpoint.componentPayload.toList() shouldBe listOf<Byte>(3)
	}

	@Test
	fun `pre revoke duplicate heals metrics without replacing later terminal sensor state`() = runTest {
		installStepRegistrationGeneration()
		val evidence = stepCandidate(
			startNanos = 100L,
			endNanos = 100L,
			sourceSequence = 1L,
			providerDedupKey = "step-provider-pre-revoke",
		)
		val admitted = subject.admit(evidence).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val terminal = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = null,
				lastAdmissionOrdinal = null,
				failedAdmissionCount = 1L,
				unresolvedSequenceStart = 3L,
				unresolvedSequenceEndInclusive = 3L,
				gapClassifications = emptySet(),
			),
			componentStateVersion = 1,
			componentPayload = byteArrayOf(9),
			causalOrderElapsedRealtimeNanos = 200L,
		)
		database.sourceRuntimeStateDao().save(
			SourceRuntimeStateEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				ownerScope = STEP_OWNER_SCOPE,
				sourceInstanceId = "step-instance",
				clockDomainId = "boot",
				registrationGeneration = 1L,
				lastProviderSequence = 2L,
				lastAdmittedSourceSequence = null,
				lastAdmissionOrdinal = null,
				stateVersion = 3,
				payload = encodeSensorRuntimeCheckpoint(terminal),
				updatedAtMs = 100L,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.STEPS.stableCode,
				1L,
				2L,
				emptyList(),
				"boot",
				101L,
				101L,
			),
		)

		val duplicate = subject.admit(
			evidence,
			stepCheckpoint(providerSequenceThrough = 2L, componentByte = 2, updatedAtMs = 10_000L),
		).shouldBeInstanceOf<AdmissionResult.Duplicate>()
		val state = requireNotNull(database.sourceRuntimeStateDao().get(
			SourceKind.STEPS.stableCode,
			STEP_OWNER_SCOPE,
		))
		val checkpoint = requireNotNull(decodeSensorRuntimeCheckpoint(state, 1))

		duplicate.existingAdmissionOrdinal shouldBe admitted.admissionOrdinal
		checkpoint.lifecycle shouldBe RuntimeCheckpointLifecycle.QUIESCED
		checkpoint.componentPayload.toList() shouldBe listOf<Byte>(9)
		state.lastAdmittedSourceSequence shouldBe 2L
		state.lastAdmissionOrdinal shouldBe admitted.admissionOrdinal
		checkpoint.metrics.lastDurablyAdmittedSequence shouldBe state.lastAdmittedSourceSequence
		checkpoint.metrics.lastAdmissionOrdinal shouldBe state.lastAdmissionOrdinal
		checkpoint.metrics.unresolvedSequenceStart shouldBe 3L
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `same source sequence with different evidence is rejected as collision`() = runTest {
		subject.admit(candidate(sequence = 1L, activityType = 3))

		val collision = subject.admit(candidate(sequence = 1L, activityType = 7))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		collision.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
	}

	@Test
	fun `same source sequence with different observation envelope is rejected as collision`() = runTest {
		val original = candidate(sequence = 1L)
		subject.admit(original).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val collision = subject.admit(original.copy(wallTimeUncertaintyMs = 2L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		collision.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
	}

	@Test
	fun `valid but mutated payload bytes are rejected before decode`() = runTest {
		val admitted = subject.admit(candidate(sequence = 1L, activityType = 3))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()
		val replacement = DefaultSourcePayloadCodec().encode(
			ActivityTransitionPayload(7, 1, 100L),
			1,
		).bytes
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE event_id = ?",
			arrayOf(replacement, admitted.eventId.value),
		)

		val failure = shouldThrow<CorruptSourceEventException> {
			subject.committedBatch(0L, 1)
		}

		failure.admissionOrdinal shouldBe admitted.admissionOrdinal
		failure.failureCode shouldBe "RAW_PAYLOAD_INTEGRITY"
	}

	@Test
	fun `sessionless observation before its demand effective boundary is rejected`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_authorization SET effective_elapsed_realtime_nanos = 200 " +
				"WHERE source_kind = ${SourceKind.ACTIVITY.stableCode} AND registration_generation = 1",
		)

		val result = subject.admit(candidate(sequence = 1L, observedElapsedNanos = 100L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_SOURCE_POLICY
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `provider retry with a newly allocated local sequence remains idempotent`() = runTest {
		val admitted = subject.admit(candidate(sequence = 1L, providerDedupKey = "provider-event"))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		val duplicate = subject.admit(candidate(sequence = 2L, providerDedupKey = "provider-event"))
			.shouldBeInstanceOf<AdmissionResult.Duplicate>()

		duplicate.eventId shouldBe admitted.eventId
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `source delivery allocates contiguous sequences and commits every unit atomically`() = runTest {
		val delivery = delivery(
			candidate(sequence = 700L, activityType = 3),
			candidate(sequence = 900L, activityType = 7, observedElapsedNanos = 101L),
		)

		val admitted = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()

		admitted.units.map { it.unitIndex } shouldBe listOf(0, 1)
		val rows = database.sourceEventWalDao().eventsAfter(0L, 10)
		rows.map { it.sourceSequence } shouldBe listOf(0L, 1L)
		rows.map { it.deliveryIdentity } shouldBe listOf(delivery.identity.value, delivery.identity.value)
		rows.map { it.deliveryUnitIndex } shouldBe listOf(0, 1)
		rows.map { it.deliveryUnitCount } shouldBe listOf(2, 2)
		rows.map { it.providerDedupKey } shouldBe listOf(null, null)
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 2L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
	}

	@Test
	fun `Activity adapter persists the eligible sparse subset once through real Room admission`() = runTest {
		val recovery = mockk<SourcePipelineRecovery>()
		val motion = mockk<CollectionMotionController>(relaxed = true)
		val automationEpochAuthority = mockk<ActivityAutomationEpochAuthority>()
		coEvery { automationEpochAuthority.epochForCallbackAdmission() } returnsMany
			listOf(activityAutomationAuthority(17L), activityAutomationAuthority(18L))
		coEvery { recovery.drainCommittedWork() } returns SourceRecoveryResult(
			CoordinatorDrainResult.Complete(Long.MAX_VALUE, 0),
			0,
			0,
		)
		val adapter = RoomActivityRecognitionEventIngress(
			ActivitySourceDeliveryFactory(),
			Provider { startupGate },
			database,
			automationEpochAuthority,
			subject,
			subject,
			recovery,
			motion,
			ApplicationProvider.getApplicationContext<Application>(),
		)
		val delivery = ActivityRecognitionEvidenceBatch(
			receivedElapsedRealtimeNanos = 100L,
			receivedWallTimeMs = 1_000L,
			registrationIdentity = ActivityRegistrationIdentity(
				sourceInstanceId = "activity-instance",
				registrationGeneration = 1L,
				collectedDataEpoch = 0L,
				clockDomainId = "boot",
				physicalConfigurationFingerprint = TEST_PHYSICAL_CONFIG,
			),
			automaticRecognitionEligible = true,
			recognitions = listOf(
				ActivityRecognitionEvidence(DetectedActivityType.STILL, 90, 40L),
				ActivityRecognitionEvidence(DetectedActivityType.WALKING, 90, 60L),
			),
		)

		val admitted = adapter.admit(delivery)

		admitted.admittedCount shouldBe 1
		admitted.duplicateCount shouldBe 0
		admitted.durableSelection.recognitionIndexes shouldBe setOf(1)
		val persisted = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		persisted.observedElapsedNanos shouldBe 60L
		persisted.authorizationRevision shouldBe 1L
		persisted.logicalTrackingId shouldBe TEST_SESSION_ID
		persisted.activityAutomationEpoch shouldBe 17L

		val duplicate = adapter.admit(delivery)

		duplicate.admittedCount shouldBe 0
		duplicate.duplicateCount shouldBe 1
		duplicate.durableSelection.isEmpty shouldBe true
		database.sourceEventWalDao().countAll() shouldBe 1L
		database.sourceEventWalDao().eventsAfter(0L, 10).single()
			.activityAutomationEpoch shouldBe 17L
		verify(exactly = 1) { motion.onDurableEvidence(any()) }
	}

	@Test
	fun `source delivery encoding cancellation propagates without allocating or persisting`() = runTest {
		val cancellingSubject = RoomDurableSourceIngress(
			database,
			lifecycle,
			object : SourcePayloadCodec by DefaultSourcePayloadCodec() {
				override fun encode(
					payload: SourcePayload,
					payloadVersion: Int,
				): EncodedSourcePayload = throw CancellationException("test cancellation")
			},
			TEST_EXECUTABLE_LANE_CATALOG,
			Provider { startupGate },
		)

		shouldThrow<CancellationException> {
			cancellingSubject.admit(delivery(candidate(sequence = 700L, activityType = 3)))
		}

		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `closed startup gate rejects single and batch admission without touching WAL`() = runTest {
		startupGate.result = TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.LEGACY_V27,
			"RECOVERY_PENDING",
		)

		subject.admit(candidate(sequence = 900L)) shouldBe AdmissionResult.RetryableFailure(
			AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
		)
		subject.admit(delivery(candidate(sequence = 901L))) shouldBe
			DeliveryAdmissionResult.RetryableFailure(
				AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
			)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `startup generation closing after reconciliation rejects transactional admission`() = runTest {
		startupGate.allowReadyGeneration = false

		subject.admit(candidate(sequence = 902L)) shouldBe AdmissionResult.RetryableFailure(
			AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
		)
		subject.admit(delivery(candidate(sequence = 903L))) shouldBe
			DeliveryAdmissionResult.RetryableFailure(
				AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
			)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `future observed time rejects single and whole delivery before durable mutation`() = runTest {
		val future = candidate(
			sequence = 900L,
			observedElapsedNanos = 111L,
			receivedElapsedNanos = 110L,
		)

		subject.admit(future) shouldBe AdmissionResult.PermanentFailure(
			AdmissionFailureCode.INVALID_OBSERVED_TIME,
		)
		subject.admit(delivery(candidate(sequence = 901L), future)) shouldBe
			DeliveryAdmissionResult.PermanentFailure(
				AdmissionFailureCode.INVALID_OBSERVED_TIME,
			)

		startupGate.reconcileCalls shouldBe 0
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 0L
		database.activityAutomaticStartActionDao().current() shouldBe null
	}

	@Test
	fun `observed time equal to receipt remains admissible`() = runTest {
		val evidence = candidate(
			sequence = 902L,
			observedElapsedNanos = 110L,
			receivedElapsedNanos = 110L,
		)

		subject.admit(evidence).shouldBeInstanceOf<AdmissionResult.Admitted>()

		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `invalid source intervals and clock domains are rejected before durable mutation`() = runTest {
		val invalidIntervals = listOf(
			stepCandidate(startNanos = -1L, endNanos = 100L),
			stepCandidate(startNanos = 101L, endNanos = 100L),
			stepCandidate(startNanos = 100L, endNanos = 111L, receivedNanos = 110L),
			stepCandidate(startNanos = 100L, endNanos = 100L, observedNanos = 101L),
			stepCandidate(startNanos = 100L, endNanos = 100L, payloadClockDomainId = "other-boot"),
		)

		invalidIntervals.forEach { evidence ->
			subject.admit(evidence) shouldBe AdmissionResult.PermanentFailure(
				AdmissionFailureCode.INVALID_OBSERVED_TIME,
			)
		}

		startupGate.reconcileCalls shouldBe 0
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		database.activityAutomaticStartActionDao().current() shouldBe null
	}

	@Test
	fun `delivery interval model rejects negative start and start after end`() = runTest {
		val evidence = candidate(sequence = 903L, observedElapsedNanos = 100L)

		shouldThrow<IllegalArgumentException> {
			SourceDeliveryUnit(
				unitIndex = 0,
				evidence = evidence,
				observedIntervalStartElapsedRealtimeNanos = -1L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			SourceDeliveryUnit(
				unitIndex = 0,
				evidence = evidence,
				observedIntervalStartElapsedRealtimeNanos = 101L,
			)
		}

		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `exact source delivery replay ignores process local envelope and allocates no sequence`() = runTest {
		val initial = delivery(
			candidate(sequence = 10L, activityType = 3),
			candidate(sequence = 11L, activityType = 7, observedElapsedNanos = 101L),
		)
		val admitted = subject.admit(initial).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		val replay = initial.copy(
			units = initial.units.mapIndexed { index, unit ->
				unit.copy(
					evidence = unit.evidence.copy(
						providerDedupKey = "must-not-be-indexed-$index",
						sourceInstanceId = SourceInstanceId("new-process-instance"),
						registrationGeneration = 99L,
						physicalConfigurationFingerprint = "new-physical-generation",
						authorizationRevision = 99L,
						registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
						registrationEligibilityFingerprint = "new-authorization",
						sourceSequence = 9_000L + index,
						receivedElapsedRealtimeNanos = 50_000L + index,
						wallTimeMs = 75_000L + index,
						wallTimeUncertaintyMs = 500L + index,
						acquiredAtMs = 75_000L + index,
						quality = SourceQuality(
							confidence = 0.25f,
							flags = setOf(SourceQualityFlag.BATCHED, SourceQualityFlag.CLOCK_UNCERTAIN),
						),
					),
				)
			},
		)

		val duplicate = subject.admit(replay).shouldBeInstanceOf<DeliveryAdmissionResult.Duplicate>()

		duplicate.units shouldBe admitted.units
		database.sourceEventWalDao().countAll() shouldBe 2L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 2L
	}

	@Test
	fun `delayed callback from historically valid replaced generation uses shared sequence space`() = runTest {
		database.sourceBrokerDao().finishRegistration(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = 1L,
			sourceInstanceId = "activity-instance",
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 200L,
			failureCode = "REPLACED",
		) shouldBe 1
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = "activity-instance",
				ownerScope = SOURCE_OWNER_SCOPE,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				clockDomainId = "boot",
				physicalConfigurationFingerprint = CONTROL_PHYSICAL_CONFIG,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 200L,
				reservedElapsedRealtimeNanos = 200L,
				acceptedAtMs = 200L,
				acceptedElapsedRealtimeNanos = 200L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		val current = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.ACTIVITY.stableCode, SOURCE_OWNER_SCOPE),
		)
		database.sourceRegistrationStateDao().replace(
			current.copy(registrationGeneration = 2L, updatedAtMs = 200L),
		)

		val admitted = subject.admit(delivery(candidate(sequence = 900L, observedElapsedNanos = 100L)))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()

		admitted.units.single().unitIndex shouldBe 0
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.registrationGeneration shouldBe 1L
		stored.sourceSequence shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.let { state ->
			state.registrationGeneration shouldBe 2L
			state.nextSequence shouldBe 1L
		}
	}

	@Test
	fun `reused source delivery identity with changed native payload is rejected`() = runTest {
		val initial = delivery(candidate(sequence = 1L, activityType = 3))
		subject.admit(initial).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		val changed = initial.copy(
			units = listOf(
				initial.units.single().copy(
					evidence = candidate(sequence = 999L, activityType = 7),
				),
			),
		)

		val collision = subject.admit(changed)
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		collision.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
		database.sourceEventWalDao().countAll() shouldBe 1L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 1L
	}

	@Test
	fun `delivery split into authorization homogeneous units commits mixed revisions atomically`() = runTest {
		val prior = database.sourceBrokerDao().latestAuthorization(SourceKind.ACTIVITY.stableCode, 1L)
		database.sourceBrokerDao().insertAuthorizations(
			prior.map { row ->
				row.copy(
					authorizationRevision = 2L,
					effectiveElapsedRealtimeNanos = 101L,
					effectiveWallTimeMs = 101L,
				)
			},
		)
		val delivery = delivery(
			candidate(sequence = 1L, activityType = 3, observedElapsedNanos = 100L),
			candidate(sequence = 2L, activityType = 7, observedElapsedNanos = 101L),
		)

		val result = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()

		result.units.map { it.unitIndex } shouldBe listOf(0, 1)
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10)
		stored.map { it.authorizationRevision } shouldBe listOf(1L, 2L)
		stored.map { it.sourceSequence } shouldBe listOf(0L, 1L)
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 2L
	}

	@Test
	fun `single delivery unit spanning authorization boundary requires source splitting`() = runTest {
		val prior = database.sourceBrokerDao().latestAuthorization(SourceKind.ACTIVITY.stableCode, 1L)
		database.sourceBrokerDao().insertAuthorizations(
			prior.map { row ->
				row.copy(
					authorizationRevision = 2L,
					effectiveElapsedRealtimeNanos = 101L,
					effectiveWallTimeMs = 101L,
				)
			},
		)
		val candidate = candidate(sequence = 1L, activityType = 3, observedElapsedNanos = 102L)
		val delivery = SourceDeliveryCandidate(
			identity = sourceDeliveryIdentity("provider-delivery-1".encodeToByteArray()),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = candidate,
					observedIntervalStartElapsedRealtimeNanos = 100L,
				),
			),
		)

		val result = subject.admit(delivery)
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 0L
	}

	@Test
	fun `single delivery unit spanning physical retirement requires source splitting`() = runTest {
		database.sourceBrokerDao().finishRegistration(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = 1L,
			sourceInstanceId = "activity-instance",
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 101L,
			retiredElapsedRealtimeNanos = 101L,
			failureCode = "TEST_RETIREMENT",
		) shouldBe 1
		val candidate = candidate(sequence = 1L, activityType = 3, observedElapsedNanos = 102L)
		val delivery = SourceDeliveryCandidate(
			identity = sourceDeliveryIdentity("provider-delivery-1".encodeToByteArray()),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = candidate,
					observedIntervalStartElapsedRealtimeNanos = 100L,
				),
			),
		)

		val result = subject.admit(delivery)
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 0L
	}

	@Test
	fun `delivery keeps pre revoke unit and omits denied unit with stable replay gap`() = runTest {
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				2L,
				emptyList(),
				"boot",
				101L,
				101L,
			),
		)
		val delivery = delivery(
			candidate(sequence = 1L, activityType = 3, observedElapsedNanos = 100L),
			candidate(sequence = 2L, activityType = 7, observedElapsedNanos = 101L),
		)

		val admitted = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		val duplicate = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Duplicate>()

		admitted.units.map { it.unitIndex } shouldBe listOf(0)
		duplicate.units shouldBe admitted.units
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.deliveryUnitIndex shouldBe 0
		stored.deliveryUnitCount shouldBe 2
		stored.authorizationRevision shouldBe 1L
		stored.sourceSequence shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 1L
	}

	@Test
	fun `delivery keeps post retention floor unit and replays its original sparse index`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(0L, 150L))
		database.sourceEvidenceStateDao().updateLifecycle(0L, 150L, 150L) shouldBe 1
		val delivery = delivery(
			candidate(sequence = 1L, activityType = 3, acquiredAtMs = 100L),
			candidate(sequence = 2L, activityType = 7, acquiredAtMs = 200L, observedElapsedNanos = 101L),
		)

		val admitted = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		val duplicate = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Duplicate>()

		admitted.units.map { it.unitIndex } shouldBe listOf(1)
		duplicate.units shouldBe admitted.units
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.deliveryUnitIndex shouldBe 1
		stored.deliveryUnitCount shouldBe 2
		stored.acquiredAtMs shouldBe 200L
		stored.sourceSequence shouldBe 0L
		database.sourceRegistrationStateDao().get(
			SourceKind.ACTIVITY.stableCode,
			SOURCE_OWNER_SCOPE,
		)?.nextSequence shouldBe 1L
	}

	@Test
	fun `stale collected-data epoch is rejected before database admission`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(2L, 500L))

		val result = subject.admit(candidate(sequence = 1L, epoch = 1L, acquiredAtMs = 600L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `new epoch waits until Room deletion barrier catches up`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(1L, 500L))

		val result = subject.admit(candidate(sequence = 1L, epoch = 1L, acquiredAtMs = 600L))
			.shouldBeInstanceOf<AdmissionResult.RetryableFailure>()

		result.code shouldBe AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS
	}

	@Test
	fun `candidate session hint cannot override observed authorization identity`() = runTest {
		installSession()
		subject.admit(
			candidate(sequence = 1L).copy(logicalTrackingId = LogicalTrackingId("forged-session")),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.logicalTrackingId shouldBe LogicalTrackingId(TEST_SESSION_ID)
		event.serviceRunId shouldBe ServiceRunId(TEST_RUN_ID)
		event.sessionManifestRevision shouldBe 1L
	}

	@Test
	fun `candidate policy and service stamps are replaced by observed authorization`() = runTest {
		val authorization = installSession()
		subject.admit(
			candidate(sequence = 1L).copy(
				serviceRunId = ServiceRunId("forged-run"),
				sourcePolicyRevision = Long.MAX_VALUE,
				captureConsentEpoch = Long.MAX_VALUE,
				sessionManifestRevision = Long.MAX_VALUE,
				lifecycleLeaseGeneration = Long.MAX_VALUE,
			),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.serviceRunId shouldBe ServiceRunId(TEST_RUN_ID)
		event.sourcePolicyRevision shouldBe authorization.policyRevision
		event.captureConsentEpoch shouldBe authorization.captureConsentEpoch
		event.sessionManifestRevision shouldBe 1L
		event.lifecycleLeaseGeneration shouldBe 7L
	}

	@Test
	fun `stale physical registration configuration is rejected before wal admission`() = runTest {
		val result = subject.admit(
			candidate(sequence = 1L).copy(physicalConfigurationFingerprint = "stale-physical"),
		).shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_REGISTRATION_GENERATION
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `observation older than every authorized demand maximum age is not persisted`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET maximum_age_ms = 0 " +
				"WHERE demand_id IN ('ambient-demand', 'session-demand')",
		)

		val result = subject.admit(
			candidate(sequence = 1L, observedElapsedNanos = 100L, receivedElapsedNanos = 101L),
		).shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_OBSERVATION
		database.sourceEventWalDao().countAll() shouldBe 0L
		// Reconciliation or replay cannot resurrect a fact that was never fresh enough to admit.
		subject.admit(
			candidate(sequence = 1L, observedElapsedNanos = 100L, receivedElapsedNanos = 101L),
		) shouldBe AdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_OBSERVATION)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `stale session member cannot borrow fresher ambient eligibility`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET maximum_age_ms = 0 WHERE demand_id = 'session-demand'",
		)

		subject.admit(
			candidate(sequence = 1L, observedElapsedNanos = 100L, receivedElapsedNanos = 101L),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.logicalTrackingId shouldBe null
		event.serviceRunId shouldBe null
		event.sourcePolicyRevision shouldBe null
		event.captureConsentEpoch shouldBe null
		event.registrationPurposeEligibilityMask shouldBe SourceBrokerPurpose.MASK_AMBIENT_PRODUCT
		event.planAttribution shouldBe PlanAttribution.RECEIVE_TIME_ONLY
	}

	@Test
	fun `delivery persists fresh units while omitting stale units`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET maximum_age_ms = 0 " +
				"WHERE demand_id IN ('ambient-demand', 'session-demand')",
		)
		val delivery = delivery(
			candidate(sequence = 1L, observedElapsedNanos = 100L, receivedElapsedNanos = 101L),
			candidate(sequence = 2L, observedElapsedNanos = 101L, receivedElapsedNanos = 101L),
		)

		val admitted = subject.admit(delivery).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()

		admitted.units.map { unit -> unit.unitIndex } shouldBe listOf(1)
		val stored = subject.committedBatch(0L, 10).single().evidence
		stored.observedElapsedRealtimeNanos shouldBe 101L
	}

	@Test
	fun `delivery with no freshness qualified unit is not persisted`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET maximum_age_ms = 0 " +
				"WHERE demand_id IN ('ambient-demand', 'session-demand')",
		)
		val delivery = delivery(
			candidate(sequence = 1L, observedElapsedNanos = 100L, receivedElapsedNanos = 101L),
			candidate(sequence = 2L, observedElapsedNanos = 101L, receivedElapsedNanos = 102L),
		)

		subject.admit(delivery) shouldBe DeliveryAdmissionResult.PermanentFailure(
			AdmissionFailureCode.STALE_OBSERVATION,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `control only registration persists observation without session capture attribution`() = runTest {
		val authorization = installSession()
		installControlOnlyGeneration()

		subject.admit(
			sessionCandidate(sequence = 1L, authorization = authorization).copy(
				registrationGeneration = 2L,
				physicalConfigurationFingerprint = CONTROL_PHYSICAL_CONFIG,
				registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				registrationEligibilityFingerprint = CONTROL_ONLY_FINGERPRINT,
			),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.logicalTrackingId shouldBe null
		event.serviceRunId shouldBe null
		event.sourcePolicyRevision shouldBe null
		event.captureConsentEpoch shouldBe null
		event.sessionManifestRevision shouldBe null
		event.lifecycleLeaseGeneration shouldBe null
		event.registrationPurposeEligibilityMask shouldBe SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
		event.planAttribution shouldBe PlanAttribution.RECEIVE_TIME_ONLY
	}

	@Test
	fun `current immutable manifest admits session qualified evidence`() = runTest {
		val authorization = installSession()

		val result = subject.admit(sessionCandidate(sequence = 1L, authorization = authorization))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.sessionManifestRevision shouldBe 1L
		event.lifecycleLeaseGeneration shouldBe 7L
	}

	@Test
	fun `authorization stop boundary admits pre boundary and rejects exact boundary`() = runTest {
		installSession(
			sessionState = "STOPPING",
			runState = "STOPPING",
			cutoffElapsedNanos = 100L,
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				2L,
				emptyList(),
				"boot",
				100L,
				100L,
			),
		)

		subject.admit(
			candidate(sequence = 1L, observedElapsedNanos = 99L),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val rejected = subject.admit(
			candidate(sequence = 2L, observedElapsedNanos = 100L),
		).shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.STALE_SOURCE_POLICY
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `capture revocation atomically removes capture while retaining authorized ambient ingress`() = runTest {
		val settings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot", 100L, 100L)
		}
		val current = policy.bootstrapFromLegacy(settings)
		val staleCandidate = candidate(sequence = 1L, observedElapsedNanos = 99L).copy(
			sourcePolicyRevision = current.revision,
			captureConsentEpoch = requireNotNull(
				current[TrackingSourceComponent.ACTIVITY].captureConsentEpoch,
			),
		)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = current.revision,
			settings = settings.copy(
				activityEnabled = false,
				sourceCollectionSettings = settings.sourceCollectionSettings.copy(
					activity = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_REVOKE",
		)
		val fenced = database.sourceBrokerDao().latestAuthorization(SourceKind.ACTIVITY.stableCode, 1L)
		fenced.map { it.purpose } shouldBe listOf(SourceBrokerPurpose.AMBIENT_PRODUCT)
		fenced.single().effectiveElapsedRealtimeNanos shouldBe 100L
		database.sourceBrokerDao().demandHistory("session:$TEST_SESSION_ID").single().status shouldBe
			SourceDemandEntity.STATUS_RETIRING
		database.sourceBrokerDao().currentDemands("app:test").single().status shouldBe
			SourceDemandEntity.STATUS_ACTIVE

		subject.admit(staleCandidate).shouldBeInstanceOf<AdmissionResult.Admitted>()
		subject.admit(staleCandidate.copy(sourceSequence = 2L, observedElapsedRealtimeNanos = 100L))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		val events = subject.committedBatch(0L, 2).map { it.evidence }
		events.first().sourcePolicyRevision shouldBe current.revision
		events.first().planAttribution shouldBe PlanAttribution.CAPTURED_REGISTRATION
		events.last().sourcePolicyRevision shouldBe null
		events.last().captureConsentEpoch shouldBe null
		events.last().planAttribution shouldBe PlanAttribution.RECEIVE_TIME_ONLY
	}

	private fun candidate(
		sequence: Long,
		activityType: Int = 3,
		epoch: Long = 0L,
		acquiredAtMs: Long = 100L,
		providerDedupKey: String? = null,
		observedElapsedNanos: Long = 100L,
		receivedElapsedNanos: Long = 110L,
	) = SourceEvidenceCandidate(
		providerDedupKey = providerDedupKey,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.ACTIVITY,
		sourceInstanceId = SourceInstanceId("activity-instance"),
		registrationGeneration = 1L,
		physicalConfigurationFingerprint = TEST_PHYSICAL_CONFIG,
		registrationPurposeEligibilityMask = TEST_ELIGIBILITY_MASK,
		registrationEligibilityFingerprint = TEST_ELIGIBILITY_FINGERPRINT,
		sourceSequence = sequence,
		configRevision = 1L,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = observedElapsedNanos,
		receivedElapsedRealtimeNanos = receivedElapsedNanos,
		wallTimeMs = acquiredAtMs,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = epoch,
		acquiredAtMs = acquiredAtMs,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = ActivityTransitionPayload(activityType, 1, 100L),
	)

	private fun stepCandidate(
		startNanos: Long,
		endNanos: Long,
		observedNanos: Long = endNanos,
		receivedNanos: Long = 110L,
		payloadClockDomainId: String = "boot",
		sourceSequence: Long = 0L,
		providerDedupKey: String? = null,
		lastProviderSequence: Long = 2L,
	) = SourceEvidenceCandidate(
		providerDedupKey = providerDedupKey,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("step-instance"),
		registrationGeneration = 1L,
		physicalConfigurationFingerprint = "step-physical-config",
		registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		registrationEligibilityFingerprint = STEP_ELIGIBILITY_FINGERPRINT,
		sourceSequence = sourceSequence,
		configRevision = 1L,
		planAttribution = PlanAttribution.RECEIVE_TIME_ONLY,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = observedNanos,
		receivedElapsedRealtimeNanos = receivedNanos,
		wallTimeMs = 100L,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = 0L,
		acquiredAtMs = 100L,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = StepCounterWindowPayload(
			bootClockDomainId = payloadClockDomainId,
			firstCumulativeCount = 10L,
			lastCumulativeCount = 11L,
			deltaCount = 1L,
			windowStartElapsedRealtimeNanos = startNanos,
			windowEndElapsedRealtimeNanos = endNanos,
			firstProviderSequence = 1L,
			lastProviderSequence = lastProviderSequence,
			baselineReset = false,
		),
	)

	private fun stepCheckpoint(
		providerSequenceThrough: Long,
		componentByte: Byte = 2,
		updatedAtMs: Long = 100L,
	) = SensorAdmissionCheckpoint(
		source = SourceKind.STEPS,
		ownerScope = STEP_OWNER_SCOPE,
		sourceInstanceId = "step-instance",
		clockDomainId = "boot",
		registrationGeneration = 1L,
		providerSequenceThrough = providerSequenceThrough,
		lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
		failedAdmissionCount = 0L,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		gapClassifications = emptySet(),
		componentStateVersion = 1,
		componentPayload = byteArrayOf(componentByte),
		updatedAtMs = updatedAtMs,
	)

	private fun sessionCandidate(
		sequence: Long,
		authorization: SessionAuthorization,
		observedElapsedNanos: Long = 100L,
	) = candidate(sequence = sequence, observedElapsedNanos = observedElapsedNanos).copy(
		logicalTrackingId = LogicalTrackingId(TEST_SESSION_ID),
		serviceRunId = ServiceRunId(TEST_RUN_ID),
		sourcePolicyRevision = authorization.policyRevision,
		captureConsentEpoch = authorization.captureConsentEpoch,
		sessionManifestRevision = 1L,
		lifecycleLeaseGeneration = 7L,
	)

	private fun delivery(vararg candidates: SourceEvidenceCandidate<*>) = SourceDeliveryCandidate(
		identity = sourceDeliveryIdentity("provider-delivery-1".encodeToByteArray()),
		units = candidates.mapIndexed { index, evidence ->
			SourceDeliveryUnit(index, evidence)
		},
	)

	private suspend fun installSession(
		sessionState: String = "ACTIVE",
		runState: String = "ACTIVE",
		cutoffElapsedNanos: Long? = null,
	): SessionAuthorization {
		val policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot", 50L, 50L)
		}
		policyRepository.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val authority = requireNotNull(database.sourcePolicyDao().authority())
		val policy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(
				authority.currentPolicyRevision,
				SourceKind.ACTIVITY.stableCode,
			),
		)
		val sessionDao = database.sourceSessionDao()
		sessionDao.insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = TEST_SESSION_ID,
				state = sessionState,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "boot",
				startedAtMs = 50L,
				startedElapsedNanos = 50L,
				cutoffAtMs = cutoffElapsedNanos,
				cutoffElapsedNanos = cutoffElapsedNanos,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				lifecycleLeaseGeneration = 7L,
				lifecycleBootId = "boot",
				automationEpoch = null,
			),
		)
		sessionDao.insertManifest(
			SessionManifestVersionEntity(
				logicalTrackingId = TEST_SESSION_ID,
				manifestRevision = 1L,
				sessionMode = "MANUAL",
				sourcePolicyRevision = authority.currentPolicyRevision,
				acquisitionPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				effectiveBootId = "boot",
				effectiveElapsedRealtimeNanos = 50L,
				effectiveWallTimeMs = 50L,
				zoneId = "UTC",
				automationEpoch = null,
				changeReason = "TEST",
				manifestChecksum = "test-manifest",
			),
		)
		sessionDao.insertManifestSources(
			listOf(
				SessionManifestSourceEntity(
					logicalTrackingId = TEST_SESSION_ID,
					manifestRevision = 1L,
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = "SESSION_CAPTURE",
					consentEpoch = requireNotNull(policy.captureConsentEpoch),
					persistenceEligible = true,
					qosCode = policy.qosCode,
				),
			),
		)
		sessionDao.insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = TEST_RUN_ID,
				logicalTrackingId = TEST_SESSION_ID,
				state = runState,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 50L,
				startedElapsedNanos = 50L,
				completedAtMs = null,
				completionReason = null,
				bootId = "boot",
				leaseGeneration = 7L,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "START_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 1L,
			),
		)
		return SessionAuthorization(
			policyRevision = authority.currentPolicyRevision,
			captureConsentEpoch = requireNotNull(policy.captureConsentEpoch),
		)
	}

	private suspend fun installRegistrationGeneration() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = "ambient-demand",
					consumerId = "app:test",
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testAmbientEpoch,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 15_000L,
					requestedBootId = "boot",
					requestedElapsedRealtimeNanos = 0L,
					requestedAtMs = 0L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
				SourceDemandEntity(
					demandId = "session-demand",
					consumerId = "session:$TEST_SESSION_ID",
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = TEST_SESSION_ID,
					serviceRunId = TEST_RUN_ID,
					manifestRevision = 1L,
					lifecycleLeaseGeneration = 7L,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testCaptureEpoch,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 15_000L,
					requestedBootId = "boot",
					requestedElapsedRealtimeNanos = 0L,
					requestedAtMs = 0L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
		database.sourceRegistrationStateDao().insertIfAbsent(
			SourceRegistrationStateEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				ownerScope = SOURCE_OWNER_SCOPE,
				sourceInstanceId = "activity-instance",
				clockDomainId = "boot",
				registrationGeneration = 1L,
				nextSequence = 0L,
				appliedRevision = 1L,
				collectedDataEpoch = 0L,
				updatedAtMs = 50L,
			),
		)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-instance",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				clockDomainId = "boot",
				physicalConfigurationFingerprint = TEST_PHYSICAL_CONFIG,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 50L,
				reservedElapsedRealtimeNanos = 40L,
				acceptedAtMs = 50L,
				acceptedElapsedRealtimeNanos = 50L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:ambient-demand",
					authorizationFingerprint = TEST_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = TEST_ELIGIBILITY_MASK,
					demandId = "ambient-demand",
					consumerId = "app:test",
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testAmbientEpoch,
					persistenceEligible = true,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:session-demand",
					authorizationFingerprint = TEST_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = TEST_ELIGIBILITY_MASK,
					demandId = "session-demand",
					consumerId = "session:$TEST_SESSION_ID",
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testCaptureEpoch,
					persistenceEligible = true,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = TEST_SESSION_ID,
					serviceRunId = TEST_RUN_ID,
					manifestRevision = 1L,
					lifecycleLeaseGeneration = 7L,
				),
			),
		)
	}

	private suspend fun installCaptureLane(
		source: SourceKind,
		bindingGeneration: Long = 1L,
		projectionId: String = "ingress-test-${source.name.lowercase()}",
		productStage: String = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
	) {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = source.stableCode,
				bindingGeneration = bindingGeneration,
				projectionId = projectionId,
				projectionVersion = 1,
				captureModeMask = 7L,
				productStage = productStage,
				activatedRolloutRevision = 1L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
	}

	private suspend fun installControlOnlyGeneration() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = "control-demand",
					consumerId = "app:automation",
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = false,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 15_000L,
					requestedBootId = "boot",
					requestedElapsedRealtimeNanos = 0L,
					requestedAtMs = 0L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = "activity-instance",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				clockDomainId = "boot",
				physicalConfigurationFingerprint = CONTROL_PHYSICAL_CONFIG,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 60L,
				reservedElapsedRealtimeNanos = 50L,
				acceptedAtMs = 60L,
				acceptedElapsedRealtimeNanos = 60L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 2L,
					authorizationRevision = 2L,
					memberId = "demand:control-demand",
					authorizationFingerprint = CONTROL_ONLY_FINGERPRINT,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
					demandId = "control-demand",
					consumerId = "app:automation",
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = false,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private suspend fun installStepRegistrationGeneration() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = "step-ambient-demand",
					consumerId = "app:step-test",
					sourceKind = SourceKind.STEPS.stableCode,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testAmbientEpoch,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 15_000L,
					requestedBootId = "boot",
					requestedElapsedRealtimeNanos = 0L,
					requestedAtMs = 0L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
		database.sourceRegistrationStateDao().insertIfAbsent(
			SourceRegistrationStateEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				ownerScope = STEP_OWNER_SCOPE,
				sourceInstanceId = "step-instance",
				clockDomainId = "boot",
				registrationGeneration = 1L,
				nextSequence = 0L,
				appliedRevision = 1L,
				collectedDataEpoch = 0L,
				updatedAtMs = 50L,
			),
		)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "step-instance",
				ownerScope = STEP_OWNER_SCOPE,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot",
				physicalConfigurationFingerprint = "step-physical-config",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 50L,
				reservedElapsedRealtimeNanos = 40L,
				acceptedAtMs = 50L,
				acceptedElapsedRealtimeNanos = 50L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.STEPS.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:step-ambient-demand",
					authorizationFingerprint = STEP_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
					demandId = "step-ambient-demand",
					consumerId = "app:step-test",
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testAmbientEpoch,
					persistenceEligible = true,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private data class SessionAuthorization(
		val policyRevision: Long,
		val captureConsentEpoch: Long,
	)

	private fun activityAutomationAuthority(epoch: Long) = ActivityAutomationEpochEntity(
		epoch = epoch,
		automaticControlEnabled = true,
		bootClockDomainId = "boot",
		effectiveElapsedRealtimeNanos = 0L,
	)

	private companion object {
		const val TEST_SESSION_ID = "session"
		const val TEST_RUN_ID = "run"
		const val TEST_ELIGIBILITY_FINGERPRINT = "test-ambient-and-session"
		const val CONTROL_ONLY_FINGERPRINT = "test-control-only"
		const val STEP_ELIGIBILITY_FINGERPRINT = "test-step-ambient"
		const val TEST_PHYSICAL_CONFIG = "physical-config"
		const val CONTROL_PHYSICAL_CONFIG = "control-physical-config"
		val SOURCE_OWNER_SCOPE = "source-broker:${SourceKind.ACTIVITY.stableCode}"
		val STEP_OWNER_SCOPE = "source-broker:${SourceKind.STEPS.stableCode}"
		const val TEST_ELIGIBILITY_MASK = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT or
			SourceBrokerPurpose.MASK_SESSION_CAPTURE
		val TEST_EXECUTABLE_LANE_CATALOG = ExecutableSourceLaneCatalog.explicit(
			ExecutableSourceLaneBinding(
				source = SourceKind.ACTIVITY,
				bindingGeneration = 1L,
				projectionId = "ingress-test-activity",
				projectionVersion = 1,
				captureModes = CaptureReachabilityMode.entries.toSet(),
			),
			ExecutableSourceLaneBinding(
				source = SourceKind.STEPS,
				bindingGeneration = 1L,
				projectionId = "ingress-test-steps",
				projectionVersion = 1,
				captureModes = CaptureReachabilityMode.entries.toSet(),
			),
		)
	}
}

private class FakeLifecycleStore(initial: CollectedDataLifecycleSnapshot) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs)
		state.emit(updated)
		return updated
	}
	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(retainedFromMs = retainedFromMs)
		state.emit(updated)
		return updated
	}
	suspend fun update(value: CollectedDataLifecycleSnapshot) = state.emit(value)
}

private class FakeTrackingStartupGate(
	var result: TrackingStartupResult = TrackingStartupResult.Ready(
		legacyRecoveryPartial = false,
		liveCompletedThroughOrdinal = 0L,
	),
) : TrackingStartupGate {
	var reconcileCalls: Int = 0
		private set
	var allowReadyGeneration: Boolean = true

	override val isReady: Boolean
		get() = result is TrackingStartupResult.Ready

	override fun isReadyGeneration(expectedGeneration: Long): Boolean =
		allowReadyGeneration && isReady && currentGeneration == expectedGeneration

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
		reconcileCalls += 1
		return result
	}
}
