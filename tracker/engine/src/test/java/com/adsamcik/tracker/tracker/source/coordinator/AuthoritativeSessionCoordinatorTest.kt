package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
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
class AuthoritativeSessionCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var runtime: FakeStepsRuntime
	private lateinit var subject: AuthoritativeSessionCoordinator

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		runtime = FakeStepsRuntime(database)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.committedBatch(any(), any()) } returns emptyList()
		val dispatcher = ProjectionDispatcher(database, emptySet())
		subject = AuthoritativeSessionCoordinator(
			database,
			RoomSourcePlanStore(database, SourcePlanCodec()),
			SourceRuntimeRegistry(setOf(runtime)),
			DurableSourceEventSinkFactory(ingress),
			TrackingCoordinator(database, ingress, dispatcher),
			rolloutStore = fixedEventRolloutStore(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `session is durable before source start and closes after source drain`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()

		runtime.stateObservedAtStart shouldBe SessionLifecycleState.STARTING.name
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.RUNNING.name
		database.sourcePlanStateDao().latestRevision()?.status shouldBe DesiredPlanStatus.EFFECTIVE.name

		val stopped = subject.stop(
			SessionStopRequest("test-owner", "manual", 2_000, 2_000_000, perSourceTimeoutMs = 100),
		).shouldBeInstanceOf<SessionStopResult.Stopped>()

		stopped.finalAdmissionOrdinal shouldBe 0L
		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.CLOSED.name
		database.sourceSessionDao().completeness(started.logicalTrackingId).single().appDrainComplete shouldBe true
		runtime.closed shouldBe true
	}

	@Test
	fun `service run suspension preserves logical session and recovery resumes exact identity`() = runTest {
		val first = subject.start(
			startRequest().copy(logicalTrackingId = "logical-1", serviceRunId = "run-1"),
		).shouldBeInstanceOf<SessionStartResult.Started>()
		first.logicalTrackingId shouldBe "logical-1"
		first.serviceRunId shouldBe "run-1"

		subject.suspendForRestart(
			SessionSuspendRequest("test-owner", "ANDROID_RESTART", 2_000, 2_000_000, perSourceTimeoutMs = 100),
		).shouldBeInstanceOf<SessionSuspendResult.Suspended>()

		database.sourceSessionDao().session("logical-1")?.state shouldBe SessionLifecycleState.RUNNING.name
		database.sourceSessionDao().serviceRun("run-1")?.state shouldBe SessionLifecycleState.CLOSED.name

		val restored = subject.start(
			startRequest().copy(
				origin = SessionStartOrigin.RESTORE_AFTER_PROCESS_DEATH,
				plan = AcquisitionPlanRevision(
					revision = 2,
					planId = "balanced-restore",
					createdAtMs = 3_000,
					plans = mapOf(SourceKind.STEPS to StepsPlan(2, true, 2_000, 2_000, true)),
				),
				wallTimeMs = 3_000,
				elapsedRealtimeNanos = 3_000_000,
				logicalTrackingId = "logical-1",
				serviceRunId = "run-2",
			),
		).shouldBeInstanceOf<SessionStartResult.Started>()

		restored.logicalTrackingId shouldBe "logical-1"
		restored.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().latestServiceRun("logical-1")?.serviceRunId shouldBe "run-2"
		database.sourceSessionDao().session("logical-1")?.desiredPlanRevision shouldBe 2L
	}

	@Test
	fun `source start failure closes partial runtimes and marks durable lifecycle failed`() = runTest {
		runtime.throwOnStart = true

		val failed = subject.start(
			startRequest().copy(logicalTrackingId = "logical-failed", serviceRunId = "run-failed"),
		).shouldBeInstanceOf<SessionStartResult.Failed>()

		failed.code shouldBe "NO_SOURCE_STARTED"
		database.sourceSessionDao().session("logical-failed")?.state shouldBe SessionLifecycleState.FAILED.name
		database.sourceSessionDao().serviceRun("run-failed")?.state shouldBe SessionLifecycleState.FAILED.name
		runtime.closed shouldBe true
	}

	@Test
	fun `failed reconfiguration restores the previous plan atomically`() = runTest {
		subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.failRevisions += 2L

		val result = subject.reconfigure(reconfigureRequest(2L))
			.shouldBeInstanceOf<SessionReconfigureResult.RolledBack>()

		result.restoredRevision shouldBe 1L
		runtime.currentRevision shouldBe 1L
		runtime.reconfiguredRevisions shouldBe listOf(2L, 1L)
		database.sourceSessionDao().activeSession()?.state shouldBe SessionLifecycleState.RUNNING.name
		database.sourceSessionDao().activeSession()?.desiredPlanRevision shouldBe 1L
		database.sourcePlanStateDao().revision(2L)?.status shouldBe DesiredPlanStatus.FAILED.name
	}

	@Test
	fun `rollback failure stops sources and marks the session failed`() = runTest {
		val started = subject.start(startRequest()).shouldBeInstanceOf<SessionStartResult.Started>()
		runtime.failRevisions += setOf(1L, 2L)

		subject.reconfigure(reconfigureRequest(2L))
			.shouldBeInstanceOf<SessionReconfigureResult.Failed>()

		database.sourceSessionDao().session(started.logicalTrackingId)?.state shouldBe SessionLifecycleState.FAILED.name
		runtime.closed shouldBe true
	}

	private fun startRequest() = SessionStartRequest(
		ownerToken = "test-owner",
		origin = SessionStartOrigin.MANUAL_FOREGROUND,
		plan = AcquisitionPlanRevision(
			revision = 1,
			planId = "balanced",
			createdAtMs = 1_000,
			plans = mapOf(SourceKind.STEPS to StepsPlan(1, true, 5_000, 5_000, false)),
		),
		rolloutRevision = 1,
		clockDomainId = "boot-1",
		foregroundCapabilityFlags = 0,
		wallTimeMs = 1_000,
		elapsedRealtimeNanos = 1_000_000,
	)

	private fun reconfigureRequest(revision: Long) = SessionReconfigureRequest(
		ownerToken = "test-owner",
		plan = AcquisitionPlanRevision(
			revision = revision,
			planId = "plan-$revision",
			createdAtMs = revision * 1_000L,
			plans = mapOf(SourceKind.STEPS to StepsPlan(revision, true, 2_000, 2_000, true)),
		),
		wallTimeMs = revision * 1_000L,
		elapsedRealtimeNanos = revision * 1_000_000L,
	)

	private fun fixedEventRolloutStore() = object : TrackingRolloutStateStore {
		override suspend fun load() = TrackingRolloutState(
			revision = 1,
			schemaVersion = 1,
			coordinatorMode = CoordinatorMode.EVENT,
			projectionMode = ProjectionMode.SHADOW_READ_ONLY,
			sourceOwners = SourceKind.entries.associateWith { source ->
				if (source == SourceKind.STEPS) SourceOwner.EVENT else SourceOwner.LEGACY
			},
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)

		override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
	}
}

