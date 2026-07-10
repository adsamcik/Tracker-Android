package com.adsamcik.tracker.tracker.controller

import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultTrackerServiceControllerTest {

	private lateinit var controller: DefaultTrackerServiceController

	@BeforeEach
	fun setup() {
		controller = DefaultTrackerServiceController()
	}

	@Nested
	inner class `initial state` {

		@Test
		fun `service is not running by default`() {
			controller.isServiceRunning shouldBe false
			controller.isServiceRunningFlow.value shouldBe false
		}

		@Test
		fun `session info is null by default`() {
			controller.sessionInfoFlow.value.shouldBeNull()
		}

		@Test
		fun `session is null by default`() {
			controller.sessionFlow.value.shouldBeNull()
		}

		@Test
		fun `collection data is null by default`() {
			controller.collectionDataFlow.value.shouldBeNull()
		}

		@Test
		fun `path points is null by default`() {
			controller.pathPointsFlow.value.shouldBeNull()
		}

		@Test
		fun `last session is null by default`() {
			controller.lastSessionFlow.value.shouldBeNull()
		}

		@Test
		fun `policy tier is OFF by default`() {
			controller.policyTierFlow.value shouldBe PolicyTier.OFF
		}

		@Test
		fun `policy state is null by default`() {
			controller.policyStateFlow.value.shouldBeNull()
		}

		@Test
		fun `persistence error flow is null by default`() {
			controller.persistenceErrorFlow.shouldBeNull()
		}
	}

	@Nested
	inner class `updateServiceRunning` {

		@Test
		fun `sets running state to true`() {
			controller.updateServiceRunning(true)

			controller.isServiceRunning shouldBe true
			controller.isServiceRunningFlow.value shouldBe true
		}

		@Test
		fun `sets running state to false`() {
			controller.updateServiceRunning(true)
			controller.updateServiceRunning(false)

			controller.isServiceRunning shouldBe false
		}

		@Test
		fun `flow emits state changes`() = runTest {
			controller.isServiceRunningFlow.test {
				awaitItem() shouldBe false

				controller.updateServiceRunning(true)
				awaitItem() shouldBe true

				controller.updateServiceRunning(false)
				awaitItem() shouldBe false
			}
		}

		@Test
		fun `idempotent when setting same value`() = runTest {
			controller.isServiceRunningFlow.test {
				awaitItem() shouldBe false

				controller.updateServiceRunning(false)
				expectNoEvents()
			}
		}
	}

	@Nested
	inner class `updateSessionInfo` {

		@Test
		fun `sets session info`() {
			val info = TrackerSessionInfo(isInitiatedByUser = true)
			controller.updateSessionInfo(info)

			controller.sessionInfoFlow.value shouldBe info
		}

		@Test
		fun `clears session info with null`() {
			controller.updateSessionInfo(TrackerSessionInfo(isInitiatedByUser = true))
			controller.updateSessionInfo(null)

			controller.sessionInfoFlow.value.shouldBeNull()
		}

		@Test
		fun `flow emits session info changes`() = runTest {
			controller.sessionInfoFlow.test {
				awaitItem().shouldBeNull()

				val info = TrackerSessionInfo(isInitiatedByUser = false)
				controller.updateSessionInfo(info)
				awaitItem() shouldBe info
			}
		}
	}

	@Nested
	inner class `updateSession` {

		@Test
		fun `sets session`() {
			val session = createSession(id = 1L)
			controller.updateSession(session)

			controller.sessionFlow.value?.id shouldBe session.id
		}

		@Test
		fun `setting null retains last session`() {
			val session = createSession(id = 42L)
			controller.updateSession(session)
			controller.updateSession(null)

			controller.sessionFlow.value.shouldBeNull()
			controller.lastSessionFlow.value?.id shouldBe session.id
		}

		@Test
		fun `setting null clears path points`() {
			controller.updateSession(null)

			controller.pathPointsFlow.value.shouldBeNull()
		}

		@Test
		fun `setting null retains last path points`() {
			val session = createSession(id = 5L)
			controller.updateSession(session)

			mockkStatic(android.location.Location::class)
			try {
				setupDistanceMock(100f)
				val data = createCollectionDataWithLocation(
					latitude = 10.0, longitude = 20.0
				)
				controller.updateCollectionData(data)

				controller.updateSession(null)

				controller.lastPathPointsFlow.value.shouldNotBeNull()
				controller.lastPathPointsFlow.value!!.first shouldBe 5L
			} finally {
				unmockkStatic(android.location.Location::class)
			}
		}

		@Test
		fun `last session not updated when no current session exists`() {
			controller.updateSession(null)

			controller.lastSessionFlow.value.shouldBeNull()
		}
	}

	@Nested
	inner class `updateCollectionData` {

		@BeforeEach
		fun setupMock() {
			mockkStatic(android.location.Location::class)
		}

		@AfterEach
		fun teardownMock() {
			unmockkStatic(android.location.Location::class)
		}

		@Test
		fun `sets collection data`() {
			val data = mockk<CollectionData>(relaxed = true)
			every { data.location } returns null
			controller.updateCollectionData(data)

			controller.collectionDataFlow.value shouldBe data
		}

		@Test
		fun `clears collection data with null`() {
			val data = mockk<CollectionData>(relaxed = true)
			every { data.location } returns null
			controller.updateCollectionData(data)
			controller.updateCollectionData(null)

			controller.collectionDataFlow.value.shouldBeNull()
		}

		@Test
		fun `creates path points for new session`() {
			setupDistanceMock(100f)
			val session = createSession(id = 10L)
			controller.updateSession(session)

			val location = createLocation(50.0, 14.0)
			val data = createCollectionDataWithLocation(location)
			controller.updateCollectionData(data)

			val pathPoints = controller.pathPointsFlow.value
			pathPoints.shouldNotBeNull()
			pathPoints.first shouldBe 10L
			pathPoints.second.size shouldBe 1
			pathPoints.second[0] shouldBe location.toModel()
		}

		@Test
		fun `stores path points in a structurally shared persistent list`() {
			setupDistanceMock(100f)
			controller.updateSession(createSession(id = 10L))

			controller.updateCollectionData(
				createCollectionDataWithLocation(createLocation(50.0, 14.0))
			)
			controller.updateCollectionData(
				createCollectionDataWithLocation(createLocation(50.001, 14.001))
			)

			controller.pathPointsFlow.value
				.shouldNotBeNull()
				.second
				.shouldBeInstanceOf<PersistentList<*>>()
		}

		@Test
		fun `appends path point when distance exceeds 10m`() {
			setupDistanceMock(50f)
			val session = createSession(id = 10L)
			controller.updateSession(session)

			val loc1 = createLocation(50.0, 14.0)
			controller.updateCollectionData(createCollectionDataWithLocation(loc1))

			val loc2 = createLocation(50.001, 14.001)
			controller.updateCollectionData(createCollectionDataWithLocation(loc2))

			val pathPoints = controller.pathPointsFlow.value
			pathPoints.shouldNotBeNull()
			pathPoints.second.size shouldBe 2
		}

		@Test
		fun `skips path point when distance is within 10m`() {
			setupDistanceMock(5f)
			val session = createSession(id = 10L)
			controller.updateSession(session)

			val loc1 = createLocation(50.0, 14.0)
			controller.updateCollectionData(createCollectionDataWithLocation(loc1))

			val loc2 = createLocation(50.00001, 14.00001)
			controller.updateCollectionData(createCollectionDataWithLocation(loc2))

			val pathPoints = controller.pathPointsFlow.value
			pathPoints.shouldNotBeNull()
			pathPoints.second.size shouldBe 1
		}

		@Test
		fun `resets path points when session changes`() {
			setupDistanceMock(100f)
			val session1 = createSession(id = 1L)
			controller.updateSession(session1)
			controller.updateCollectionData(
				createCollectionDataWithLocation(createLocation(50.0, 14.0))
			)
			controller.updateCollectionData(
				createCollectionDataWithLocation(createLocation(51.0, 15.0))
			)

			val session2 = createSession(id = 2L)
			controller.updateSession(session2)
			controller.updateCollectionData(
				createCollectionDataWithLocation(createLocation(48.0, 16.0))
			)

			val pathPoints = controller.pathPointsFlow.value
			pathPoints.shouldNotBeNull()
			pathPoints.first shouldBe 2L
			pathPoints.second.size shouldBe 1
		}

		@Test
		fun `no path points without active session`() {
			val data = createCollectionDataWithLocation(
				latitude = 50.0, longitude = 14.0
			)
			controller.updateCollectionData(data)

			controller.pathPointsFlow.value.shouldBeNull()
		}

		@Test
		fun `no path points when location is null`() {
			val session = createSession(id = 1L)
			controller.updateSession(session)

			val data = mockk<CollectionData>(relaxed = true)
			every { data.location } returns null
			controller.updateCollectionData(data)

			controller.pathPointsFlow.value.shouldBeNull()
		}
	}

	@Nested
	inner class `updatePolicyTier` {

		@Test
		fun `sets policy tier`() {
			controller.updatePolicyTier(PolicyTier.PRECISION)

			controller.policyTierFlow.value shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `flow emits tier changes`() = runTest {
			controller.policyTierFlow.test {
				awaitItem() shouldBe PolicyTier.OFF

				controller.updatePolicyTier(PolicyTier.ACTIVE)
				awaitItem() shouldBe PolicyTier.ACTIVE

				controller.updatePolicyTier(PolicyTier.AMBIENT)
				awaitItem() shouldBe PolicyTier.AMBIENT
			}
		}
	}

	@Nested
	inner class `updatePolicyState` {

		@Test
		fun `sets policy state`() {
			val state = PolicyState(
				tier = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.ACCUMULATOR_ESCALATION
			)
			controller.updatePolicyState(state)

			controller.policyStateFlow.value shouldBe state
		}

		@Test
		fun `raw policy state does not overwrite effective policy tier`() {
			controller.updatePolicyTier(PolicyTier.AMBIENT)
			val state = PolicyState(
				tier = PolicyTier.PRECISION,
				transitionReason = PolicyState.TransitionReason.USER_INITIATED
			)
			controller.updatePolicyState(state)

			controller.policyTierFlow.value shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `null state does not change policy tier`() {
			controller.updatePolicyTier(PolicyTier.ACTIVE)
			controller.updatePolicyState(null)

			controller.policyStateFlow.value.shouldBeNull()
			controller.policyTierFlow.value shouldBe PolicyTier.ACTIVE
		}
	}

	@Nested
	inner class `update live detector states` {
		@Test
		fun `sets ski projection state`() {
			val state = LiveSkiState(
				state = LiveSkiPhase.DOWNHILL_RUN,
				stateEntryTimeMs = 1L,
				stateDurationMs = 2L,
				completedRunCount = 3,
				isConfirmedSkiSession = true,
				currentRunVerticalM = 4f,
				currentRunMaxSpeedMps = 5f,
				totalVerticalM = 6f,
				totalRunCount = 7,
			)

			controller.updateSkiState(state)

			controller.skiStateFlow.value shouldBe state
		}

		@Test
		fun `sets sailing projection state`() {
			val state = LiveSailingState(
				state = LiveSailingPhase.SAILING,
				stateEntryTimeMs = 1L,
				stateDurationMs = 2L,
				totalSailingDurationMs = 3L,
				totalSailingDistanceM = 4f,
				isConfirmedSailingSession = true,
				currentSpeedMps = 5f,
				maxSpeedMps = 6f,
			)

			controller.updateSailingState(state)

			controller.sailingStateFlow.value shouldBe state
		}

		@Test
		fun `sets plane projection state`() {
			val state = LivePlaneState(
				state = LivePlanePhase.CLIMBING,
				stateEntryTimeMs = 1L,
				stateDurationMs = 2L,
				totalAirborneDurationMs = 3L,
				isConfirmedFlight = true,
				currentVerticalRateMps = 4f,
				maxSpeedMps = 5f,
			)

			controller.updatePlaneState(state)

			controller.planeStateFlow.value shouldBe state
		}
	}

	@Nested
	inner class `updatePersistenceErrorFlow` {

		@Test
		fun `sets error flow`() {
			val errorFlow = MutableSharedFlow<PersistenceError>()
			controller.updatePersistenceErrorFlow(errorFlow)

			controller.persistenceErrorFlow shouldBe errorFlow
		}

		@Test
		fun `clears error flow with null`() {
			controller.updatePersistenceErrorFlow(MutableSharedFlow())
			controller.updatePersistenceErrorFlow(null)

			controller.persistenceErrorFlow.shouldBeNull()
		}
	}

	@Nested
	inner class `concurrent access` {

		@Test
		fun `rapid service state toggling is consistent`() = runTest {
			repeat(100) {
				controller.updateServiceRunning(true)
				controller.updateServiceRunning(false)
			}
			controller.isServiceRunning shouldBe false
		}

		@Test
		fun `rapid session updates are consistent`() = runTest {
			repeat(50) { i ->
				val session = createSession(id = i.toLong())
				controller.updateSession(session)
			}
			val lastSession = controller.sessionFlow.value
			lastSession.shouldNotBeNull()
			lastSession.id shouldBe 49L
		}
	}

	// --- Helpers ---

	private fun createSession(id: Long = 1L): TrackerSession {
		val session = mockk<TrackerSession>(relaxed = true)
		every { session.id } returns id
		return session
	}

	private fun createLocation(latitude: Double, longitude: Double): Location {
		return Location(
			time = System.currentTimeMillis(),
			latitude = latitude,
			longitude = longitude,
			altitude = null,
			horizontalAccuracy = 10f,
			verticalAccuracy = null,
			speed = null,
			speedAccuracy = null
		)
	}

	private fun createCollectionDataWithLocation(location: Location): CollectionData {
		val data = mockk<CollectionData>(relaxed = true)
		every { data.location } returns location
		return data
	}

	private fun createCollectionDataWithLocation(
		latitude: Double,
		longitude: Double
	): CollectionData {
		return createCollectionDataWithLocation(createLocation(latitude, longitude))
	}

	private fun setupDistanceMock(distance: Float) {
		every {
			android.location.Location.distanceBetween(
				any(), any(), any(), any(), any()
			)
		} answers {
			val results = arg<FloatArray>(4)
			results[0] = distance
		}
	}
}
