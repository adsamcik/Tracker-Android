package com.adsamcik.tracker.tracker.component.consumer

import android.content.Context
import android.location.Location
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Regression tests for [SessionTrackerComponent] empty-session guard.
 *
 * Verifies that rapid start/stop cycles do NOT persist empty sessions
 * (0 collections, 0 distance) to the Room database.
 *
 * See bug: "Empty Sessions Saved During Rapid Start/Stop".
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("SessionTrackerComponent")
class SessionTrackerComponentTest {

	private lateinit var mockSegmentDao: SessionSegmentDao
	private lateinit var context: Context

	@BeforeEach
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

	private fun setCollectedLocationCount(component: SessionTrackerComponent, value: Int) {
		val field = SessionTrackerComponent::class.java.getDeclaredField("collectedLocationCount")
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
	@DisplayName("reads min distance and time from tracking repository on enable")
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

	@Nested
	@DisplayName("empty session guard on disable")
	inner class EmptySessionGuard {

		@Test
		@DisplayName("deletes session when zero collections collected (rapid start/stop)")
		fun deletesEmptySession() = runTest {
			val component = createComponent()
			setSession(component, emptySession())
			setIsNewSession(component, true)

			component.onDisable(context)

			coVerify(exactly = 1) { mockSegmentDao.deleteById(any()) }
			coVerify(exactly = 0) { mockSegmentDao.update(any<SessionSegment>()) }
		}

		@Test
		@DisplayName("does not create session segment for empty session")
		fun noSegmentForEmptySession() = runTest {
			val component = createComponent()
			setSession(component, emptySession())
			setIsNewSession(component, true)

			component.onDisable(context)

			coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
			coVerify(exactly = 0) { mockSegmentDao.update(any<SessionSegment>()) }
		}

		@Test
		@DisplayName("deletes session with zero collections even when duration is positive")
		fun deletesEmptySessionWithPositiveDuration() = runTest {
			// Session open for a while but GPS never locked → no data
			val component = createComponent()
			setSession(component, emptySession(start = 1000L).apply { end = 60_000L })
			setIsNewSession(component, true)

			component.onDisable(context)

			coVerify(exactly = 1) { mockSegmentDao.deleteById(any()) }
			coVerify(exactly = 0) { mockSegmentDao.update(any<SessionSegment>()) }
			coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
		}

		@Test
		@DisplayName("deletes new session when only non-location updates were collected")
		fun deletesNewSessionWithoutGpsLocation() = runTest {
			val component = createComponent()
			setSession(component, emptySession())
			setIsNewSession(component, true)

			component.onDataUpdated(
				cycle = TrackingCycle(timestampMs = 1_000L, elapsedRealtimeNanos = 1_000L),
				collectionData = MutableCollectionData(1_000L),
			)
			component.onDisable(context)

			coVerify(exactly = 1) { mockSegmentDao.deleteById(any()) }
			coVerify(exactly = 0) { mockSegmentDao.insert(any<SessionSegment>()) }
		}
	}

	@Nested
	@DisplayName("non-empty session persistence")
	inner class NonEmptySessionPersistence {

		@Test
		@DisplayName("updates and creates segment for session with collected data")
		fun persistsNonEmptySession() = runTest {
			val component = createComponent()
			setSession(component, nonEmptySession())
			setIsNewSession(component, true)
			setCollectedLocationCount(component, 5)

			component.onDisable(context)

			coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
			// session.id = 42 > 0 → update (segment was pre-inserted in initializeSession)
			coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
		}

		@Test
		@DisplayName("preserves session with single collection point and zero distance")
		fun preservesSingleCollectionSession() = runTest {
			// First GPS fix has no distance (distance is delta between points)
			val component = createComponent()
			coEvery { mockSegmentDao.insert(any<SessionSegment>()) } returns 1L
			setSession(component, nonEmptySession(collections = 1, distanceInM = 0f))
			setIsNewSession(component, true)
			setCollectedLocationCount(component, 1)

			component.onDisable(context)

			coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
		}

		@Test
		@DisplayName("preserves resumed session even without new gps points")
		fun preservesResumedSessionWithoutNewGpsPoint() = runTest {
			val component = createComponent(isUserInitiated = false)
			setSession(component, nonEmptySession(collections = 5, distanceInM = 150f))
			setIsNewSession(component, false)

			component.onDisable(context)

			coVerify(exactly = 0) { mockSegmentDao.deleteById(any()) }
			// session.id = 42 > 0 → update (segment was pre-inserted in initializeSession)
			coVerify(exactly = 1) { mockSegmentDao.update(any<SessionSegment>()) }
		}
	}

	@Nested
	@DisplayName("teleport distance leakage prevention (FIX-001a)")
	inner class TeleportDistanceLeakage {

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
		@DisplayName("teleport jump distance is NOT accumulated when location rejected")
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
		@DisplayName("normal movement distance IS accumulated when location accepted")
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
		@DisplayName("accumulates the bridged distance-from-previous, not the raw cycle distance")
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
		@DisplayName("mixed sequence: teleport then normal produces only normal distance")
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
}
