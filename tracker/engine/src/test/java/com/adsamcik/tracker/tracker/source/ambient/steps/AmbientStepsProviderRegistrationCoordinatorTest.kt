package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import io.kotest.matchers.shouldBe
import java.io.File
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
class AmbientStepsProviderRegistrationCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var broker: SourceBroker
	private lateinit var lifecycleStore: CoordinatorLifecycleStore
	private lateinit var registrations: AmbientStepsProviderRegistrationRepository
	private lateinit var cleanupFile: File
	private lateinit var cleanupStore: AmbientStepsProviderCleanupStore
	private lateinit var local: FakeAmbientStepsProviderBackend
	private lateinit var health: FakeAmbientStepsProviderBackend
	private lateinit var subject: AmbientStepsProviderRegistrationCoordinator
	private var policyElapsed = 1L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		cleanupFile = File(context.cacheDir, "ambient-steps-provider-coordinator-cleanup")
		deleteCleanupFiles()
		cleanupStore = AmbientStepsProviderCleanupStore(cleanupFile)
		val rollout = installCanonicalProductLanesForTest(
			database = database,
			bindings = listOf(
				ExecutableSourceLaneBinding(
					source = SourceKind.STEPS,
					bindingGeneration = 1L,
					projectionId = "ambient-steps-provider-coordinator-test",
					projectionVersion = 1,
					captureModes = setOf(CaptureReachabilityMode.AMBIENT),
				),
			),
			rolloutRevision = 1L,
		)
		broker = SourceBroker(database, rollout)
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(BOOT_ID, policyElapsed++, policyElapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		lifecycleStore = CoordinatorLifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 3L, retainedFromMs = null),
		)
		registrations = AmbientStepsProviderRegistrationRepository(
			database = database,
			lifecycleStore = lifecycleStore,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		)
		local = FakeAmbientStepsProviderBackend(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		health = FakeAmbientStepsProviderBackend(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS)
		subject = coordinator()
	}

	@After
	fun tearDown() {
		database.close()
		deleteCleanupFiles()
	}

	@Test
	fun `provider activation observes reserved authority before acceptance`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		var statusAtProviderCall: String? = null
		local.onEnsure = {
			statusAtProviderCall = registrations.pendingReservations().single().status
		}

		val result = subject.reconcile(demand, boundary(110L))

		val active = result as AmbientStepsProviderRegistrationResult.Active
		active.provider shouldBe AmbientStepsProvider.LOCAL_RECORDING_STEPS
		active.rearmedInThisProcess shouldBe true
		statusAtProviderCall shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		registrations.currentActive()?.registrationGeneration shouldBe
			active.registrationGeneration
		local.ensureCalls shouldBe 1
		local.removeCalls shouldBe 0
		cleanupStore.read().pending shouldBe
			setOf(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
	}

	@Test
	fun `failed provider activation is compensated before reservation fails`() = runTest {
		local.failEnsure = true
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)

		val result = subject.reconcile(demand, boundary(110L))

		result shouldBe AmbientStepsProviderRegistrationResult.Failed(
			selectedProvider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			failure = AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED,
			retryable = true,
		)
		local.ensureCalls shouldBe 1
		local.removeCalls shouldBe 1
		val failed = database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			1L,
		)
		failed?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
		registrations.currentActive() shouldBe null
		cleanupStore.read().pending shouldBe emptySet()
	}

	@Test
	fun `authority loss during provider activation removes provider and rejects acceptance`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		local.onEnsure = {
			broker.replaceAmbientStepsDemand(
				consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
				mechanism = null,
				bootId = BOOT_ID,
				elapsedRealtimeNanos = 115L,
				wallTimeMs = 115L,
			)
		}

		val result = subject.reconcile(demand, boundary(110L))

		result shouldBe AmbientStepsProviderRegistrationResult.Failed(
			selectedProvider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			failure = AmbientStepsProviderRegistrationFailure.AUTHORITY_CHANGED_DURING_ACTIVATION,
			retryable = true,
		)
		local.removeCalls shouldBe 1
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			1L,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
	}

	@Test
	fun `provider switch accepts replacement before old provider removal`() = runTest {
		val events = mutableListOf<String>()
		local.onEnsure = { events += "local-active" }
		health.onEnsure = { events += "health-active" }
		subject.reconcile(
			ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L),
			boundary(110L),
		)
		local.onRemove = {
			val active = requireNotNull(registrations.currentActive())
			active.ambientStepsProviderOrNull() shouldBe
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
			events += "local-removed"
		}

		val result = subject.reconcile(
			ready(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, 200L),
			boundary(210L),
		)

		(result as AmbientStepsProviderRegistrationResult.Active).provider shouldBe
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
		events shouldBe listOf("local-active", "health-active", "local-removed")
		local.removeCalls shouldBe 1
		health.removeCalls shouldBe 0
		registrations.pendingRetirements() shouldBe emptyList()
		cleanupStore.read().pending shouldBe
			setOf(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS)
	}

	@Test
	fun `same global provider replacement never removes newly accepted subscription`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		val first = subject.reconcile(demand, boundary(110L)) as
			AmbientStepsProviderRegistrationResult.Active
		lifecycleStore.set(CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = 120L))

		val second = subject.reconcile(demand, boundary(130L)) as
			AmbientStepsProviderRegistrationResult.Active

		second.registrationGeneration shouldBe first.registrationGeneration + 1L
		local.ensureCalls shouldBe 2
		local.removeCalls shouldBe 0
		registrations.pendingRetirements() shouldBe emptyList()
		cleanupStore.read().pending shouldBe
			setOf(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			first.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRED
	}

	@Test
	fun `new coordinator rearms persisted active provider once per process`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		subject.reconcile(demand, boundary(110L))
		local.ensureCalls shouldBe 1
		subject = coordinator()

		val cold = subject.reconcile(demand, boundary(120L))
		val warm = subject.reconcile(demand, boundary(130L))

		(cold as AmbientStepsProviderRegistrationResult.Active).rearmedInThisProcess shouldBe true
		(warm as AmbientStepsProviderRegistrationResult.Active).rearmedInThisProcess shouldBe false
		local.ensureCalls shouldBe 2
	}

	@Test
	fun `inactive demand retires and removes accepted provider`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		subject.reconcile(demand, boundary(110L))
		retireDemand(120L)
		val inactive = AmbientStepsDemandReconciliation.Unavailable(
			healthConnect = HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
			localRecording = LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
		)

		val result = subject.reconcile(inactive, boundary(130L))

		result shouldBe AmbientStepsProviderRegistrationResult.Inactive(inactive)
		local.removeCalls shouldBe 1
		registrations.currentActive() shouldBe null
		registrations.pendingRetirements() shouldBe emptyList()
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			1L,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRED
		cleanupStore.read().pending shouldBe emptySet()
	}

	@Test
	fun `provider removal failure preserves typed retryable cleanup debt`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		subject.reconcile(demand, boundary(110L))
		retireDemand(120L)
		local.failRemove = true
		val inactive = AmbientStepsDemandReconciliation.Unavailable(
			healthConnect = HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
			localRecording = LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
		)

		val result = subject.reconcile(inactive, boundary(130L))

		result shouldBe AmbientStepsProviderRegistrationResult.Degraded(
			selectedProvider = null,
			activeRegistrationGeneration = null,
			failure = AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
			retryable = true,
		)
		registrations.pendingRetirements().single().status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		cleanupStore.read().pending shouldBe
			setOf(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
	}

	@Test
	fun `deletion cleanup retries from no backup journal after Room authority is erased`() = runTest {
		val demand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		subject.reconcile(demand, boundary(110L))
		local.failRemove = true

		val first = subject.closeForCollectedDataDeletion()

		first shouldBe AmbientStepsProviderCleanupResult(
			complete = false,
			pendingProviders = setOf(AmbientStepsProvider.LOCAL_RECORDING_STEPS),
			failure = AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
			retryable = true,
		)
		database.withTransaction {
			database.sourceBrokerDao().deleteAllAuthorizations()
			database.sourceBrokerDao().deleteAllRegistrations()
			database.sourceRegistrationStateDao().deleteAll()
			database.sourceBrokerDao().deleteAllDemands()
		}
		local.failRemove = false

		subject.closeForCollectedDataDeletion() shouldBe AmbientStepsProviderCleanupResult(
			complete = true,
			pendingProviders = emptySet(),
		)
		local.removeCalls shouldBe 2
		cleanupStore.read().pending shouldBe emptySet()
	}

	@Test
	fun `mismatched interrupted reservation is removed before selected provider starts`() = runTest {
		val localDemand = ready(AmbientStepsProvider.LOCAL_RECORDING_STEPS, 100L)
		val interrupted = registrations.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			localDemand.demandId,
			boundary(110L),
		)
		cleanupStore.addPending(AmbientStepsProvider.LOCAL_RECORDING_STEPS)
		local.ensureActive()
		val healthDemand = ready(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, 120L)

		val result = subject.reconcile(healthDemand, boundary(130L))

		(result as AmbientStepsProviderRegistrationResult.Active).provider shouldBe
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
		local.removeCalls shouldBe 1
		health.ensureCalls shouldBe 1
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			interrupted.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
		cleanupStore.read().pending shouldBe
			setOf(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS)
	}

	private fun coordinator() = AmbientStepsProviderRegistrationCoordinator(
		registrations = registrations,
		cleanupStore = cleanupStore,
		providerBackends = setOf(local, health),
	)

	private fun deleteCleanupFiles() {
		cleanupFile.delete()
		File("${cleanupFile.path}.bak").delete()
		File("${cleanupFile.path}.new").delete()
	}

	private suspend fun ready(
		provider: AmbientStepsProvider,
		at: Long,
	): AmbientStepsDemandReconciliation.DemandReady {
		val mechanism = when (provider) {
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
				AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
			AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
				AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
		}
		val active = broker.replaceAmbientStepsDemand(
			consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
			mechanism = mechanism,
			bootId = BOOT_ID,
			elapsedRealtimeNanos = at,
			wallTimeMs = at,
		) as AmbientStepsDemandResult.Active
		return AmbientStepsDemandReconciliation.DemandReady(
			provider = provider,
			importAccess = AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			optionalPermissions = emptySet(),
			demandId = active.demand.demandId,
		)
	}

	private suspend fun retireDemand(at: Long) {
		broker.replaceAmbientStepsDemand(
			consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
			mechanism = null,
			bootId = BOOT_ID,
			elapsedRealtimeNanos = at,
			wallTimeMs = at,
		)
	}

	private fun boundary(at: Long) = AmbientStepsDemandBoundary(
		bootId = BOOT_ID,
		elapsedRealtimeNanos = at,
		wallTimeMs = at,
	)

	private companion object {
		const val BOOT_ID = "boot-ambient-steps-coordinator"
	}
}

private class FakeAmbientStepsProviderBackend(
	override val provider: AmbientStepsProvider,
) : AmbientStepsProviderBackend {
	var ensureCalls = 0
	var removeCalls = 0
	var failEnsure = false
	var failRemove = false
	var onEnsure: suspend () -> Unit = {}
	var onRemove: suspend () -> Unit = {}

	override suspend fun ensureActive() {
		ensureCalls += 1
		onEnsure()
		if (failEnsure) error("provider activation failed")
	}

	override suspend fun remove() {
		removeCalls += 1
		onRemove()
		if (failRemove) error("provider removal failed")
	}
}

private class CoordinatorLifecycleStore(initial: CollectedDataLifecycleSnapshot) :
	CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs)
			.also { state.emit(it) }

	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(retainedFromMs = retainedFromMs).also { state.emit(it) }

	suspend fun set(snapshot: CollectedDataLifecycleSnapshot) {
		state.emit(snapshot)
	}
}
