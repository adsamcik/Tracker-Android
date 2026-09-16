package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
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
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.runtime.SourceDeliveryAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.AmbientCellRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.WITHHELD_RADIO_IDENTIFIER_TOKEN
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellFactProjector
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionResult
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionUnverifiableReason
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var ingress: RoomDurableSourceIngress

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = CellIngressLifecycleStore(),
			payloadCodec = DefaultSourcePayloadCodec(),
			executableLaneCatalog = CELL_EXECUTABLE_LANE_CATALOG,
			trackingStartupGateProvider = Provider { CellIngressStartupGate() },
		)
		installCellCaptureLane()
		RoomTrackingRolloutStateStore(database, CELL_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.eventCanonical(
				sources = setOf(SourceKind.CELL),
				captureModes = mapOf(SourceKind.CELL to setOf(CaptureReachabilityMode.AMBIENT)),
			),
			updatedAtMs = 1L,
		)
		installCellDemand()
		installCellRegistrationGeneration(
			generation = 1L,
			authorizationRevision = 1L,
			processIncarnationId = "cell-process-before",
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 50L,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `Cell provider replay across runtime generations allocates one Room sequence`() = runTest {
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = cellDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 110L,
		)

		val durable = sink.admit(first)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()

		replaceCellRuntimeGeneration()
		val replay = cellDeliveryCandidate(
			registrationGeneration = 2L,
			authorizationRevision = 2L,
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 210L,
		)
		first.identity shouldBe replay.identity

		val duplicate = sink.admit(replay)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()

		durable.admissionOrdinals shouldBe listOf(1L)
		duplicate.existingAdmissionOrdinals shouldBe durable.admissionOrdinals
		database.sourceEventWalDao().countAll() shouldBe 1L
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.sourceKind shouldBe SourceKind.CELL.stableCode
		stored.sourceInstanceId shouldBe CELL_SOURCE_INSTANCE_ID
		stored.registrationGeneration shouldBe 1L
		stored.sourceSequence shouldBe 0L
		stored.deliveryIdentity shouldBe first.identity.value
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.CELL.stableCode,
				CELL_OWNER_SCOPE,
			),
		)
		registrationState.registrationGeneration shouldBe 2L
		registrationState.nextSequence shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.CELL.stableCode, 1L)
			?.providerProcessIncarnationId shouldBe "cell-process-before"
		database.sourceBrokerDao().registration(SourceKind.CELL.stableCode, 2L)
			?.providerProcessIncarnationId shouldBe "cell-process-after"
	}

	@Test
	fun `shared Cell controller drives real WAL projector and Room reader`() = runTest {
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 1L,
				legacySettingsFingerprint = null,
				updatedAtMs = 1L,
			),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					1L,
					SourceKind.CELL.stableCode,
					enabled = true,
					qosCode = 2,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = false,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = true,
					captureConsentEpoch = null,
					controlConsentEpoch = null,
					ambientConsentEpoch = 1L,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = 50L,
					effectiveWallTimeMs = 50L,
					changeReason = "TEST_AMBIENT_CHAIN",
				),
			),
		)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					SourceKind.CELL.stableCode,
					SourceBrokerPurpose.AMBIENT_PRODUCT,
					1L,
					eligible = true,
					persistenceEligible = true,
					policyRevision = 1L,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = 50L,
					effectiveWallTimeMs = 50L,
					changeReason = "TEST_AMBIENT_CHAIN",
				),
			),
		)
		database.applyAmbientCellRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				"privacy:cell:ambient:v1",
				0L,
				1L,
				1L,
				BOOT_CLOCK_DOMAIN_ID,
				50L,
				50L,
			),
		)
		database.ambientCellFactDao().insertAuthority(
			AmbientCellAuthorityIntegrity.create(
				1L,
				AmbientCellAuthorityEntity.STATE_ACTIVE,
				1L,
				1L,
				"privacy:cell:ambient:v1",
				1L,
				0L,
				0L,
				BOOT_CLOCK_DOMAIN_ID,
				50L,
				50L,
				1L,
				"ambient-cell-owner",
				1L,
				CELL_DEMAND_ID,
			),
		)
		val physical = mockk<CellSourceRuntime>()
		every { physical.capabilities } returns MutableStateFlow(
			SourceCapabilities(true, false, false, null, null),
		)
		val sinkFactory = DurableSourceEventSinkFactory(ingress)
		var admissionOrdinal: Long? = null
		coEvery { physical.refreshShared(any(), any(), null) } returns null
		coEvery { physical.reconfigure(any<CellPlan>(), any<SourceEventSink>()) } coAnswers {
			val handoff = secondArg<SourceEventSink>().admit(
				cellDeliveryCandidate(
					1L,
					1L,
					FIRST_PHYSICAL_CONFIGURATION,
					110L,
					RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
					"Europe/Prague",
				),
			).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
			admissionOrdinal = handoff.admissionOrdinals.single()
			SourceApplyResult.Applied(
				AppliedSourcePlan(
					1L,
					1L,
					SourceKind.CELL,
					SourceInstanceId(CELL_SOURCE_INSTANCE_ID),
					1L,
					110L,
					SourceApplyStatus.APPLIED,
				),
			)
		}
		val controller = SharedCellSourceController(
			physical,
			SourceBroker(
				database,
				RoomTrackingRolloutStateStore(database, CELL_EXECUTABLE_LANE_CATALOG),
			),
			sinkFactory,
		)

		controller.reconcileAmbientJoin().shouldBeInstanceOf<AmbientCellRuntimeJoinResult.Active>()
		val originalOrdinal = requireNotNull(admissionOrdinal)
		val zoneOnlyReplay = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				111L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"UTC",
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		zoneOnlyReplay.existingAdmissionOrdinals shouldBe listOf(originalOrdinal)
		val versionFourReplay = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				112L,
				4,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		versionFourReplay.existingAdmissionOrdinals shouldBe listOf(originalOrdinal)
		database.sourceEventWalDao().countAll() shouldBe 1L
		val originalPolicy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(1L, SourceKind.CELL.stableCode),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				originalPolicy.copy(
					policyRevision = 2L,
					effectiveElapsedRealtimeNanos = 120L,
					changeReason = "UNRELATED_SOURCE_CHANGE",
				),
			),
		)
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 1L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = 2L,
			legacySettingsFingerprint = null,
			updatedAtMs = 120L,
		) shouldBe 1
		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
		).project(originalOrdinal)
			.shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val row = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).single()

		row.fact.storedZoneId shouldBe "Europe/Prague"
		row.fact.sourceAdmissionOrdinal shouldBe admissionOrdinal
		val freshUnchanged = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				111L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"Europe/Prague",
				101L,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
		).project(freshUnchanged.admissionOrdinals.single())
			.shouldBeInstanceOf<AmbientCellProjectionResult.CoverageCompacted>()
		val gapDelivery = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				113L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"Europe/Prague",
				102L,
				subscriptionId = 1,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val gapOrdinal = gapDelivery.admissionOrdinals.single()
		val gapWal = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(gapOrdinal))
		val gapId = AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-gap-v1",
			gapWal.eventId,
			1L,
			0L,
		)
		val footprint = AmbientCellFactIntegrity.createReplayFootprint(
			AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP,
			gapId,
			0L,
			0L,
			1L,
			200L,
		)
		database.ambientCellFactDao().insertReplayFootprints(listOf(footprint))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_cell_replay_footprint SET effect_checksum = ? " +
				"WHERE footprint_kind = ? AND identity_digest = ? AND semantic_revision = 0",
			arrayOf(
				"0".repeat(64),
				AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP,
				gapId,
			),
		)

		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
		).project(gapOrdinal) shouldBe AmbientCellProjectionResult.Unverifiable(
			AmbientCellProjectionUnverifiableReason.REPLAY_FOOTPRINT_CORRUPT,
		)
		val pendingDeletion = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				114L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"Europe/Prague",
				103L,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		database.ambientCellFactDao().insertDeletionMarker(
			AmbientCellFactIntegrity.createDeletionMarker(
				0L,
				1L,
				1L,
				"TEST_CELL_DELETION",
				200L,
			),
		)
		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
		).project(pendingDeletion.admissionOrdinals.single()) shouldBe
			AmbientCellProjectionResult.Unverifiable(
				AmbientCellProjectionUnverifiableReason.DELETED_SCOPE,
			)
	}

	private fun cellDeliveryCandidate(
		registrationGeneration: Long,
		authorizationRevision: Long,
		physicalConfigurationFingerprint: String,
		receivedElapsedRealtimeNanos: Long,
		payloadVersion: Int = 1,
		observationZoneId: String? = null,
		providerTimestampNanos: Long = PROVIDER_TIMESTAMP_NANOS,
		subscriptionId: Int? = null,
	): SourceDeliveryCandidate {
		val observations = listOf(
			CellObservationEvidence(
				identifierToken = WITHHELD_RADIO_IDENTIFIER_TOKEN,
				radioType = "LTE",
				registered = true,
				signalLevelDbm = -91,
				providerTimestampNanos = providerTimestampNanos,
			),
		)
		val evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.CELL,
			sourceInstanceId = SourceInstanceId(CELL_SOURCE_INSTANCE_ID),
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorizationRevision = authorizationRevision,
			registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			registrationEligibilityFingerprint = CELL_ELIGIBILITY_FINGERPRINT,
			// The atomic Room ingress owns the only source-sequence allocation.
			sourceSequence = 0L,
			configRevision = authorizationRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = BOOT_CLOCK_DOMAIN_ID,
			observedElapsedRealtimeNanos = providerTimestampNanos,
			receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 100L,
			quality = SourceQuality(),
			payloadVersion = payloadVersion,
			payload = CellSnapshotPayload(
				subscriptionId = subscriptionId,
				observations = observations,
				refreshOutcome = CellRefreshOutcome.CALLBACK,
				observationZoneId = observationZoneId,
			),
		)
		return SourceDeliveryCandidate(
			identity = cellProviderDeliveryIdentity(BOOT_CLOCK_DOMAIN_ID, observations),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = evidence,
					observedIntervalStartElapsedRealtimeNanos = providerTimestampNanos,
				),
			),
		)
	}

	private suspend fun installCellCaptureLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.CELL.stableCode,
				bindingGeneration = 1L,
				projectionId = CELL_PROJECTION_ID,
				projectionVersion = 1,
				captureModeMask = CaptureReachabilityMode.AMBIENT.mask,
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

	private suspend fun installCellDemand() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = CELL_DEMAND_ID,
					consumerId = "app:cell-ingress-test",
					sourceKind = SourceKind.CELL.stableCode,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 15_000L,
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

	private suspend fun installCellRegistrationGeneration(
		generation: Long,
		authorizationRevision: Long,
		processIncarnationId: String,
		physicalConfigurationFingerprint: String,
		acceptedElapsedRealtimeNanos: Long,
	) {
		if (generation == 1L) {
			database.sourceRegistrationStateDao().insertIfAbsent(
				SourceRegistrationStateEntity(
					sourceKind = SourceKind.CELL.stableCode,
					ownerScope = CELL_OWNER_SCOPE,
					sourceInstanceId = CELL_SOURCE_INSTANCE_ID,
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
				sourceKind = SourceKind.CELL.stableCode,
				registrationGeneration = generation,
				sourceInstanceId = CELL_SOURCE_INSTANCE_ID,
				ownerScope = CELL_OWNER_SCOPE,
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
		installCellAuthorization(generation, authorizationRevision, acceptedElapsedRealtimeNanos)
	}

	private suspend fun installCellAuthorization(
		generation: Long,
		authorizationRevision: Long,
		effectiveElapsedRealtimeNanos: Long,
	) {
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.CELL.stableCode,
					registrationGeneration = generation,
					authorizationRevision = authorizationRevision,
					memberId = "demand:$CELL_DEMAND_ID",
					authorizationFingerprint = CELL_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
					demandId = CELL_DEMAND_ID,
					consumerId = "app:cell-ingress-test",
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = effectiveElapsedRealtimeNanos,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private suspend fun replaceCellRuntimeGeneration() {
		database.sourceBrokerDao().finishRegistration(
			sourceKind = SourceKind.CELL.stableCode,
			registrationGeneration = 1L,
			sourceInstanceId = CELL_SOURCE_INSTANCE_ID,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 200L,
			failureCode = "PROCESS_REPLACED",
		) shouldBe 1
		installCellRegistrationGeneration(
			generation = 2L,
			authorizationRevision = 2L,
			processIncarnationId = "cell-process-after",
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 200L,
		)
		val current = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.CELL.stableCode, CELL_OWNER_SCOPE),
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
		const val PROVIDER_TIMESTAMP_NANOS = 100L
		const val CELL_SOURCE_INSTANCE_ID = "cell-source-instance"
		const val CELL_DEMAND_ID = "cell-ambient-demand"
		const val CELL_ELIGIBILITY_FINGERPRINT = "cell-ambient-eligibility"
		const val FIRST_PHYSICAL_CONFIGURATION = "cell-physical-before"
		const val SECOND_PHYSICAL_CONFIGURATION = "cell-physical-after"
		const val CELL_PROJECTION_ID = "cell-ingress-test"
		val CELL_OWNER_SCOPE = "source-broker:${SourceKind.CELL.stableCode}"
		val CELL_EXECUTABLE_LANE_CATALOG = ExecutableSourceLaneCatalog.explicit(
			ExecutableSourceLaneBinding(
				source = SourceKind.CELL,
				bindingGeneration = 1L,
				projectionId = CELL_PROJECTION_ID,
				projectionVersion = 1,
				captureModes = setOf(CaptureReachabilityMode.AMBIENT),
			),
		)
	}
}

private class CellIngressLifecycleStore : CollectedDataLifecycleStore {
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

private class CellIngressStartupGate : TrackingStartupGate {
	override val isReady: Boolean = true
	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(
			legacyRecoveryPartial = false,
			liveCompletedThroughOrdinal = 0L,
		)
}
