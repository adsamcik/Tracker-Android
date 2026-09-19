package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.TestLiveAmbientRetentionAuthorityReader
import com.adsamcik.tracker.tracker.source.runtime.TestPurposeSourceCallerDemandDispatcher
import com.adsamcik.tracker.tracker.source.runtime.LiveAmbientRetentionSnapshot
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurpose
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityVector
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingSource
import kotlinx.coroutines.flow.MutableStateFlow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsDemandReconcilerTest {
	private lateinit var database: AppDatabase
	private lateinit var broker: SourceBroker
	private lateinit var policyRepository: RoomSourcePolicyRepository
	private lateinit var rolloutStore: TrackingRolloutStateStore
	private lateinit var retentionReader: TestLiveAmbientRetentionAuthorityReader
	private var retentionSnapshot: LiveAmbientRetentionSnapshot? = null
	private var elapsed = 10L

	@Before
	fun setUp() = runTest {
		retentionSnapshot = null
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L),
		)
		val binding = ExecutableSourceLaneBinding(
			source = SourceKind.STEPS,
			bindingGeneration = 1L,
			projectionId = "ambient-steps-demand-test",
			projectionVersion = 1,
			captureModes = setOf(CaptureReachabilityMode.AMBIENT),
		)
		rolloutStore = installCanonicalProductLanesForTest(
			database = database,
			bindings = listOf(binding),
			rolloutRevision = 1L,
		)
		retentionReader = TestLiveAmbientRetentionAuthorityReader()
		broker = SourceBroker(
			database,
			rolloutStore,
			retentionReader,
		)
		policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `missing current caller authority blocks capability and provider demand`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		var capabilityProbes = 0
		val unavailableReader = object : CurrentTrackingPurposeAvailabilityReader {
			override val availability = MutableStateFlow(
				CurrentTrackingPurposeAvailability.SAFE_DEFAULT,
			)
			override val authorityRevision = MutableStateFlow(
				TrackingPurposeAuthorityRevision.UNAVAILABLE,
			)
		}
		val subject = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
				emptySet(),
			),
			onResolve = { capabilityProbes++ },
			currentPurposeReader = unavailableReader,
		)

		subject.reconcileAt(boundary(100L)) shouldBe
			AmbientStepsDemandReconciliation.PolicyBlocked(
				null,
				AmbientStepsDemandBlockReason.CALLER_AUTHORITY_UNAVAILABLE,
			)
		capabilityProbes shouldBe 0
	}

	@Test
	fun `ready capability creates exactly one provider-specific ambient demand`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		var capabilityProbes = 0
		val subject = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				importAccess = AmbientStepsImportAccess.FOREGROUND_ONLY,
				optionalPermissions = setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ),
			),
			onResolve = { capabilityProbes++ },
		)

		val result = subject.reconcileAt(boundary(100L))

		val ready = result as AmbientStepsDemandReconciliation.DemandReady
		ready.provider shouldBe AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
		ready.importAccess shouldBe AmbientStepsImportAccess.FOREGROUND_ONLY
		ready.optionalPermissions shouldBe setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ)
		capabilityProbes shouldBe 1
		val demands = database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID)
		demands.size shouldBe 1
		val demand = demands.single()
		demand.demandId shouldBe ready.demandId
		demand.status shouldBe SourceDemandEntity.STATUS_ACTIVE
		(demand.toSourceDemandContract().floor as AmbientStepsAcquisitionFloor).mechanism shouldBe
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
	}

	@Test
	fun `new pending lease is accepted from its exact issuer identity before READY publication`() =
		runTest {
			val policy = bootstrapPolicy(ambientEnabled = true)
			val grant = requireNotNull(retentionSnapshot).grants.getValue(SourceKind.STEPS)
			val identity = AmbientReconciliationIdentity.from(
				TrackingPurposeLeaseIdentity(
					sourcePurpose =
						TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
					policyRevision = policy.revision,
					consentEpoch =
						requireNotNull(policy[TrackingSourceComponent.STEPS].ambientConsentEpoch),
					collectedDataEpoch = 3L,
					retainedFromMs = grant.retainedFromMs,
					rolloutRevision = 1L,
					executionRevision = 1L,
					ownerCasToken = "pending-steps-owner",
				),
				grant.opaquePolicyId,
				grant.approvalRevision,
			)
			val pending = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
				ambientSources = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
					(
						AmbientTrackingSource.STEPS to
							AmbientSourceOperationalAvailability.reconciliationPending(
								AmbientTrackingSource.STEPS,
								identity.purposeLeaseIdentity,
							)
					),
			)
			val reader = object : CurrentTrackingPurposeAvailabilityReader {
				override val availability = MutableStateFlow(
					CurrentTrackingPurposeAvailability(
						published = pending,
						currentAuthorities = mapOf(
							identity.sourcePurpose to TrackingPurposeAuthorityVector(
								identity.sourcePurpose,
								identity.policyRevision,
								identity.consentEpoch,
								identity.collectedDataEpoch,
								identity.rolloutRevision,
								identity.executionRevision,
							),
						),
					),
				)
				override val authorityRevision = MutableStateFlow(
					TrackingPurposeAuthorityRevision(
						policyRevision = identity.policyRevision,
						collectedDataEpoch = identity.collectedDataEpoch,
						rolloutRevision = identity.rolloutRevision,
					),
				)
			}

			reconciler(
				AmbientStepsCapability.ReadyForRegistration(
					AmbientStepsProvider.LOCAL_RECORDING_STEPS,
					AmbientStepsImportAccess.BACKGROUND_ALLOWED,
				),
				currentPurposeReader = reader,
			).reconcileAt(boundary(100L), AmbientReconciliationLease(identity)) shouldBe
				AmbientStepsDemandReconciliation.DemandReady(
					AmbientStepsProvider.LOCAL_RECORDING_STEPS,
					AmbientStepsImportAccess.BACKGROUND_ALLOWED,
					emptySet(),
					database.sourceBrokerDao()
						.currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID)
						.single()
						.demandId,
				)
		}

	@Test
	fun `missing selected-provider permission retires prior ambient demand`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			),
		).reconcileAt(boundary(100L))
		val subject = reconciler(
			AmbientStepsCapability.PermissionRequired(
				provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				requiredPermissions = setOf(AmbientStepsPermission.ACTIVITY_RECOGNITION),
			),
		)

		val result = subject.reconcileAt(boundary(200L))

		result shouldBe AmbientStepsDemandReconciliation.PermissionRequired(
			provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			requiredPermissions = setOf(AmbientStepsPermission.ACTIVITY_RECOGNITION),
			optionalPermissions = emptySet(),
		)
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	@Test
	fun `unavailable provider state retires prior ambient demand without direct fallback`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			),
		).reconcileAt(boundary(100L))
		val unavailable = AmbientStepsCapability.Unavailable(
			healthConnect = HealthConnectAmbientStepsAvailability.PROBE_FAILED,
			localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
		)

		reconciler(unavailable).reconcileAt(boundary(200L)) shouldBe
			AmbientStepsDemandReconciliation.Unavailable(
				unavailable.healthConnect,
				unavailable.localRecording,
			)
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	@Test
	fun `default-off ambient policy does not probe a provider`() = runTest {
		bootstrapPolicy(ambientEnabled = false)
		var capabilityProbes = 0

		val result = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
			),
			onResolve = { capabilityProbes++ },
		).reconcileAt(boundary(100L))

		result shouldBe AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = null,
			reason = AmbientStepsDemandBlockReason.REQUEST_DISABLED,
		)
		capabilityProbes shouldBe 0
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	@Test
	fun `revoked ambient policy retires stale demand without probing a provider`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			),
		).reconcileAt(boundary(50L))
		val activePolicy = policyRepository.currentState() as SourcePolicyAuthorityState.Active
		policyRepository.setNonCaptureConsent(
			expectedPolicyRevision = activePolicy.snapshot.revision,
			source = TrackingSourceComponent.STEPS,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_AMBIENT_DISABLED",
		)
		var capabilityProbes = 0

		val result = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
			),
			onResolve = { capabilityProbes++ },
		).reconcileAt(boundary(100L))

		result shouldBe AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = null,
			reason = AmbientStepsDemandBlockReason.REQUEST_DISABLED,
		)
		capabilityProbes shouldBe 0
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	@Test
	fun `contained ambient rollout retires demand without probing a provider`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			),
		).reconcileAt(boundary(50L))
		rolloutStore.save(TrackingRolloutState.contained(revision = 2L), updatedAtMs = 2L)
		var capabilityProbes = 0

		val result = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
			),
			onResolve = { capabilityProbes++ },
		).reconcileAt(boundary(100L))

		result shouldBe AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = null,
			reason = AmbientStepsDemandBlockReason.ROLLOUT_CONTAINED,
		)
		capabilityProbes shouldBe 0
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	private suspend fun bootstrapPolicy(ambientEnabled: Boolean): SourcePolicySnapshot {
		val policy = policyRepository.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = ambientEnabled,
				legacySettingsMigrationCompleted = true,
			),
		)
		if (ambientEnabled) {
			retentionSnapshot = retentionReader.installCurrent(
				database,
				TrackingSourceComponent.STEPS,
				policy.revision,
				requireNotNull(policy[TrackingSourceComponent.STEPS].ambientConsentEpoch),
				3L,
				"boot-1",
			)
		}
		return policy
	}

	private fun reconciler(
		capability: AmbientStepsCapability,
		onResolve: () -> Unit = {},
		currentPurposeReader: CurrentTrackingPurposeAvailabilityReader =
			readyAmbientStepsReader(),
	) = AmbientStepsDemandReconciler(
		resolveCapability = {
			onResolve()
			capability
		},
		sourceBroker = broker,
		sourceCallerDemandDispatcher = TestPurposeSourceCallerDemandDispatcher(
			broker = broker,
			retentionSnapshot = { _, _, _, _, _ -> retentionSnapshot },
		),
		bootClockDomainProvider = BootClockDomainProvider { "boot-1" },
		sourcePolicyRepository = policyRepository,
		trackingRolloutStateStore = rolloutStore,
		currentRetentionAuthority = { policyRevision, consentEpoch ->
			retentionReader.currentLiveAmbient(
				TrackingSourceComponent.STEPS,
				policyRevision,
				consentEpoch,
				3L,
			)
		},
		currentPurposeAvailabilityReader = currentPurposeReader,
	)

	private fun readyAmbientStepsReader(): CurrentTrackingPurposeAvailabilityReader {
		val identity = TrackingPurposeLeaseIdentity(
			sourcePurpose = TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 3L,
			rolloutRevision = 1L,
			executionRevision = 1L,
			ownerCasToken = "ambient-steps-test",
		)
		val published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			ambientSources = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
				(
					AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability.ready(
						AmbientTrackingSource.STEPS,
						AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
						identity,
					)
				),
		)
		return object : CurrentTrackingPurposeAvailabilityReader {
			override val availability = MutableStateFlow(
				CurrentTrackingPurposeAvailability(
					published,
					mapOf(
						identity.sourcePurpose to com.adsamcik.tracker.tracker.api
							.TrackingPurposeAuthorityVector(
								identity.sourcePurpose,
								identity.policyRevision,
								identity.consentEpoch,
								identity.collectedDataEpoch,
								identity.rolloutRevision,
								identity.executionRevision,
							),
					),
				),
			)
			override val authorityRevision = MutableStateFlow(
				TrackingPurposeAuthorityRevision(1L, 0L, 1L),
			)
		}
	}

	private fun boundary(elapsedRealtimeNanos: Long) = AmbientStepsDemandBoundary(
		bootId = "boot-1",
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = elapsedRealtimeNanos,
	)
}
