package com.adsamcik.tracker.tracker.component.consumer

import android.content.Context
import android.location.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.data.collection.PressureReading
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.presentation.OpenSessionPresentation
import com.adsamcik.tracker.tracker.presentation.SessionPresentationBinding
import com.adsamcik.tracker.tracker.presentation.SessionPresentationLifecycle
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for source-neutral presentation persistence and session aggregation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionTrackerComponentTest {

	private lateinit var mockSegmentDao: SessionSegmentDao
	private lateinit var context: Context
	private fun trackingParamsRepository(): TrackingParamsRepository = mockk {
		every { data } returns MutableStateFlow(TrackingParamsState())
	}

	@Before
	fun setup() {
		mockSegmentDao = mockk(relaxed = true)
		context = mockk(relaxed = true)
	}

	private fun createComponent(isUserInitiated: Boolean = true): SessionTrackerComponent {
		return SessionTrackerComponent(
			isUserInitiated = isUserInitiated,
			sessionSegmentDao = mockSegmentDao,
		)
	}

	/**
	 * Inject a session into the component's private [mutableSession] field,
	 * simulating the state after [onEnable] / [initializeSession] ran.
	 */
	private fun setSession(component: SessionTrackerComponent, session: MutableTrackerSession) {
		val field = SessionTrackerComponent::class.java.getDeclaredField("mutableSession")
		field.isAccessible = true
		field.set(component, session)
	}

	private fun setIsNewSession(component: SessionTrackerComponent, value: Boolean) {
		val field = SessionTrackerComponent::class.java.getDeclaredField("isNewSession")
		field.isAccessible = true
		field.set(component, value)
	}

	private fun intField(component: SessionTrackerComponent, name: String): Int {
		val field = SessionTrackerComponent::class.java.getDeclaredField(name)
		field.isAccessible = true
		return field.getInt(component)
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private fun emptySession(id: Long = 42L, start: Long = 1000L): MutableTrackerSession =
		MutableTrackerSession(
			id = id,
			start = start,
			end = start,
			isUserInitiated = true,
			collections = 0,
			distanceInM = 0f,
			distanceOnFootInM = 0f,
			distanceInVehicleInM = 0f,
			steps = 0,
		)

	private fun nonEmptySession(
		id: Long = 42L,
		start: Long = 1000L,
		end: Long = 30_000L,
		collections: Int = 5,
		distanceInM: Float = 150f,
	): MutableTrackerSession = MutableTrackerSession(
		id = id,
		start = start,
		end = end,
		isUserInitiated = true,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = 100f,
		distanceInVehicleInM = 0f,
		steps = 200,
	)

	// ── tests ───────────────────────────────────────────────────────────

	@Test
	fun readsTrackingParamsRepositoryOnEnable() = runTest {
		val params = MutableStateFlow(
			TrackingParamsState(
				minDistanceMeters = 33,
				minTimeSeconds = 7,
			)
		)
		val repository: TrackingParamsRepository = mockk {
			every { data } returns params
		}
		val component = SessionTrackerComponent(
			isUserInitiated = true,
			sessionSegmentDao = mockSegmentDao,
			trackingParamsRepository = repository,
		)

		component.onEnable(context)

		intField(component, "minDistanceInMeters") shouldBe 33
		intField(component, "minUpdateDelayInSeconds") shouldBe 7
		component.onDisable(context)
	}

	@Test
	fun resumesExactRecentSegmentWithMetrics() = runTest {
		val now = System.currentTimeMillis()
		val existing = SessionSegment(
			id = 42L,
			startTimeMs = now - 60_000L,
			endTimeMs = now - 1_000L,
			distanceM = 321f,
			steps = 456,
			primaryActivity = DetectedActivity.WALKING.value,
			activityConfidence = 87,
			sampleCount = 12,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = "tracker_v2",
			createdAt = now - 61_000L,
		)
		coEvery { mockSegmentDao.getById(42L) } returns existing
		val component = SessionTrackerComponent(
			isUserInitiated = true,
			sessionSegmentDao = mockSegmentDao,
			trackingParamsRepository = trackingParamsRepository(),
			resumeSessionSegmentId = 42L,
		)

		component.onEnable(context)

		component.isNewSession shouldBe false
		component.session.id shouldBe 42L
		component.session.distanceInM shouldBe 321f
		component.session.steps shouldBe 456
		component.session.collections shouldBe 12
		coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
		component.onDisable(context)
	}

	@Test
	fun staleExactSegmentFallsBackToNewSession() = runTest {
		val now = System.currentTimeMillis()
		coEvery { mockSegmentDao.getById(42L) } returns SessionSegment(
			id = 42L,
			startTimeMs = now - SessionTrackerComponent.SESSION_RESUME_TIMEOUT - 2_000L,
			endTimeMs = now - SessionTrackerComponent.SESSION_RESUME_TIMEOUT - 1_000L,
			distanceM = 100f,
			steps = 10,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 3,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = "tracker_v2",
			createdAt = now - SessionTrackerComponent.SESSION_RESUME_TIMEOUT - 2_000L,
		)
		coEvery { mockSegmentDao.insert(any<SessionSegment>()) } returns 99L
		val component = SessionTrackerComponent(
			isUserInitiated = true,
			sessionSegmentDao = mockSegmentDao,
			trackingParamsRepository = trackingParamsRepository(),
			resumeSessionSegmentId = 42L,
		)

		component.onEnable(context)

		component.isNewSession shouldBe true
		component.session.id shouldBe 99L
		coVerify(exactly = 0) { mockSegmentDao.update(match<SessionSegment> { it.id == 42L }) }
		component.onDisable(context)
	}

	@Test
	fun persistsDurableLogicalAndServiceRunBridgeOnInsert() = runTest {
		val proposed = slot<SessionSegment>()
		val lifecycle = mockk<SessionPresentationLifecycle>()
		coEvery {
			lifecycle.openOrResumeExact("logical-7", "run-11", null, capture(proposed))
		} answers {
			OpenSessionPresentation(
				binding = SessionPresentationBinding("logical-7", "run-11", 71L),
				segment = proposed.captured.copy(id = 71L),
				created = true,
			)
		}
		val repository: TrackingParamsRepository = mockk {
			every { data } returns MutableStateFlow(TrackingParamsState())
		}
		val component = SessionTrackerComponent(
			isUserInitiated = true,
			sessionSegmentDao = mockSegmentDao,
			trackingParamsRepository = repository,
			logicalTrackingId = "logical-7",
			serviceRunId = "run-11",
			presentationLifecycle = lifecycle,
		)

		component.onEnable(context)

		proposed.captured.logicalTrackingId shouldBe "logical-7"
		proposed.captured.serviceRunId shouldBe "run-11"
		component.session.id shouldBe 71L
		component.presentationBinding shouldBe SessionPresentationBinding("logical-7", "run-11", 71L)
		component.onDisable(context)
	}

	@Test
	fun rejectsHalfOfDurableSessionBridge() {
		shouldThrow<IllegalArgumentException> {
			SessionTrackerComponent(
				isUserInitiated = true,
				sessionSegmentDao = mockSegmentDao,
				logicalTrackingId = "logical-only",
			)
		}
	}


	@Test
	fun retainsEmptyPresentationUntilSourceEvidenceIsEvaluated() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun finalizingAnExistingEmptyPresentationDoesNotInsertAnotherRow() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
		coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun retainsEmptySessionWithPositiveDurationForRadioEvidenceEvaluation() = runTest {
		// Session open for a while but GPS never locked → no data
		val component = createComponent()
		setSession(component, emptySession(start = 1000L).apply { end = 60_000L })
		setIsNewSession(component, true)

		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
		coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
	}

	@Test
	fun rejectedOrAbsentGpsDoesNotDeletePossibleRadioOnlySession() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDataUpdated(
			cycle = TrackingCycle(timestampMs = 1_000L, elapsedRealtimeNanos = 1_000L),
			collectionData = MutableCollectionData(1_000L),
		)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
		coVerify(exactly = 2) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun coveredZeroStepIntervalPreservesStepsOnlySession() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDataUpdated(
			cycle = TrackingCycle(
				timestampMs = 1_000L,
				elapsedRealtimeNanos = 1_000L,
				stepDelta = 0,
			),
			collectionData = MutableCollectionData(1_000L),
		)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 2) {
			mockSegmentDao.update(match<SessionSegment> { it.steps == 0 })
		}
	}


	@Test
	fun persistsNonEmptySession() = runTest {
		val component = createComponent()
		setSession(component, nonEmptySession())
		setIsNewSession(component, true)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		// session.id = 42 > 0 → update (segment was pre-inserted in initializeSession)
		coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun preservesSingleCollectionSession() = runTest {
		// First GPS fix has no distance (distance is delta between points)
		val component = createComponent()
		coEvery { mockSegmentDao.insert(any<SessionSegment>()) } returns 1L
		setSession(component, nonEmptySession(collections = 1, distanceInM = 0f))
		setIsNewSession(component, true)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
	}

	@Test
	fun preservesResumedSessionWithoutNewGpsPoint() = runTest {
		val component = createComponent(isUserInitiated = false)
		setSession(component, nonEmptySession(collections = 5, distanceInM = 150f))
		setIsNewSession(component, false)

		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		// session.id = 42 > 0 → update (segment was pre-inserted in initializeSession)
		coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun preservesStepsOnlySessionWithoutLocation() = runTest {
		// Separation of sources: a steps-only (or otherwise non-location) session collects no
		// GPS fixes (collectedLocationCount stays 0) yet records steps. It must be preserved,
		// not deleted by the rapid-start/stop empty-session guard.
		val component = createComponent()
		setSession(component, nonEmptySession(collections = 3, distanceInM = 0f))
		setIsNewSession(component, true)

		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun preservesActivityOnlySessionWithoutLocationOrSteps() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDataUpdated(
			cycle = TrackingCycle(
				timestampMs = 1_000L,
				elapsedRealtimeNanos = 1_000L,
				activity = ActivityInfo(DetectedActivity.STILL, confidence = 88),
				activityFresh = true,
			),
			collectionData = MutableCollectionData(1_000L),
		)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 2) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun preservesPressureOnlySessionWithoutLocationActivityOrSteps() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDataUpdated(
			cycle = TrackingCycle(
				timestampMs = 1_000L,
				elapsedRealtimeNanos = 1_000L,
				pressure = PressureReading(pressureHpa = 1_000f, altitudeM = 110f),
			),
			collectionData = MutableCollectionData(1_000L),
		)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 2) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun rejectedLocationStillDefersRetentionDecisionToAllSourceEvidence() = runTest {
		val component = createComponent()
		setSession(component, emptySession())
		setIsNewSession(component, true)

		component.onDataUpdated(
			cycle = cycleWithDistance(
				lat = 50.08,
				lon = 14.42,
				prevLat = 0.0,
				prevLon = 0.0,
			),
			// LocationTrackerComponent rejected the raw fix, so it never reached persistence.
			collectionData = MutableCollectionData(1_000L),
		)
		component.onDisable(context)

		coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		coVerify(exactly = 2) { mockSegmentDao.update(any<SessionSegment>()) }
	}

	@Test
	fun freshCyclingEvidenceIsPersistedSeparatelyFromMotorizedTravel() = runTest {
		val component = createComponent()
		setSession(component, nonEmptySession(id = 42L))
		val persisted = mutableListOf<SessionSegment>()
		coEvery { mockSegmentDao.update(capture(persisted)) } returns Unit

		component.onDataUpdated(
			cycle = TrackingCycle(
				timestampMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000_000L,
				activity = ActivityInfo(DetectedActivity.ON_BICYCLE, confidence = 92),
				activityFresh = true,
			),
			collectionData = MutableCollectionData(2_000L),
		)

		val segment = persisted.last()
		segment.primaryActivity shouldBe DetectedActivity.ON_BICYCLE.value
		segment.activityConfidence shouldBe 92
	}


	/**
	 * Helper to create a [TrackingCycle] with precomputed distance in [LocationData],
	 * simulating what LocationCollectionTrigger produces.
	 */
	private fun cycleWithDistance(
		lat: Double,
		lon: Double,
		prevLat: Double,
		prevLon: Double,
		timeMs: Long = 1000L,
		elapsedNanos: Long = 1_000_000_000_000L,
	): TrackingCycle {
		val loc = Location("test").apply {
			latitude = lat; longitude = lon
			time = timeMs; elapsedRealtimeNanos = elapsedNanos
		}
		val prev = Location("test").apply {
			latitude = prevLat; longitude = prevLon
			time = timeMs - 1000L
			elapsedRealtimeNanos = elapsedNanos - 1_000_000_000L
		}
		val distance = loc.distanceTo(prev)
		val locationData = LocationData(
			locations = listOf(loc),
			previousLocation = prev,
			distance = distance,
		)
		return TrackingCycle(
			timestampMs = timeMs,
			elapsedRealtimeNanos = elapsedNanos,
			location = locationData,
		)
	}

	@Test
	fun teleportDistanceNotAccumulated() = runTest {
		val component = createComponent()
		val session = emptySession(id = 1L)
		setSession(component, session)

		// Cycle with huge teleport distance (~5,500 km from (0,0) to Prague)
		// collectionData.location is null => LocationTrackerComponent rejected it
		val cycle = cycleWithDistance(
			lat = 50.08, lon = 14.42,
			prevLat = 0.0, prevLon = 0.0,
		)
		val collectionData = MutableCollectionData(1000L)
		// Do NOT set collectionData.location — simulates teleport rejection

		component.onDataUpdated(cycle, collectionData)

		session.distanceInM shouldBe 0f
	}

	@Test
	fun normalDistanceAccumulated() = runTest {
		val component = createComponent()
		val session = emptySession(id = 1L)
		setSession(component, session)

		// Small movement: ~100m
		val cycle = cycleWithDistance(
			lat = 50.0884, lon = 14.4213,
			prevLat = 50.0875, prevLon = 14.4213,
		)
		val collectionData = MutableCollectionData(1000L)
		// Simulate LocationTrackerComponent accepting the point: it sets both the
		// location and the bridged distance-from-previous-accepted value.
		collectionData.setLocation(cycle.location!!.lastLocation)
		collectionData.distanceFromPreviousM = cycle.location!!.distance

		component.onDataUpdated(cycle, collectionData)

		session.distanceInM shouldBeGreaterThan 0f
	}

	@Test
	fun usesBridgedDistanceField() = runTest {
		val component = createComponent()
		val session = emptySession(id = 1L)
		setSession(component, session)

		val cycle = cycleWithDistance(
			lat = 50.0884, lon = 14.4213,
			prevLat = 50.0875, prevLon = 14.4213,
		)
		val collectionData = MutableCollectionData(1000L)
		collectionData.setLocation(cycle.location!!.lastLocation)
		// Bridged distance differs from the raw cycle distance (it spans cycles dropped
		// upstream). The session must use this value, not cycle.location.distance.
		collectionData.distanceFromPreviousM = 250f

		component.onDataUpdated(cycle, collectionData)

		session.distanceInM shouldBe 250f
	}

	@Test
	fun mixedSequenceOnlyNormalDistance() = runTest {
		val component = createComponent()
		val session = emptySession(id = 1L)
		setSession(component, session)

		// First: teleport (rejected — collectionData.location is null)
		val teleportCycle = cycleWithDistance(
			lat = 50.08, lon = 14.42,
			prevLat = 0.0, prevLon = 0.0,
		)
		component.onDataUpdated(
			teleportCycle,
			MutableCollectionData(1000L), // location NOT set — simulates rejection
		)
		session.distanceInM shouldBe 0f

		// Second: normal movement (accepted — collectionData.location set)
		val normalCycle = cycleWithDistance(
			lat = 50.0884, lon = 14.4213,
			prevLat = 50.0875, prevLon = 14.4213,
			timeMs = 6000L,
			elapsedNanos = 1_000_000_000_000L + 5_000_000_000L,
		)
		val normalData = MutableCollectionData(6000L)
		normalData.setLocation(normalCycle.location!!.lastLocation)
		normalData.distanceFromPreviousM = normalCycle.location!!.distance
		component.onDataUpdated(normalCycle, normalData)

		// Distance should be ~100m, NOT ~5,500,000m + 100m
		session.distanceInM shouldBeGreaterThan 0f
		assert(session.distanceInM < 1000f) {
			"Expected < 1km but got ${session.distanceInM}m — teleport distance leaked"
		}
	}
}
