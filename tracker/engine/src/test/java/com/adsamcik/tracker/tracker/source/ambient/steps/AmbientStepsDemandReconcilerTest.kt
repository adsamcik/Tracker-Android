package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
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
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
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
	private var elapsed = 10L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
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
		broker = SourceBroker(database, rolloutStore)
		policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
	}

	@After
	fun tearDown() = database.close()

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

	@Test
	fun `missing retention approval blocks provider probe and demand`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		var capabilityProbes = 0
		val subject = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
			),
			onResolve = { capabilityProbes++ },
			currentRetentionAuthority = { _, _ ->
				com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
					.Unavailable(
						com.adsamcik.tracker.shared.preferences.retention
							.RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
					)
			},
		)

		subject.reconcileAt(boundary(100L)) shouldBe
			AmbientStepsDemandReconciliation.PolicyBlocked(
				provider = null,
				reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
			)
		capabilityProbes shouldBe 0
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	@Test
	fun `prior boot retention approval blocks provider probe and demand`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		var capabilityProbes = 0
		val subject = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
			),
			onResolve = { capabilityProbes++ },
			currentRetentionAuthority = { _, _ ->
				com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
					.Approved("test-retention", 1L, "old-boot", 1L, 1L)
			},
		)

		subject.reconcileAt(boundary(100L)) shouldBe
			AmbientStepsDemandReconciliation.PolicyBlocked(
				provider = null,
				reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
			)
		capabilityProbes shouldBe 0
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	private suspend fun bootstrapPolicy(ambientEnabled: Boolean) {
		database.sourceEvidenceStateDao().ensure()
		val snapshot = policyRepository.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = ambientEnabled,
				legacySettingsMigrationCompleted = true,
			),
		)
		if (ambientEnabled) {
			database.applyAmbientStepsRetentionDecision(
				AmbientStepsRetentionDecision.GrantLiveAmbient(
					opaquePolicyId = "test-retention",
					expectedCollectedDataEpoch = 0L,
					expectedSourcePolicyRevision = snapshot.revision,
					expectedAmbientConsentEpoch = requireNotNull(
						snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
					),
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = elapsed++,
					effectiveWallTimeMs = elapsed,
				),
			)
		}
	}

	private fun reconciler(
		capability: AmbientStepsCapability,
		onResolve: () -> Unit = {},
		currentRetentionAuthority: suspend (Long, Long) ->
			com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority = { _, _ ->
				com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
					.Approved("test-retention", 1L, "boot-1", 1L, 1L)
			},
	) = AmbientStepsDemandReconciler(
		resolveCapability = {
			onResolve()
			capability
		},
		sourceBroker = broker,
		bootClockDomainProvider = BootClockDomainProvider { "boot-1" },
		sourcePolicyRepository = policyRepository,
		trackingRolloutStateStore = rolloutStore,
		currentRetentionAuthority = currentRetentionAuthority,
	)

	private fun boundary(elapsedRealtimeNanos: Long) = AmbientStepsDemandBoundary(
		bootId = "boot-1",
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = elapsedRealtimeNanos,
	)
}
