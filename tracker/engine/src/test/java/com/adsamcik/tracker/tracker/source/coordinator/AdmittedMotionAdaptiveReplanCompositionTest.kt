package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.control.CollectionAcquisitionProfile
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.CollectionMotionState
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.ingress.AdmissionFailureCode
import com.adsamcik.tracker.tracker.source.ingress.AdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.OwnedSourceShutdown
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real admission sink, motion controller, profile Flow, session planner, Room plan store and runtime
 * registry. Admission decisions and durable lifecycle authority are fixtures; owners record the
 * actual typed plans dispatched by the registry. This does not exercise Android callbacks or the
 * private TrackerService collector, and does not prove a physical provider survives reconfiguration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdmittedMotionAdaptiveReplanCompositionTest {

	private lateinit var database: AppDatabase
	private lateinit var subject: TrackerServiceSourceSession
	private lateinit var ingress: DurableSourceIngress
	private lateinit var sink: SourceEventSink
	private lateinit var motion: CollectionMotionController
	private lateinit var owners: Map<SourceKind, RecordingOwner>
	private var constraints = SourceKind.entries.associateWith { SourceConstraint() }
	private var reconfigurationCount = 0
	private var ordinal = 0L
	private val outcomes = mutableListOf<SourceSessionReconfigureOutcome>()
	private val observedProfile = MutableStateFlow<CollectionAcquisitionProfile?>(null)

	@Before
	fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		observedProfile.value = null
		motion = CollectionMotionController().also { it.configureScope("collection", BOOT, 0L) }
		ingress = mockk()
		coEvery { ingress.admit(any()) } answers {
			AdmissionResult.Admitted(SourceEventId("event-${++ordinal}"), ordinal)
		}
		val recovery = mockk<SourcePipelineRecovery>(relaxed = true)
		sink = DurableSourceEventSinkFactory(ingress, recovery, motion).unbound
		owners = SourceKind.entries.associateWith(::RecordingOwner)
		val registry = SourceRuntimeRegistry(owners.values.toSet())
		val lifecycle = mockk<AuthoritativeSessionCoordinator>()
		val store = mockk<ActiveTrackingSessionStore>()
		var descriptor = ActiveTrackingSessionDescriptor(
			isUserInitiated = true, isAmbient = false, policyTier = PolicyTier.PRECISION,
			logicalTrackingId = LOGICAL, serviceRunId = RUN,
			sourceCallerAuthorityReference = REFERENCE,
		)
		coEvery { store.read() } answers { ActiveTrackingSessionStoreResult.Success(descriptor) }
		coEvery { store.replaceExact(any(), any()) } answers {
			if (firstArg<ActiveTrackingSessionDescriptor>() == descriptor) {
				descriptor = secondArg()
			}
			ActiveTrackingSessionStoreResult.Success(descriptor)
		}
		coEvery { lifecycle.start(any()) } coAnswers {
			val plan = firstArg<SessionStartRequest>().plan
			val applied = dispatch(plan, registry, starting = true)
			SessionStartResult.Started(LOGICAL, RUN, applied, DesiredPlanStatus.EFFECTIVE, REFERENCE)
		}
		coEvery { lifecycle.reconfigure(any()) } coAnswers {
			reconfigurationCount++
			val plan = firstArg<SessionReconfigureRequest>().plan
			val applied = dispatch(plan, registry, starting = false)
			SessionReconfigureResult.Applied(plan.revision, applied, DesiredPlanStatus.EFFECTIVE, REFERENCE)
		}
		val status = DefaultTrackingSettingsStatusProvider(
			SemanticAcquisitionPlanFactory(), SourcePlanResolver(),
			QualitativeBatteryImpactEstimator(), TrackingCoordinatorTelemetry(),
		)
		val bindings = SourceKind.entries.map { source ->
			ExecutableSourceLaneBinding(source, 1L, "fixture-${source.name.lowercase()}", 1,
				setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE))
		}.toTypedArray()
		subject = TrackerServiceSourceSession(
			database, lifecycle, SemanticAcquisitionPlanFactory(), SourcePlanResolver(),
			TrackingCoordinatorTelemetry(), status, Provider { ReadyGate },
			RoomTrackingRolloutStateStore(database, ExecutableSourceLaneCatalog.explicit(*bindings)), store,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admitted sessionless Steps restore owner plans from stationary without changing Steps request`() = runTest {
		startAndObserve(settings())
		makeStationary()
		awaitCurrentProfile()
		assertStationaryOwners()
		val cheapSteps = latestSteps().copy(revision = 0L)

		sink.admit(candidate(steps(), seconds(101), 3L))
		awaitCurrentProfile()

		motion.policy.value.motionState shouldBe CollectionMotionState.PEDESTRIAN
		assertMovingOwners()
		latestSteps().copy(revision = 0L) shouldBe cheapSteps
		latestSteps().maximumReportLatencyMs shouldBe 5_000L
		outcomes.last().shouldBeInstanceOf<SourceSessionReconfigureOutcome.Applied>()
	}

	@Test
	fun `admitted Location speed restores live radio plans while unchosen Activity stays off`() = runTest {
		startAndObserve(settings())
		makeStationary()
		awaitCurrentProfile()

		sink.admit(candidate(
			LocationFixPayload(50.0, 14.0, 5f, null, null, 8f, null, "gps"), seconds(101), 3L,
		).copy(locationSpeedAccuracyMetersPerSecond = 0.5f))
		awaitCurrentProfile()

		motion.policy.value.motionState shouldBe CollectionMotionState.VEHICLE
		assertMovingOwners()
		owners.getValue(SourceKind.ACTIVITY).plans.all { !it.enabled } shouldBe true
	}

	@Test
	fun `admitted Activity transition restores live plans without enabling Activity capture`() = runTest {
		startAndObserve(settings())
		makeStationary()
		awaitCurrentProfile()

		sink.admit(candidate(ActivityTransitionPayload(StableActivityTypeCode.WALKING, 0, seconds(101)),
			seconds(101), 3L))
		awaitCurrentProfile()

		motion.policy.value.motionState shouldBe CollectionMotionState.PEDESTRIAN
		assertMovingOwners()
		owners.getValue(SourceKind.ACTIVITY).plans.all { !it.enabled } shouldBe true
	}

	@Test
	fun `rejected revoked source and stale replay cannot alter stationary plans`() = runTest {
		startAndObserve(settings())
		makeStationary()
		awaitCurrentProfile()
		val previousPlans = owners.mapValues { it.value.plans.size }
		val previousReconfigurations = reconfigurationCount
		coEvery { ingress.admit(any()) } returns AdmissionResult.PermanentFailure(
			AdmissionFailureCode.COLLECTION_SUPPRESSED)
		sink.admit(candidate(steps(), seconds(101), 3L))
		awaitCurrentProfile()
		coEvery { ingress.admit(any()) } returns AdmissionResult.Duplicate(SourceEventId("old-event"), 1L)
		sink.admit(candidate(ActivityTransitionPayload(StableActivityTypeCode.IN_VEHICLE, 0, seconds(20)),
			seconds(20), 1L))
		// A first delivery from another boot also cannot affect the active collection scope.
		sink.admit(candidate(steps(boot = "another-boot"), seconds(101), 4L).copy(clockDomainId = "another-boot"))
		// Durable old Location is still too stale to direct current acquisition.
		sink.admit(candidate(LocationFixPayload(50.0, 14.0, 5f, null, null, 8f, null, "gps"),
			seconds(10), 1L).copy(receivedElapsedRealtimeNanos = seconds(101),
			locationSpeedAccuracyMetersPerSecond = 0.5f))
		awaitCurrentProfile()

		assertStationaryOwners()
		owners.mapValues { it.value.plans.size } shouldBe previousPlans
		reconfigurationCount shouldBe previousReconfigurations
	}

	@Test
	fun `provenance-only motion change and repeated policy tick do not reconfigure owners`() = runTest {
		startAndObserve(settings())
		sink.admit(candidate(steps(atSeconds = 1), seconds(1), 1L))
		awaitCurrentProfile()
		val previousReconfigurations = reconfigurationCount
		val previousPlans = owners.mapValues { it.value.plans.size }
		sink.admit(candidate(ActivityTransitionPayload(StableActivityTypeCode.IN_VEHICLE, 0, seconds(2)),
			seconds(2), 2L))
		motion.tick(seconds(3))
		awaitCurrentProfile()
		subject.reconfigure(inputs(settings(), motion.policy.value.acquisitionProfile)) shouldBe
			SourceSessionReconfigureOutcome.Unchanged

		motion.policy.value.motionState shouldBe CollectionMotionState.VEHICLE
		reconfigurationCount shouldBe previousReconfigurations
		owners.mapValues { it.value.plans.size } shouldBe previousPlans
	}

	@Test
	fun `disabled Location and radio participation stay off while independent Steps hints remain useful`() = runTest {
		startAndObserve(settings(location = false, radios = false))
		makeStationary()
		awaitCurrentProfile()
		sink.admit(candidate(steps(), seconds(101), 3L))
		awaitCurrentProfile()

		latestSteps().enabled shouldBe true
		listOf(SourceKind.LOCATION, SourceKind.WIFI, SourceKind.CELL, SourceKind.ACTIVITY,
			SourceKind.PRESSURE).forEach { source ->
			owners.getValue(source).plans.all { !it.enabled } shouldBe true
		}
	}

	@Test
	fun `Location permission loss disables its request while admitted Steps restore radio siblings`() = runTest {
		startAndObserve(settings())
		makeStationary()
		awaitCurrentProfile()
		constraints = constraints + (SourceKind.LOCATION to SourceConstraint(permissionGranted = false))
		sink.admit(candidate(steps(), seconds(101), 3L))
		awaitCurrentProfile()

		(owners.getValue(SourceKind.LOCATION).plans.last() as LocationPlan).mode shouldBe LocationMode.DISABLED
		latestSteps().enabled shouldBe true
		(owners.getValue(SourceKind.WIFI).plans.last() as WifiPlan).mode shouldBe WifiMode.ACTIVE_ATTEMPTS
		(owners.getValue(SourceKind.CELL).plans.last() as CellPlan).mode shouldBe CellMode.OBSERVE_AND_SPARSE_REFRESH
		outcomes.last().shouldBeInstanceOf<SourceSessionReconfigureOutcome.Applied>()
	}

	private suspend fun TestScope.startAndObserve(settings: TrackingParamsState): Job {
		RoomSourcePolicyRepository(database) { SourcePolicyEffectiveTime(BOOT, 0L, 0L) }
			.bootstrapFromLegacy(settings)
		val rollout = TrackingRolloutState.eventCanonical(SourceKind.entries.toSet(), revision = 1L)
		subject.start(SourceSessionStartRequest(
			rollout, TrackingSessionOwnership.resolve(rollout, settings), LOGICAL, RUN,
			SessionStartOrigin.MANUAL_FOREGROUND_START, foregroundCapabilityFlags = 1L,
			planInputs = inputs(settings, motion.policy.value.acquisitionProfile), ownerToken = "owner",
		)).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()
		// Same profile selection used by TrackerService; the Android service lifecycle is not mocked as proven.
		val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
			motion.policy.map { it.acquisitionProfile }.distinctUntilChanged().collect { profile ->
				outcomes += subject.reconfigure(inputs(settings, profile))
				observedProfile.value = profile
			}
		}
		awaitCurrentProfile()
		return observer
	}

	private suspend fun awaitCurrentProfile() {
		observedProfile.first { it == motion.policy.value.acquisitionProfile }
	}

	private suspend fun makeStationary() {
		sink.admit(candidate(ActivityRecognitionPayload(StableActivityTypeCode.STILL, 95, null), seconds(1), 1L))
		sink.admit(candidate(ActivityRecognitionPayload(StableActivityTypeCode.STILL, 95, null), seconds(25), 2L))
		motion.tick(seconds(100))
	}

	private fun assertStationaryOwners() {
		motion.policy.value.motionState shouldBe CollectionMotionState.STATIONARY
		(owners.getValue(SourceKind.LOCATION).plans.last() as LocationPlan).let {
			it.mode shouldBe LocationMode.BALANCED
			it.requestedIntervalMs shouldBe 30_000L
		}
		(owners.getValue(SourceKind.WIFI).plans.last() as WifiPlan).mode shouldBe WifiMode.BROADCAST_DRIVEN
		(owners.getValue(SourceKind.CELL).plans.last() as CellPlan).mode shouldBe CellMode.OBSERVE_CHANGES
	}

	private fun assertMovingOwners() {
		(owners.getValue(SourceKind.LOCATION).plans.last() as LocationPlan).let {
			it.mode shouldBe LocationMode.HIGH_ACCURACY
			it.requestedIntervalMs shouldBe 1_000L
		}
		(owners.getValue(SourceKind.WIFI).plans.last() as WifiPlan).mode shouldBe WifiMode.ACTIVE_ATTEMPTS
		(owners.getValue(SourceKind.CELL).plans.last() as CellPlan).mode shouldBe CellMode.OBSERVE_AND_SPARSE_REFRESH
	}

	private fun latestSteps() = owners.getValue(SourceKind.STEPS).plans.last() as StepsPlan

	private suspend fun dispatch(plan: AcquisitionPlanRevision, registry: SourceRuntimeRegistry,
		starting: Boolean): List<AppliedSourcePlan> {
		val store = RoomSourcePlanStore(database, SourcePlanCodec())
		store.persistDesired(plan, DesiredPlanStatus.EFFECTIVE)
		return plan.plans.values.map { sourcePlan ->
			val claim = SourceRuntimeClaim(sourcePlan.source, "fixture-${plan.revision}", 1, plan.revision, LOGICAL, RUN)
			val state = if (starting) {
				registry.start(claim, sourcePlan, sink).shouldBeInstanceOf<SourceStartResult.Started>().applied
			} else {
				registry.reconfigure(claim, sourcePlan, sink).shouldBeInstanceOf<SourceApplyResult.Applied>().state
			}
			store.saveApplied(state, sourcePlan, plan.createdAtMs)
			state
		}
	}

	private fun inputs(settings: TrackingParamsState, profile: CollectionAcquisitionProfile) = SourceSessionPlanInputs(
		settings, SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, setOf(1),
			SourceKind.entries.filter { source -> when (source) {
				SourceKind.LOCATION -> settings.locationEnabled
				SourceKind.STEPS -> settings.stepsEnabled
				SourceKind.WIFI -> settings.wifiEnabled
				SourceKind.CELL -> settings.cellEnabled
				else -> false
			} }.toSet()),
		PlanResolutionContext(constraints, false, false, false, profile, locationProfileLevel = 5,
			locationLiveFocus = settings.locationEnabled), emptyList(), BOOT,
	)

	private fun settings(location: Boolean = true, radios: Boolean = true) = TrackingParamsState(
		locationEnabled = location, activityEnabled = false, stepsEnabled = true,
		wifiEnabled = radios, cellEnabled = radios, barometerEnabled = false,
		sourcePolicyRevision = 1L, balanceProfileVersion = 1, overallBalanceLevel = 5,
		locationProfileVersion = 1, locationBalanceLevel = 5,
		legacySettingsMigrationCompleted = true,
	)

	private fun steps(atSeconds: Long = 101, boot: String = BOOT) = StepCounterWindowPayload(
		boot, 100L, 103L, 3L, seconds(atSeconds - 1), seconds(atSeconds), 1L, 2L, baselineReset = false)

	private fun <T : SourcePayload> candidate(payload: T, at: Long, sequence: Long) = SourceEvidenceCandidate(
		providerDedupKey = null, logicalTrackingId = null, serviceRunId = null, source = payload.source,
		sourceInstanceId = SourceInstanceId("fixture-${payload.source.name.lowercase()}"),
		registrationGeneration = 1L, sourceSequence = sequence, configRevision = 1L,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION, clockDomainId = BOOT,
		observedElapsedRealtimeNanos = at, receivedElapsedRealtimeNanos = at,
		wallTimeMs = at / 1_000_000L, wallTimeUncertaintyMs = 0L, capturedCollectedDataEpoch = 0L,
		acquiredAtMs = at / 1_000_000L, quality = SourceQuality(), payloadVersion = 1, payload = payload,
	)

	private class RecordingOwner(override val source: SourceKind) : ClaimedSourceRuntime<SourcePlan> {
		val plans = mutableListOf<SourcePlan>()
		override val capabilities = MutableStateFlow(SourceCapabilities(true, true, false, null, null))
		override suspend fun start(plan: SourcePlan, sink: SourceEventSink): SourceStartResult =
			SourceStartResult.Started(record(plan))
		override suspend fun start(claim: SourceRuntimeClaim, plan: SourcePlan, sink: SourceEventSink): SourceStartResult {
			claim.source shouldBe source
			return start(plan, sink)
		}
		override suspend fun reconfigure(plan: SourcePlan, sink: SourceEventSink): SourceApplyResult =
			SourceApplyResult.Applied(record(plan))
		override suspend fun reconfigure(claim: SourceRuntimeClaim, plan: SourcePlan, sink: SourceEventSink): SourceApplyResult {
			claim.source shouldBe source
			return reconfigure(plan, sink)
		}
		override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = error("Not part of this fixture")
		override suspend fun close() = Unit
		override suspend fun shutdownIfOwned(claim: SourceRuntimeClaim, cutoff: SessionCutoff) = OwnedSourceShutdown.NotOwned
		private fun record(plan: SourcePlan): AppliedSourcePlan {
			plan.source shouldBe source
			plans += plan
			return AppliedSourcePlan(plan.revision, plan.revision, source,
				SourceInstanceId("fixture-${source.name.lowercase()}").takeIf { plan.enabled },
				plan.revision.takeIf { plan.enabled }, plan.revision, SourceApplyStatus.APPLIED)
		}
	}

	private object ReadyGate : TrackingStartupGate {
		override val isReady = true
		override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val BOOT = "boot-1"
		const val LOGICAL = "logical"
		const val RUN = "run"
		val REFERENCE = SourceCallerReplayReference("fixture-motion-authority")
		fun seconds(value: Long) = value * 1_000_000_000L
	}
}
