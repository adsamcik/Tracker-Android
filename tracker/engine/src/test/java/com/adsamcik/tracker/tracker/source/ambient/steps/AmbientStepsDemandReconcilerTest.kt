package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
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
		val rollout = installCanonicalProductLanesForTest(
			database = database,
			bindings = listOf(binding),
			rolloutRevision = 1L,
		)
		broker = SourceBroker(database, rollout)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `ready capability creates exactly one provider-specific ambient demand`() = runTest {
		bootstrapPolicy(ambientEnabled = true)
		val subject = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				importAccess = AmbientStepsImportAccess.FOREGROUND_ONLY,
				optionalPermissions = setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ),
			),
		)

		val result = subject.reconcileAt(boundary(100L))

		val ready = result as AmbientStepsDemandReconciliation.DemandReady
		ready.provider shouldBe AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
		ready.importAccess shouldBe AmbientStepsImportAccess.FOREGROUND_ONLY
		ready.optionalPermissions shouldBe setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ)
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
	fun `ready provider cannot create demand without independent ambient consent`() = runTest {
		bootstrapPolicy(ambientEnabled = false)
		val result = reconciler(
			AmbientStepsCapability.ReadyForRegistration(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientStepsImportAccess.FOREGROUND_ONLY,
			),
		).reconcileAt(boundary(100L))

		result shouldBe AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			reason = AmbientStepsDemandBlockReason.CONSENT_REVOKED,
		)
		database.sourceBrokerDao().currentDemands(AmbientStepsDemandReconciler.CONSUMER_ID) shouldBe
			emptyList()
	}

	private suspend fun bootstrapPolicy(ambientEnabled: Boolean) {
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = ambientEnabled,
				legacySettingsMigrationCompleted = true,
			),
		)
	}

	private fun reconciler(capability: AmbientStepsCapability) = AmbientStepsDemandReconciler(
		resolveCapability = { capability },
		sourceBroker = broker,
		bootClockDomainProvider = BootClockDomainProvider { "boot-1" },
	)

	private fun boundary(elapsedRealtimeNanos: Long) = AmbientStepsDemandBoundary(
		bootId = "boot-1",
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = elapsedRealtimeNanos,
	)
}