private class FakeStepsRuntime(private val database: AppDatabase) : SourceRuntime<StepsPlan> {
	override val source = SourceKind.STEPS
	override val capabilities = MutableStateFlow(SourceCapabilities(true, true, true, 100, 1))
	var stateObservedAtStart: String? = null
	var closed = false
	var throwOnStart = false
	val failRevisions = mutableSetOf<Long>()
	val reconfiguredRevisions = mutableListOf<Long>()
	var currentRevision: Long? = null

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult {
		stateObservedAtStart = database.sourceSessionDao().activeSession()?.state
		if (throwOnStart) error("start failed")
		currentRevision = plan.revision
		return SourceStartResult.Started(applied(plan))
	}

	override suspend fun reconfigure(plan: StepsPlan): SourceApplyResult {
		reconfiguredRevisions += plan.revision
		if (plan.revision in failRevisions) {
			currentRevision = null
			return SourceApplyResult.Failed(
				applied(plan).copy(appliedRevision = null, status = SourceApplyStatus.FAILED),
				retryable = false,
			)
		}
		currentRevision = plan.revision
		return SourceApplyResult.Applied(applied(plan))
	}

	override suspend fun quiesce(cutoff: SessionCutoff) = SourceStopAck(
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1,
		appliedRevision = 1,
		callbackEntryBarrierSequence = 4,
		lastDurablyAdmittedSequence = 4,
		lastAdmissionOrdinal = null,
		failedAdmissionCount = 0,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED,
		providerFlushOutcome = ProviderFlushOutcome.COMPLETE,
		providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER,
		appDrainComplete = true,
		status = SourceStopStatus.COMPLETE,
	)

	override suspend fun close() {
		closed = true
		currentRevision = null
	}

	private fun applied(plan: SourcePlan) = AppliedSourcePlan(
		desiredRevision = plan.revision,
		appliedRevision = plan.revision,
		source = source,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1,
		appliedAtElapsedRealtimeNanos = 1_000_000,
		status = SourceApplyStatus.APPLIED,
	)
}
