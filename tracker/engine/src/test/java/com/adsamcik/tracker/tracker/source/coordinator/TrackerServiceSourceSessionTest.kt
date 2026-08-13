package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
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

	@Before
	fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(application)
		lifecycle = mockk()
		subject = TrackerServiceSourceSession(
			database,
			lifecycle,
			SemanticAcquisitionPlanFactory(),
			SourcePlanResolver(),
			TrackingCoordinatorTelemetry(),
			DefaultTrackingSettingsStatusProvider(
				SemanticAcquisitionPlanFactory(),
				SourcePlanResolver(),
				QualitativeBatteryImpactEstimator(),
				TrackingCoordinatorTelemetry(),
			),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `event source enabled after service start activates coordinator without a global trigger`() = runTest {
		val rollout = TrackingRolloutState.eventCanonical(revision = 5)
		val initialSettings = settings(steps = SourceCollectionFrequency.OFF)
		val initialOwnership = TrackingSessionOwnership.resolve(rollout, initialSettings)
		subject.start(
			SourceSessionStartRequest(
				rollout = rollout,
				ownership = initialOwnership,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				origin = SessionStartOrigin.MANUAL_FOREGROUND,
				foregroundCapabilityFlags = 1,
				planInputs = inputs(initialSettings),
				ownerToken = "owner",
			),
		) shouldBe SourceSessionStartOutcome.NotRequired

		val capturedStart = slot<SessionStartRequest>()
		coEvery { lifecycle.start(capture(capturedStart)) } answers {
			val request = capturedStart.captured
			request.plan.plans.filterValues { it.enabled }.keys shouldBe setOf(SourceKind.STEPS)
			SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
		}

		subject.reconfigure(
			inputs(settings(steps = SourceCollectionFrequency.BALANCED)),
		).shouldBeInstanceOf<SourceSessionReconfigureOutcome.Started>()
	}

	@Test
	fun `startup exception clears the in-memory active session`() = runTest {
		val rollout = TrackingRolloutState.eventCanonical(revision = 5)
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } throws IllegalStateException("coordinator failed")

		shouldThrow<IllegalStateException> {
			subject.start(
				SourceSessionStartRequest(
					rollout = rollout,
					ownership = TrackingSessionOwnership.resolve(rollout, enabledSettings),
					logicalTrackingId = "logical",
					serviceRunId = "run",
					origin = SessionStartOrigin.MANUAL_FOREGROUND,
					foregroundCapabilityFlags = 1,
					planInputs = inputs(enabledSettings),
					ownerToken = "owner",
				),
			)
		}

		subject.reconfigure(inputs(enabledSettings)) shouldBe SourceSessionReconfigureOutcome.NotActive
	}

	@Test
	fun `start cancellation propagates and clears the active session`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } throws CancellationException("cancel start")

		shouldThrow<CancellationException> { subject.start(startRequest(enabledSettings)) }
		subject.reconfigure(inputs(enabledSettings)) shouldBe SourceSessionReconfigureOutcome.NotActive
	}

	@Test
	fun `controlled SQLite start failure becomes a typed rejection`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } throws SQLiteException("database unavailable")

		val rejected = subject.start(startRequest(enabledSettings))
			.shouldBeInstanceOf<SourceSessionStartOutcome.Rejected>()

		(rejected.result as SessionStartResult.Failed).code shouldBe "STORAGE_UNAVAILABLE"
	}

	@Test
	fun `reconfigure cancellation propagates`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } returns startedResult()
		subject.start(startRequest(enabledSettings))
		coEvery { lifecycle.reconfigure(any()) } throws CancellationException("cancel reconfigure")

		shouldThrow<CancellationException> {
			subject.reconfigure(inputs(enabledSettings.copy(minTimeSeconds = 99)))
		}
	}

	@Test
	fun `controlled SQLite reconfigure failure becomes a typed rejection`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } returns startedResult()
		subject.start(startRequest(enabledSettings))
		coEvery { lifecycle.reconfigure(any()) } throws SQLiteException("database unavailable")

		val rejected = subject.reconfigure(inputs(enabledSettings.copy(minTimeSeconds = 99)))
			.shouldBeInstanceOf<SourceSessionReconfigureOutcome.Rejected>()

		(rejected.result as SessionReconfigureResult.InvalidState).state shouldBe "STORAGE_UNAVAILABLE"
	}

	@Test
	fun `stop cancellation propagates`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } returns startedResult()
		subject.start(startRequest(enabledSettings))
		coEvery { lifecycle.stop(any()) } throws CancellationException("cancel stop")

		shouldThrow<CancellationException> {
			subject.stop("test", preserveLogicalSession = false)
		}
	}

	@Test
	fun `controlled SQLite stop failure becomes a typed retry`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } returns startedResult()
		subject.start(startRequest(enabledSettings))
		coEvery { lifecycle.stop(any()) } throws SQLiteException("database unavailable")

		subject.stop("test", preserveLogicalSession = false) shouldBe
			SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.STORAGE_UNAVAILABLE)
	}

	@Test
	fun `programmer errors propagate from reconfigure and stop`() = runTest {
		val enabledSettings = settings(steps = SourceCollectionFrequency.BALANCED)
		coEvery { lifecycle.start(any()) } returns startedResult()
		subject.start(startRequest(enabledSettings))
		coEvery { lifecycle.reconfigure(any()) } throws IllegalArgumentException("bad plan")

		shouldThrow<IllegalArgumentException> {
			subject.reconfigure(inputs(enabledSettings.copy(minTimeSeconds = 99)))
		}

		coEvery { lifecycle.stop(any()) } throws IllegalStateException("broken invariant")
		shouldThrow<IllegalStateException> {
			subject.stop("test", preserveLogicalSession = false)
		}
	}

	private fun startRequest(settings: TrackingParamsState): SourceSessionStartRequest {
		val rollout = TrackingRolloutState.eventCanonical(revision = 5)
		return SourceSessionStartRequest(
			rollout = rollout,
			ownership = TrackingSessionOwnership.resolve(rollout, settings),
			logicalTrackingId = "logical",
			serviceRunId = "run",
			origin = SessionStartOrigin.MANUAL_FOREGROUND,
			foregroundCapabilityFlags = 1,
			planInputs = inputs(settings),
			ownerToken = "owner",
		)
	}

	private fun startedResult() = SessionStartResult.Started(
		logicalTrackingId = "logical",
		serviceRunId = "run",
		applied = emptyList(),
		planStatus = DesiredPlanStatus.EFFECTIVE,
	)

	private fun inputs(settings: TrackingParamsState) = SourceSessionPlanInputs(
		settings = settings,
		environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, emptySet()),
		resolutionContext = PlanResolutionContext(
			constraints = SourceKind.entries.associateWith { SourceConstraint() },
			powerSaver = false,
			doze = false,
			severeThermalPressure = false,
		),
		demands = emptyList(),
		clockDomainId = "boot-1",
	)

	private fun settings(steps: SourceCollectionFrequency) = TrackingParamsState(
		locationEnabled = false,
		activityEnabled = false,
		stepsEnabled = steps != SourceCollectionFrequency.OFF,
		wifiEnabled = false,
		cellEnabled = false,
		barometerEnabled = false,
		sourceCollectionSettings = SourceCollectionSettings(
			location = SourceCollectionFrequency.OFF,
			activity = SourceCollectionFrequency.OFF,
			steps = steps,
			pressure = SourceCollectionFrequency.OFF,
			wifi = SourceCollectionFrequency.OFF,
			cell = SourceCollectionFrequency.OFF,
		),
	)

}
