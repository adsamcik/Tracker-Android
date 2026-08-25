package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerServiceSourceSessionTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: AuthoritativeSessionCoordinator
	private lateinit var subject: TrackerServiceSourceSession
	private lateinit var startupGate: FakeTrackingStartupGate
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore
	private lateinit var statusProvider: DefaultTrackingSettingsStatusProvider

	@Before
	fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		lifecycle = mockk()
		startupGate = FakeTrackingStartupGate()
		rolloutStore = RoomTrackingRolloutStateStore(
			database,
			ExecutableSourceLaneCatalog.explicit(TEST_STEPS_BINDING),
		)
		statusProvider = DefaultTrackingSettingsStatusProvider(
			SemanticAcquisitionPlanFactory(),
			SourcePlanResolver(),
			QualitativeBatteryImpactEstimator(),
			TrackingCoordinatorTelemetry(),
		)
		subject = TrackerServiceSourceSession(
			database,
			lifecycle,
			SemanticAcquisitionPlanFactory(),
			SourcePlanResolver(),
			TrackingCoordinatorTelemetry(),
			statusProvider,
			Provider { startupGate },
			rolloutStore,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `zero reachable capture sources fail closed instead of creating an empty service session`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val initialSettings = settings(steps = SourceCollectionFrequency.OFF)
		val initialOwnership = TrackingSessionOwnership.resolve(rollout, initialSettings)
		val outcome = subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = initialOwnership,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(initialSettings),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Rejected>()

		outcome.result shouldBe SessionStartResult.InvalidIntent("ZERO_REACHABLE_CAPTURE_SOURCES")
		subject.reconfigure(inputs(settings(SourceCollectionFrequency.BALANCED))) shouldBe
			SourceSessionReconfigureOutcome.NotActive
	}

	@Test
	fun `automatic start preserves exact trigger evidence through the service session boundary`() = runTest {
		val rollout = allEventShadow(revision = 5, automaticCapture = true)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 7)
		val trigger = AutomaticTrackingStartTrigger(
			triggerId = "activity-transition:boot-1:42",
			kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
			bootId = "boot-1",
			observedElapsedRealtimeNanos = 100L,
			receivedElapsedRealtimeNanos = 120L,
			expiresElapsedRealtimeNanos = 60_000_000_120L,
			automationEpoch = 9L,
			startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
			sourcePolicyRevision = 7L,
			requestedCaptureSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
			intendedCaptureSourceMask = 1L shl (SourceKind.STEPS.stableCode - 1),
			intendedForegroundServiceTypeMask = 1L,
			collectedDataEpoch = 3L,
		)
		val captured = slot<SessionStartRequest>()
		coEvery { lifecycle.start(capture(captured)) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)

		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				automaticTrigger = trigger,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()

		captured.captured.automaticTrigger shouldBe trigger
		captured.captured.origin shouldBe SessionStartOrigin.AUTOMATIC_BACKGROUND_START
		captured.captured.controlDependencies shouldBe setOf(SourceKind.ACTIVITY)
	}

	@Test
	fun `manual-only lane rejects the same settings for automatic session capture`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 7)

		val outcome = subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical-auto-rejected",
				serviceRunId = "run-auto-rejected",
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				automaticTrigger = automaticTrigger(SourceKind.STEPS),
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner-auto-rejected",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Rejected>()

		outcome.result shouldBe SessionStartResult.InvalidIntent("ZERO_REACHABLE_CAPTURE_SOURCES")
		coVerify(exactly = 0) { lifecycle.start(any()) }
	}

	@Test
	fun `automatic status blocks a configured manual-only sibling`() = runTest {
		val rollout = TrackingRolloutState.eventShadow(
			sources = setOf(SourceKind.LOCATION, SourceKind.STEPS),
			revision = 5L,
			captureModes = mapOf(
				SourceKind.LOCATION to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				SourceKind.STEPS to setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE),
			),
		)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 7).copy(
			locationEnabled = true,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.BALANCED,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.BALANCED,
				pressure = SourceCollectionFrequency.OFF,
				wifi = SourceCollectionFrequency.OFF,
				cell = SourceCollectionFrequency.OFF,
			),
		)
		val captured = slot<SessionStartRequest>()
		coEvery { lifecycle.start(capture(captured)) } returns SessionStartResult.Started(
			logicalTrackingId = "logical-mode-status",
			serviceRunId = "run-mode-status",
			applied = listOf(
				AppliedSourcePlan(
					desiredRevision = 1L,
					appliedRevision = 1L,
					source = SourceKind.STEPS,
					sourceInstanceId = null,
					registrationGeneration = null,
					appliedAtElapsedRealtimeNanos = 1L,
					status = SourceApplyStatus.APPLIED,
				),
			),
			planStatus = DesiredPlanStatus.EFFECTIVE,
		)

		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(
					rollout,
					enabled,
					CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
				),
				logicalTrackingId = "logical-mode-status",
				serviceRunId = "run-mode-status",
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				automaticTrigger = automaticTrigger(SourceKind.STEPS),
				foregroundCapabilityFlags = 1L,
				planInputs = inputs(enabled),
				ownerToken = "owner-mode-status",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()

		captured.captured.plan.plans.filterValues { plan -> plan.enabled }.keys shouldBe
			setOf(SourceKind.STEPS)
		val statuses = statusProvider.runtimeStatus.value.sources
		statuses.getValue(SourceKind.STEPS).state shouldBe EffectiveSourceState.ACTIVE
		statuses.getValue(SourceKind.LOCATION).state shouldBe EffectiveSourceState.BLOCKED
		statuses.getValue(SourceKind.LOCATION).reasonCodes shouldContain
			"AUTOMATIC_SESSION_CAPTURE_NOT_REACHABLE"
	}

	@Test
	fun `cancelled prepared apply retains ownership until partial runtime cleanup completes`() = runTest {
		val rollout = rolloutStore.installAndActivateShadowLane(
			binding = TEST_STEPS_BINDING,
			rolloutRevision = 5L,
			updatedAtMs = 1L,
		).rollout
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = "run",
				logicalTrackingId = "logical",
				state = SessionLifecycleState.STARTING.name,
				desiredPlanRevision = 1L,
				rolloutRevision = rollout.revision,
				foregroundCapabilityFlags = 1L,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				completedAtMs = null,
				completionReason = null,
			),
		)
		val applyStarted = CompletableDeferred<Unit>()
		coEvery { lifecycle.applyPreparedAndroidStart(any(), any(), any(), any(), any()) } coAnswers {
			applyStarted.complete(Unit)
			awaitCancellation()
		}
		coEvery { lifecycle.stop(any()) } returns SessionStopResult.NoActiveSession
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1L)
		val applying = async {
			subject.applyPreparedAndroidStart(
				claim = ClaimedPreparedSessionStart(
					token = PreparedTrackingStartToken("prepared-run"),
					logicalTrackingId = "logical",
					serviceRunId = "run",
					manifestRevision = 1L,
					intentRevision = 1L,
					planRevision = 1L,
					sourcePolicyRevision = 1L,
					startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
					sessionMode = SessionMode.MANUAL,
					acceptedSources = setOf(SourceKind.STEPS),
					desiredForegroundCapabilityFlags = 1L,
					intent = mockk(),
					automaticTrigger = null,
					isUserInitiated = true,
					isAmbient = false,
					alreadyForegroundAccepted = true,
				),
				commandGeneration = 1L,
				planInputs = inputs(enabled),
			)
		}
		applyStarted.await()

		applying.cancelAndJoin()
		subject.stop(reason = "EXPLICIT_REQUEST", preserveLogicalSession = false)

		coVerify(exactly = 1) { lifecycle.stop(any()) }
	}

	@Test
	fun `transition sampling pressure-only automatic start never requires Steps control`() = runTest {
		val rollout = allEventShadow(revision = 5, automaticCapture = true)
		val enabled = settings(SourceCollectionFrequency.OFF, sourcePolicyRevision = 7).copy(
			barometerEnabled = true,
			transitionDetectionEnabled = false,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.OFF,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.OFF,
				pressure = SourceCollectionFrequency.BALANCED,
				wifi = SourceCollectionFrequency.OFF,
				cell = SourceCollectionFrequency.OFF,
			),
		)
		val captured = slot<SessionStartRequest>()
		coEvery { lifecycle.start(capture(captured)) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)

		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				automaticTrigger = automaticTrigger(SourceKind.PRESSURE),
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()

		captured.captured.plan.plans.filterValues { it.enabled }.keys shouldBe setOf(SourceKind.PRESSURE)
		captured.captured.controlDependencies shouldBe setOf(SourceKind.ACTIVITY)
	}

	@Test
	fun `production pressure-only demand stays enabled at its useful floor under thermal pressure`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val enabled = settings(SourceCollectionFrequency.OFF, sourcePolicyRevision = 7).copy(
			barometerEnabled = true,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.OFF,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.OFF,
				pressure = SourceCollectionFrequency.BALANCED,
				wifi = SourceCollectionFrequency.OFF,
				cell = SourceCollectionFrequency.OFF,
			),
		)
		val captured = slot<SessionStartRequest>()
		coEvery { lifecycle.start(capture(captured)) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)

		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled, severeThermalPressure = true),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()

		(captured.captured.plan.plans.getValue(SourceKind.PRESSURE) as PressurePlan).let { pressure ->
			pressure.enabled shouldBe true
			pressure.hardwareSamplePeriodMicros shouldBe 1_000_000
			pressure.maximumReportLatencyMicros shouldBe 60_000_000
			pressure.aggregationWindowMs shouldBe 60_000L
			pressure.movementGatedBurst shouldBe true
		}
	}

	@Test
	fun `newer policy input received before start supersedes the captured request`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val captured = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1)
		val revoked = settings(SourceCollectionFrequency.OFF, sourcePolicyRevision = 2)
		subject.reconfigure(inputs(revoked)) shouldBe SourceSessionReconfigureOutcome.NotActive
		subject.reconfigure(inputs(captured)) shouldBe SourceSessionReconfigureOutcome.NotActive

		val result = subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, captured),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(captured),
				ownerToken = "owner",
			),
		)

		result shouldBe SourceSessionStartOutcome.Rejected(
			SessionStartResult.InvalidIntent("ZERO_REACHABLE_CAPTURE_SOURCES"),
		)
	}

	@Test
	fun `latest environment wins when pending inputs share a policy revision`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val captured = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 2)
		val revoked = settings(SourceCollectionFrequency.OFF, sourcePolicyRevision = 2)
		subject.reconfigure(inputs(captured)) shouldBe SourceSessionReconfigureOutcome.NotActive
		subject.reconfigure(inputs(revoked)) shouldBe SourceSessionReconfigureOutcome.NotActive

		val result = subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, captured),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(captured),
				ownerToken = "owner",
			),
		)

		result shouldBe SourceSessionStartOutcome.Rejected(
			SessionStartResult.InvalidIntent("ZERO_REACHABLE_CAPTURE_SOURCES"),
		)
	}

	@Test
	fun `closed startup gate rejects source start before durable lifecycle or provider work`() = runTest {
		startupGate.result = TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.LEGACY_V27,
			"LEGACY_RECOVERY_PENDING",
		)
		val rollout = allEventShadow(revision = 5)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 2)

		val result = subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner",
			),
		)

		result shouldBe SourceSessionStartOutcome.Rejected(
			SessionStartResult.InvalidIntent("STARTUP_RECOVERY_NOT_READY"),
		)
		coVerify(exactly = 0) { lifecycle.start(any()) }
		database.sourceSessionDao().activeSession() shouldBe null
		database.sourcePlanStateDao().latestRevision() shouldBe null
	}

	@Test
	fun `closed startup gate rejects active reconfiguration before coordinator work`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val initial = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1)
		coEvery { lifecycle.start(any()) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, initial),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(initial),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()
		startupGate.result = TrackingStartupResult.Blocked(
			TrackingStartupStage.LEGACY_V27,
			"LEGACY_RECOVERY_BLOCKED",
		)

		val result = subject.reconfigure(
			inputs(settings(SourceCollectionFrequency.BATTERY_SAVER, sourcePolicyRevision = 2)),
		)

		result shouldBe SourceSessionReconfigureOutcome.Rejected(
			SessionReconfigureResult.InvalidState("STARTUP_RECOVERY_NOT_READY"),
		)
		coVerify(exactly = 0) { lifecycle.reconfigure(any()) }
	}

	@Test
	fun `closed startup gate rejects all reconfiguration while stop remains the cleanup authority`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val initial = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1)
		coEvery { lifecycle.start(any()) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
		coEvery { lifecycle.reconfigure(any()) } returns SessionReconfigureResult.Failed(
			revision = 1,
			applied = emptyList(),
			failureCode = "NO_SOURCE_ACTIVE_AFTER_RECONFIGURE",
		)
		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, initial),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(initial),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()
		startupGate.result = TrackingStartupResult.Blocked(
			TrackingStartupStage.LEGACY_V27,
			"LEGACY_RECOVERY_BLOCKED",
		)

		subject.reconfigure(
			inputs(settings(SourceCollectionFrequency.OFF, sourcePolicyRevision = 2)),
		).shouldBeInstanceOf<SourceSessionReconfigureOutcome.Rejected>()

		coVerify(exactly = 0) { lifecycle.reconfigure(any()) }
		startupGate.reconcileCalls shouldBe 2
	}

	@Test
	fun `stop and restart suspension remain available while startup gate is closed`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1)
		coEvery { lifecycle.start(any()) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
		coEvery { lifecycle.suspendForRestart(any()) } returns SessionSuspendResult.NoActiveSession
		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()
		startupGate.result = TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.LIVE_V2,
			"LIVE_DRAIN_PENDING",
		)

		subject.stop(reason = "PROCESS_RESTART", preserveLogicalSession = true)

		coVerify(exactly = 1) { lifecycle.suspendForRestart(any()) }
		startupGate.reconcileCalls shouldBe 1
	}

	@Test
	fun `live external stop forwards the same factual cutoff used by inactive finalization`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1)
		val capturedStop = slot<SessionStopRequest>()
		coEvery { lifecycle.start(any()) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
		coEvery { lifecycle.stop(capture(capturedStop)) } returns SessionStopResult.NoActiveSession
		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()

		subject.stop(
			reason = "EXPLICIT_REQUEST",
			preserveLogicalSession = false,
			factualCutoff = SourceSessionStopCutoff(
				wallTimeMs = 2_500L,
				elapsedRealtimeNanos = 2_500_000L,
				clockDomainId = "boot-1",
			),
		)

		capturedStop.captured.wallTimeMs shouldBe 2_500L
		capturedStop.captured.elapsedRealtimeNanos shouldBe 2_500_000L
		capturedStop.captured.clockDomainId shouldBe "boot-1"
	}

	@Test
	fun `internal stop samples current clocks`() = runTest {
		val rollout = allEventShadow(revision = 5)
		val enabled = settings(SourceCollectionFrequency.BALANCED, sourcePolicyRevision = 1)
		val capturedStop = slot<SessionStopRequest>()
		coEvery { lifecycle.start(any()) } returns
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
		coEvery { lifecycle.stop(capture(capturedStop)) } returns SessionStopResult.NoActiveSession
		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = TrackingSessionOwnership.resolve(rollout, enabled),
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(enabled),
				ownerToken = "owner",
			),
		).shouldBeInstanceOf<SourceSessionStartOutcome.Started>()
		val beforeWallTimeMs = Time.nowMillis
		val beforeElapsedRealtimeNanos = Time.elapsedRealtimeNanos

		subject.stop(reason = "INTERNAL_FAILURE", preserveLogicalSession = false)

		val afterWallTimeMs = Time.nowMillis
		val afterElapsedRealtimeNanos = Time.elapsedRealtimeNanos
		(capturedStop.captured.wallTimeMs in beforeWallTimeMs..afterWallTimeMs) shouldBe true
		(capturedStop.captured.elapsedRealtimeNanos in
			beforeElapsedRealtimeNanos..afterElapsedRealtimeNanos) shouldBe true
		capturedStop.captured.clockDomainId shouldBe "boot-1"
	}

	private fun inputs(
		settings: TrackingParamsState,
		severeThermalPressure: Boolean = false,
	) = SourceSessionPlanInputs(
		settings = settings,
		environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, emptySet()),
		resolutionContext = PlanResolutionContext(
			constraints = SourceKind.entries.associateWith { SourceConstraint() },
			powerSaver = false,
			doze = false,
			severeThermalPressure = severeThermalPressure,
		),
		demands = emptyList(),
		clockDomainId = "boot-1",
	)

	private fun allEventShadow(
		revision: Long,
		automaticCapture: Boolean = false,
	) = TrackingRolloutState.eventShadow(
		sources = SourceKind.entries.toSet(),
		revision = revision,
		captureModes = SourceKind.entries.associateWith {
			buildSet {
				add(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE)
				if (automaticCapture) add(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE)
			}
		},
	)

	private fun settings(
		steps: SourceCollectionFrequency,
		sourcePolicyRevision: Long? = null,
	) = TrackingParamsState(
		locationEnabled = false,
		activityEnabled = false,
		stepsEnabled = steps != SourceCollectionFrequency.OFF,
		wifiEnabled = false,
		cellEnabled = false,
		barometerEnabled = false,
		sourcePolicyRevision = sourcePolicyRevision,
		sourceCollectionSettings = SourceCollectionSettings(
			location = SourceCollectionFrequency.OFF,
			activity = SourceCollectionFrequency.OFF,
			steps = steps,
			pressure = SourceCollectionFrequency.OFF,
			wifi = SourceCollectionFrequency.OFF,
			cell = SourceCollectionFrequency.OFF,
		),
	)

	private fun automaticTrigger(source: SourceKind) = AutomaticTrackingStartTrigger(
		triggerId = "activity-transition:boot-1:43",
		kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
		bootId = "boot-1",
		observedElapsedRealtimeNanos = 100L,
		receivedElapsedRealtimeNanos = 120L,
		expiresElapsedRealtimeNanos = 60_000_000_120L,
		automationEpoch = 9L,
		startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
		sourcePolicyRevision = 7L,
		requestedCaptureSourceMask = 1L shl (source.stableCode - 1),
		intendedCaptureSourceMask = 1L shl (source.stableCode - 1),
		intendedForegroundServiceTypeMask = 1L,
		collectedDataEpoch = 3L,
	)

	private companion object {
		val TEST_STEPS_BINDING = ExecutableSourceLaneBinding(
			SourceKind.STEPS,
			1L,
			"test-steps-session-product",
			1,
			setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
		)
	}

}

private class FakeTrackingStartupGate(
	var result: TrackingStartupResult = TrackingStartupResult.Ready(false, 0L),
) : TrackingStartupGate {
	var reconcileCalls = 0

	override val isReady: Boolean
		get() = result is TrackingStartupResult.Ready

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
		reconcileCalls += 1
		return result
	}
}
