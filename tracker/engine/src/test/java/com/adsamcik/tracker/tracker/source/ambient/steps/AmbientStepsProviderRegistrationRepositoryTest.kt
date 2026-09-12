package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
class AmbientStepsProviderRegistrationRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var broker: SourceBroker
	private lateinit var lifecycleStore: MutableCollectedDataLifecycleStore
	private lateinit var subject: AmbientStepsProviderRegistrationRepository
	private var policyElapsed = 1L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		val rollout = installCanonicalProductLanesForTest(
			database = database,
			bindings = listOf(
				ExecutableSourceLaneBinding(
					source = SourceKind.STEPS,
					bindingGeneration = 1L,
					projectionId = "ambient-steps-registration-test",
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
		lifecycleStore = MutableCollectedDataLifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 3L, retainedFromMs = null),
		)
		subject = AmbientStepsProviderRegistrationRepository(
			database = database,
			lifecycleStore = lifecycleStore,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `reservation is exact system rearmable ambient authority before provider work`() = runTest {
		val demand = select(AmbientStepsProvider.LOCAL_RECORDING_STEPS, boundary(100L))

		val reservation = subject.reserve(
			provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			expectedDemandId = demand.demandId,
			boundary = boundary(110L),
		)

		reservation.requiresProviderAcceptance shouldBe true
		reservation.predecessorState shouldBe null
		val physical = requireNotNull(
			database.sourceBrokerDao().registration(
				SourceKind.STEPS.stableCode,
				reservation.state.registrationGeneration,
			),
		)
		physical.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		physical.providerResidency shouldBe
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
		physical.providerProcessIncarnationId shouldBe null
		physical.ownerScope shouldBe SourceProviderPurposeScope.exactOwnerScope(
			SourceKind.STEPS.stableCode,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		physical.physicalConfigurationFingerprint shouldBe
			AmbientStepsProvider.LOCAL_RECORDING_STEPS.physicalConfigurationFingerprint()
		reservation.authorization.purposeEligibilityMask shouldBe
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT
		reservation.authorization.authorizedMembers.single().demandId shouldBe demand.demandId
		database.sourceRegistrationStateDao().get(
			SourceKind.STEPS.stableCode,
			physical.ownerScope,
		) shouldBe null
	}

	@Test
	fun `accepted reservation becomes the exact current ambient pointer`() = runTest {
		val demand = select(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, boundary(100L))
		val reservation = subject.reserve(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			demand.demandId,
			boundary(110L),
		)

		subject.accept(reservation) shouldBe null

		val physical = requireNotNull(subject.currentActive())
		physical.registrationGeneration shouldBe reservation.state.registrationGeneration
		physical.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		physical.acceptedAtMs shouldBe reservation.providerRequestAtMs
		physical.acceptedElapsedRealtimeNanos shouldBe
			reservation.providerRequestElapsedRealtimeNanos
		database.sourceRegistrationStateDao().get(
			SourceKind.STEPS.stableCode,
			physical.ownerScope,
		)?.registrationGeneration shouldBe reservation.state.registrationGeneration
	}

	@Test
	fun `provider switch accepts replacement before predecessor becomes retiring`() = runTest {
		val localDemand = select(AmbientStepsProvider.LOCAL_RECORDING_STEPS, boundary(100L))
		val local = subject.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			localDemand.demandId,
			boundary(110L),
		)
		subject.accept(local)
		val healthDemand = select(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, boundary(200L))
		val health = subject.reserve(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			healthDemand.demandId,
			boundary(210L),
		)

		val predecessor = requireNotNull(subject.accept(health))

		predecessor.registrationGeneration shouldBe local.state.registrationGeneration
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			local.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRING
		subject.currentActive()?.registrationGeneration shouldBe health.state.registrationGeneration
		subject.pendingRetirements().map { it.registrationGeneration } shouldBe
			listOf(local.state.registrationGeneration)
		subject.completeRetirement(predecessor) shouldBe true
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			local.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRED
	}

	@Test
	fun `provider cannot inherit an ambient demand for another acquisition mechanism`() = runTest {
		val demand = select(AmbientStepsProvider.LOCAL_RECORDING_STEPS, boundary(100L))

		shouldThrow<IllegalStateException> {
			subject.reserve(
				AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				demand.demandId,
				boundary(110L),
			)
		}

		database.sourceBrokerDao().maximumRegistrationGeneration(
			SourceKind.STEPS.stableCode,
		) shouldBe 0L
	}

	@Test
	fun `authority loss during provider work prevents acceptance and reservation can fail`() = runTest {
		val demand = select(AmbientStepsProvider.LOCAL_RECORDING_STEPS, boundary(100L))
		val reservation = subject.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			demand.demandId,
			boundary(110L),
		)
		broker.replaceAmbientStepsDemand(
			consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
			mechanism = null,
			bootId = BOOT_ID,
			elapsedRealtimeNanos = 120L,
			wallTimeMs = 120L,
		)

		shouldThrow<IllegalStateException> { subject.accept(reservation) }
		subject.failUnaccepted(
			registration = reservation,
			failureCode = "AUTHORITY_CHANGED_DURING_ACTIVATION",
			boundary = boundary(130L),
		) shouldBe true
		val failed = database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			reservation.state.registrationGeneration,
		)
		failed?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
		failed?.failureCode shouldBe "AUTHORITY_CHANGED_DURING_ACTIVATION"
		subject.currentActive() shouldBe null
	}

	@Test
	fun `unchanged active provider reuses identity without another acceptance`() = runTest {
		val demand = select(AmbientStepsProvider.LOCAL_RECORDING_STEPS, boundary(100L))
		val first = subject.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			demand.demandId,
			boundary(110L),
		)
		subject.accept(first)

		val reused = subject.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			demand.demandId,
			boundary(120L),
		)

		reused.requiresProviderAcceptance shouldBe false
		reused.state.registrationGeneration shouldBe first.state.registrationGeneration
		database.sourceBrokerDao().maximumRegistrationGeneration(
			SourceKind.STEPS.stableCode,
		) shouldBe first.state.registrationGeneration
	}

	@Test
	fun `new collected data epoch reserves a fresh identity for the same provider`() = runTest {
		val demand = select(AmbientStepsProvider.LOCAL_RECORDING_STEPS, boundary(100L))
		val first = subject.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			demand.demandId,
			boundary(110L),
		)
		subject.accept(first)
		lifecycleStore.set(CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = 115L))

		val replacement = subject.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			demand.demandId,
			boundary(120L),
		)

		replacement.requiresProviderAcceptance shouldBe true
		replacement.state.registrationGeneration shouldBe first.state.registrationGeneration + 1L
		(replacement.state.sourceInstanceId == first.state.sourceInstanceId) shouldBe false
		replacement.state.collectedDataEpoch shouldBe 4L
	}

	private suspend fun select(
		provider: AmbientStepsProvider,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsDemandReconciliation.DemandReady {
		val mechanism = when (provider) {
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
				AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
			AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
				AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
		}
		val result = broker.replaceAmbientStepsDemand(
			consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
			mechanism = mechanism,
			bootId = boundary.bootId,
			elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			wallTimeMs = boundary.wallTimeMs,
		)
		val active = result as AmbientStepsDemandResult.Active
		return AmbientStepsDemandReconciliation.DemandReady(
			provider = provider,
			importAccess = AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			optionalPermissions = emptySet(),
			demandId = active.demand.demandId,
		)
	}

	private fun boundary(elapsed: Long) = AmbientStepsDemandBoundary(
		bootId = BOOT_ID,
		elapsedRealtimeNanos = elapsed,
		wallTimeMs = elapsed,
	)

	private companion object {
		const val BOOT_ID = "boot-ambient-steps"
	}
}

private class MutableCollectedDataLifecycleStore(initial: CollectedDataLifecycleSnapshot) :
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
