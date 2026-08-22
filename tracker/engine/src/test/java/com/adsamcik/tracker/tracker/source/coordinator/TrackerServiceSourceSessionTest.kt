package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
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
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
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
				origin = SessionStartOrigin.MANUAL_FOREGROUND_START,
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
	fun `newer policy input received before start supersedes the captured request`() = runTest {
		val rollout = TrackingRolloutState.eventCanonical(revision = 5)
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

		result shouldBe SourceSessionStartOutcome.NotRequired
	}

	@Test
	fun `latest environment wins when pending inputs share a policy revision`() = runTest {
		val rollout = TrackingRolloutState.eventCanonical(revision = 5)
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

		result shouldBe SourceSessionStartOutcome.NotRequired
	}

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

}
