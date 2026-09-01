package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.runtime.PRESSURE_RUNTIME_COMPONENT_VERSION
import com.adsamcik.tracker.tracker.source.runtime.RuntimeCheckpointLifecycle
import com.adsamcik.tracker.tracker.source.runtime.SensorAdmissionCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionFailureCode
import com.adsamcik.tracker.tracker.source.runtime.SourceDeliveryAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.decodeSensorRuntimeCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.pressureProviderDeliveryIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
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
class PressureDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var ingress: RoomDurableSourceIngress
	private lateinit var lifecycleStore: PressureIngressLifecycleStore

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		lifecycleStore = PressureIngressLifecycleStore()
		ingress = createIngress()
		installPressureCaptureLane()
		RoomTrackingRolloutStateStore(database, PRESSURE_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.eventCanonical(
				sources = setOf(SourceKind.PRESSURE),
				captureModes = mapOf(
					SourceKind.PRESSURE to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				),
			),
			updatedAtMs = 1L,
		)
		installPressureDemand()
		installPressureRegistrationGeneration(
			generation = 1L,
			authorizationRevision = 1L,
			processIncarnationId = "pressure-process-before",
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 50L,
		)
	}

	private fun createIngress() = RoomDurableSourceIngress(
		database = database,
		lifecycleStore = lifecycleStore,
		payloadCodec = DefaultSourcePayloadCodec(),
		executableLaneCatalog = PRESSURE_EXECUTABLE_LANE_CATALOG,
		trackingStartupGateProvider = Provider { PressureIngressStartupGate() },
	)

	@After
	fun tearDown() = database.close()

	@Test
	fun `Pressure ingress replacement retries exactly and rejects a stale generation`() = runTest {
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val payload = pressurePayload()
		val first = pressureDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 120L,
			payload = payload,
		)

		val durable = sink.admit(first, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()

		val exactRetry = pressureDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 130L,
			payload = payload,
		)
		first.identity shouldBe exactRetry.identity
		val restartedSink = DurableSourceEventSinkFactory(createIngress()).unbound
		val duplicate = restartedSink.admit(exactRetry, pressureCheckpoint(1L, payload, 130L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		assertExactRetryState(first, durable, duplicate, payload)

		replacePressureRuntimeGeneration(acceptedElapsedRealtimeNanos = 50L)
		val staleReplacement = pressureDeliveryCandidate(
			registrationGeneration = 2L,
			authorizationRevision = 2L,
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 210L,
			payload = payload,
		)
		first.identity shouldBe staleReplacement.identity
		val rejected = restartedSink.admit(staleReplacement, pressureCheckpoint(2L, payload, 210L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.TerminalFailure>()
		rejected.code shouldBe SourceAdmissionFailureCode.STALE_REGISTRATION_GENERATION
		val ordinaryDuplicate = restartedSink.admit(staleReplacement)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		ordinaryDuplicate.existingAdmissionOrdinals shouldBe durable.admissionOrdinals

		assertStaleReplacementDidNotCheckpoint(payload)
	}

	@Test
	fun `Pressure replay before the retention boundary cannot checkpoint`() = runTest {
		val payload = pressurePayload()
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = pressureDeliveryCandidate(payload = payload)
		sink.admit(first, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		lifecycleStore.advanceRetainedFrom(101L)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 101L, 101L) shouldBe 1

		val retry = pressureDeliveryCandidate(receivedElapsedRealtimeNanos = 130L, payload = payload)
		val rejected = ingress.admit(retry, pressureCheckpoint(1L, payload, 130L))
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY
		assertSingleAdmissionUnchanged(payload, causalOrderElapsedRealtimeNanos = 120L)
	}

	@Test
	fun `Pressure callback-only replay collision cannot mutate sequence or checkpoint`() = runTest {
		val payload = pressurePayload()
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = pressureDeliveryCandidate(payload = payload)
		sink.admit(first, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val renumbered = payload.copy(firstProviderSequence = 101L, lastProviderSequence = 102L)
		val retry = pressureDeliveryCandidate(receivedElapsedRealtimeNanos = 130L, payload = renumbered)
		first.identity shouldBe retry.identity

		val rejected = ingress.admit(retry, pressureCheckpoint(1L, renumbered, 130L))
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
		assertSingleAdmissionUnchanged(payload, causalOrderElapsedRealtimeNanos = 120L)
	}

	@Test
	fun `Pressure wrapper cannot narrow intrinsic interval on admission or replay`() = runTest {
		val payload = pressurePayload()
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val narrowed = pressureDeliveryCandidate(
			payload = payload,
			wrapperStartElapsedRealtimeNanos = 105L,
		)
		val initialRejected = ingress.admit(narrowed, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()
		initialRejected.code shouldBe AdmissionFailureCode.INVALID_OBSERVED_TIME
		assertNoAdmission()

		val valid = pressureDeliveryCandidate(payload = payload)
		sink.admit(valid, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val narrowedRetry = pressureDeliveryCandidate(
			receivedElapsedRealtimeNanos = 130L,
			payload = payload,
			wrapperStartElapsedRealtimeNanos = 105L,
		)
		val replayRejected = ingress.admit(
			narrowedRetry,
			pressureCheckpoint(1L, payload, 130L),
		).shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()
		replayRejected.code shouldBe AdmissionFailureCode.INVALID_OBSERVED_TIME
		assertSingleAdmissionUnchanged(payload, causalOrderElapsedRealtimeNanos = 120L)
	}

	@Test
	fun `Pressure authorization boundary rejects new admission over the true window`() = runTest {
		val payload = pressurePayload()
		installPressureAuthorization(1L, 2L, effectiveElapsedRealtimeNanos = 105L)
		val candidate = pressureDeliveryCandidate(authorizationRevision = 2L, payload = payload)
		val rejected = ingress.admit(candidate, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
		assertNoAdmission()
	}

	@Test
	fun `Pressure authorization boundary rejects checkpointed replay over the true window`() = runTest {
		val payload = pressurePayload()
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = pressureDeliveryCandidate(payload = payload)
		sink.admit(first, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		installPressureAuthorization(1L, 2L, effectiveElapsedRealtimeNanos = 105L)
		val retry = pressureDeliveryCandidate(
			authorizationRevision = 2L,
			receivedElapsedRealtimeNanos = 130L,
			payload = payload,
		)

		val rejected = ingress.admit(retry, pressureCheckpoint(1L, payload, 130L))
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
		assertSingleAdmissionUnchanged(payload, causalOrderElapsedRealtimeNanos = 120L)
	}

	@Test
	fun `Pressure replay requires stored authorization to match the true window`() = runTest {
		val payload = pressurePayload()
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = pressureDeliveryCandidate(payload = payload)
		sink.admit(first, pressureCheckpoint(1L, payload, 120L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		installPressureAuthorization(1L, 2L, effectiveElapsedRealtimeNanos = 90L)
		val retry = pressureDeliveryCandidate(
			authorizationRevision = 2L,
			receivedElapsedRealtimeNanos = 130L,
			payload = payload,
		)

		val rejected = ingress.admit(retry, pressureCheckpoint(1L, payload, 130L))
			.shouldBeInstanceOf<DeliveryAdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.STALE_SOURCE_POLICY
		assertSingleAdmissionUnchanged(payload, causalOrderElapsedRealtimeNanos = 120L)
	}

	private suspend fun assertExactRetryState(
		first: SourceDeliveryCandidate,
		durable: SourceDeliveryAdmissionHandoff.Durable,
		duplicate: SourceDeliveryAdmissionHandoff.Duplicate,
		payload: PressureWindowPayload,
	) {
		durable.admissionOrdinals shouldBe listOf(1L)
		duplicate.existingAdmissionOrdinals shouldBe durable.admissionOrdinals
		database.sourceEventWalDao().countAll() shouldBe 1L
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		stored.sourceInstanceId shouldBe PRESSURE_SOURCE_INSTANCE_ID
		stored.registrationGeneration shouldBe 1L
		stored.sourceSequence shouldBe 0L
		stored.providerDedupKey shouldBe null
		stored.deliveryIdentity shouldBe first.identity.value
		val storedPayload = DefaultSourcePayloadCodec().decode(
			SourceKind.PRESSURE,
			stored.payloadVersion,
			stored.payload,
		).shouldBeInstanceOf<PressureWindowPayload>()
		storedPayload.firstProviderSequence shouldBe 1L
		storedPayload.lastProviderSequence shouldBe 2L

		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		registrationState.registrationGeneration shouldBe 1L
		registrationState.nextSequence shouldBe 1L

		val runtimeState = requireNotNull(
			database.sourceRuntimeStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		runtimeState.registrationGeneration shouldBe 1L
		runtimeState.lastProviderSequence shouldBe payload.lastProviderSequence
		runtimeState.lastAdmissionOrdinal shouldBe 1L
		val decoded = requireNotNull(
			decodeSensorRuntimeCheckpoint(runtimeState, PRESSURE_RUNTIME_COMPONENT_VERSION),
		)
		decoded.metrics.lastDurablyAdmittedSequence shouldBe payload.lastProviderSequence
		decoded.metrics.lastAdmissionOrdinal shouldBe 1L
		decoded.causalOrderElapsedRealtimeNanos shouldBe 130L
		database.sourceBrokerDao().registration(SourceKind.PRESSURE.stableCode, 1L)
			?.providerProcessIncarnationId shouldBe "pressure-process-before"
	}

	private suspend fun assertStaleReplacementDidNotCheckpoint(payload: PressureWindowPayload) {
		database.sourceEventWalDao().countAll() shouldBe 1L
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		registrationState.registrationGeneration shouldBe 2L
		registrationState.nextSequence shouldBe 1L
		val runtimeState = requireNotNull(
			database.sourceRuntimeStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		runtimeState.registrationGeneration shouldBe 1L
		runtimeState.lastProviderSequence shouldBe payload.lastProviderSequence
		runtimeState.lastAdmissionOrdinal shouldBe 1L
		val decoded = requireNotNull(
			decodeSensorRuntimeCheckpoint(runtimeState, PRESSURE_RUNTIME_COMPONENT_VERSION),
		)
		decoded.causalOrderElapsedRealtimeNanos shouldBe 130L
		database.sourceBrokerDao().registration(SourceKind.PRESSURE.stableCode, 2L)
			?.providerProcessIncarnationId shouldBe "pressure-process-after"
	}

	private suspend fun assertSingleAdmissionUnchanged(
		payload: PressureWindowPayload,
		causalOrderElapsedRealtimeNanos: Long,
	) {
		database.sourceEventWalDao().countAll() shouldBe 1L
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		registrationState.registrationGeneration shouldBe 1L
		registrationState.nextSequence shouldBe 1L
		val runtimeState = requireNotNull(
			database.sourceRuntimeStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		runtimeState.registrationGeneration shouldBe 1L
		runtimeState.lastProviderSequence shouldBe payload.lastProviderSequence
		runtimeState.lastAdmissionOrdinal shouldBe 1L
		val decoded = requireNotNull(
			decodeSensorRuntimeCheckpoint(runtimeState, PRESSURE_RUNTIME_COMPONENT_VERSION),
		)
		decoded.causalOrderElapsedRealtimeNanos shouldBe causalOrderElapsedRealtimeNanos
	}

	private suspend fun assertNoAdmission() {
		database.sourceEventWalDao().countAll() shouldBe 0L
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		registrationState.nextSequence shouldBe 0L
		database.sourceRuntimeStateDao().get(
			SourceKind.PRESSURE.stableCode,
			PRESSURE_OWNER_SCOPE,
		) shouldBe null
	}

	@Test
	fun `Pressure checkpoint failure rolls back new WAL row and Room sequence`() = runTest {
		val payload = pressurePayload()
		val incompatibleFutureRuntime = pressureCheckpoint(
			registrationGeneration = 2L,
			payload = payload.copy(lastProviderSequence = 50L),
			updatedAtMs = 200L,
		).toRuntimeState(
			admissionOrdinal = 50L,
			causalOrderElapsedRealtimeNanos = 200L,
		)
		database.sourceRuntimeStateDao().save(incompatibleFutureRuntime)
		val delivery = pressureDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 120L,
			payload = payload,
		)
		val sink = DurableSourceEventSinkFactory(ingress).unbound

		shouldThrow<IllegalStateException> {
			sink.admit(delivery, pressureCheckpoint(1L, payload, 120L))
		}

		database.sourceEventWalDao().countAll() shouldBe 0L
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.PRESSURE.stableCode,
				PRESSURE_OWNER_SCOPE,
			),
		)
		registrationState.nextSequence shouldBe 0L
		database.sourceRuntimeStateDao().get(
			SourceKind.PRESSURE.stableCode,
			PRESSURE_OWNER_SCOPE,
		) shouldBe incompatibleFutureRuntime
	}

	private fun pressureDeliveryCandidate(
		registrationGeneration: Long = 1L,
		authorizationRevision: Long = 1L,
		physicalConfigurationFingerprint: String = FIRST_PHYSICAL_CONFIGURATION,
		receivedElapsedRealtimeNanos: Long = 120L,
		payload: PressureWindowPayload,
		wrapperStartElapsedRealtimeNanos: Long = payload.windowStartElapsedRealtimeNanos,
	): SourceDeliveryCandidate {
		val evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.PRESSURE,
			sourceInstanceId = SourceInstanceId(PRESSURE_SOURCE_INSTANCE_ID),
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorizationRevision = authorizationRevision,
			registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			registrationEligibilityFingerprint = PRESSURE_ELIGIBILITY_FINGERPRINT,
			// The atomic Room ingress owns the only source-sequence allocation.
			sourceSequence = 0L,
			configRevision = authorizationRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = BOOT_CLOCK_DOMAIN_ID,
			observedElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 100L,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = payload,
		)
		return SourceDeliveryCandidate(
			identity = pressureProviderDeliveryIdentity(BOOT_CLOCK_DOMAIN_ID, payload),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = evidence,
					observedIntervalStartElapsedRealtimeNanos = wrapperStartElapsedRealtimeNanos,
				),
			),
		)
	}

	private fun pressureCheckpoint(
		registrationGeneration: Long,
		payload: PressureWindowPayload,
		updatedAtMs: Long,
	) = SensorAdmissionCheckpoint(
		source = SourceKind.PRESSURE,
		ownerScope = PRESSURE_OWNER_SCOPE,
		sourceInstanceId = PRESSURE_SOURCE_INSTANCE_ID,
		clockDomainId = BOOT_CLOCK_DOMAIN_ID,
		registrationGeneration = registrationGeneration,
		providerSequenceThrough = payload.lastProviderSequence,
		lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
		failedAdmissionCount = 0L,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		gapClassifications = emptySet(),
		componentStateVersion = PRESSURE_RUNTIME_COMPONENT_VERSION,
		componentPayload = ByteArray(0),
		updatedAtMs = updatedAtMs,
	)

	private fun pressurePayload() = PressureWindowPayload(
		sampleCount = 2,
		meanHectopascals = 1_000.5,
		sumSquaredDeviations = 0.5,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_001f,
		windowStartElapsedRealtimeNanos = 100L,
		windowEndElapsedRealtimeNanos = 110L,
		firstProviderSequence = 1L,
		lastProviderSequence = 2L,
	)

	private suspend fun installPressureCaptureLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.PRESSURE.stableCode,
				bindingGeneration = 1L,
				projectionId = PRESSURE_PROJECTION_ID,
				projectionVersion = 1,
				captureModeMask = CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
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

	private suspend fun installPressureDemand() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = PRESSURE_DEMAND_ID,
					consumerId = "session:$LOGICAL_TRACKING_ID",
					sourceKind = SourceKind.PRESSURE.stableCode,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = SERVICE_RUN_ID,
					manifestRevision = 1L,
					lifecycleLeaseGeneration = 1L,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 5_000L,
					requestedBootId = BOOT_CLOCK_DOMAIN_ID,
					requestedElapsedRealtimeNanos = 0L,
					requestedAtMs = 0L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
	}

	private suspend fun installPressureRegistrationGeneration(
		generation: Long,
		authorizationRevision: Long,
		processIncarnationId: String,
		physicalConfigurationFingerprint: String,
		acceptedElapsedRealtimeNanos: Long,
	) {
		if (generation == 1L) {
			database.sourceRegistrationStateDao().insertIfAbsent(
				SourceRegistrationStateEntity(
					sourceKind = SourceKind.PRESSURE.stableCode,
					ownerScope = PRESSURE_OWNER_SCOPE,
					sourceInstanceId = PRESSURE_SOURCE_INSTANCE_ID,
					clockDomainId = BOOT_CLOCK_DOMAIN_ID,
					registrationGeneration = generation,
					nextSequence = 0L,
					appliedRevision = authorizationRevision,
					collectedDataEpoch = 0L,
					updatedAtMs = acceptedElapsedRealtimeNanos,
				),
			)
		}
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.PRESSURE.stableCode,
				registrationGeneration = generation,
				sourceInstanceId = PRESSURE_SOURCE_INSTANCE_ID,
				ownerScope = PRESSURE_OWNER_SCOPE,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = processIncarnationId,
				clockDomainId = BOOT_CLOCK_DOMAIN_ID,
				physicalConfigurationFingerprint = physicalConfigurationFingerprint,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = acceptedElapsedRealtimeNanos,
				reservedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
				acceptedAtMs = acceptedElapsedRealtimeNanos,
				acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		installPressureAuthorization(generation, authorizationRevision, acceptedElapsedRealtimeNanos)
	}

	private suspend fun installPressureAuthorization(
		generation: Long,
		authorizationRevision: Long,
		effectiveElapsedRealtimeNanos: Long,
	) {
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.PRESSURE.stableCode,
					registrationGeneration = generation,
					authorizationRevision = authorizationRevision,
					memberId = "demand:$PRESSURE_DEMAND_ID",
					authorizationFingerprint = PRESSURE_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					demandId = PRESSURE_DEMAND_ID,
					consumerId = "session:$LOGICAL_TRACKING_ID",
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = effectiveElapsedRealtimeNanos,
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = SERVICE_RUN_ID,
					manifestRevision = 1L,
					lifecycleLeaseGeneration = 1L,
				),
			),
		)
	}

	private suspend fun replacePressureRuntimeGeneration(acceptedElapsedRealtimeNanos: Long) {
		database.sourceBrokerDao().finishRegistration(
			sourceKind = SourceKind.PRESSURE.stableCode,
			registrationGeneration = 1L,
			sourceInstanceId = PRESSURE_SOURCE_INSTANCE_ID,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 200L,
			failureCode = "PROCESS_REPLACED",
		) shouldBe 1
		installPressureRegistrationGeneration(
			generation = 2L,
			authorizationRevision = 2L,
			processIncarnationId = "pressure-process-after",
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
		)
		val current = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.PRESSURE.stableCode, PRESSURE_OWNER_SCOPE),
		)
		database.sourceRegistrationStateDao().replace(
			current.copy(
				registrationGeneration = 2L,
				appliedRevision = 2L,
				updatedAtMs = 200L,
			),
		)
	}

	private companion object {
		const val BOOT_CLOCK_DOMAIN_ID = "boot"
		const val PRESSURE_SOURCE_INSTANCE_ID = "pressure-source-instance"
		const val PRESSURE_DEMAND_ID = "pressure-session-demand"
		const val PRESSURE_ELIGIBILITY_FINGERPRINT = "pressure-session-eligibility"
		const val FIRST_PHYSICAL_CONFIGURATION = "pressure-physical-before"
		const val SECOND_PHYSICAL_CONFIGURATION = "pressure-physical-after"
		const val PRESSURE_PROJECTION_ID = "pressure-ingress-test"
		const val LOGICAL_TRACKING_ID = "pressure-session"
		const val SERVICE_RUN_ID = "pressure-run"
		val PRESSURE_OWNER_SCOPE = "source-broker:${SourceKind.PRESSURE.stableCode}"
		val PRESSURE_EXECUTABLE_LANE_CATALOG = ExecutableSourceLaneCatalog.explicit(
			ExecutableSourceLaneBinding(
				source = SourceKind.PRESSURE,
				bindingGeneration = 1L,
				projectionId = PRESSURE_PROJECTION_ID,
				projectionVersion = 1,
				captureModes = setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
			),
		)
	}
}

private class PressureIngressLifecycleStore : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(0L, null))
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
}

private class PressureIngressStartupGate : TrackingStartupGate {
	override val isReady: Boolean = true
	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(
			legacyRecoveryPartial = false,
			liveCompletedThroughOrdinal = 0L,
		)
}
