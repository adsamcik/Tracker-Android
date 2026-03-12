package com.adsamcik.tracker.tracker.component.consumer

import android.content.Context
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Regression tests for [SessionTrackerComponent] empty-session guard.
 *
 * Verifies that rapid start/stop cycles do NOT persist empty sessions
 * (0 collections, 0 distance) to the Room database.
 *
 * See bug: "Empty Sessions Saved During Rapid Start/Stop".
 */
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
}
