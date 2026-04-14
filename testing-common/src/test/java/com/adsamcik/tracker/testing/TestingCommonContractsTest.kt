package com.adsamcik.tracker.testing

import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import com.adsamcik.tracker.testing.data.TestDataFactory
import com.adsamcik.tracker.testing.fake.FakeLocationSource
import com.adsamcik.tracker.testing.fake.FakeLockManager
import com.adsamcik.tracker.testing.fake.FakeTrackerSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TestingCommonContractsTest {

	@Test
	fun `fake location source emits expected location data`() = runTest {
		val fakeLocationSource = FakeLocationSource()
		val nextLocation = async { fakeLocationSource.locations.first() }
		runCurrent()

		fakeLocationSource.emitLocation(
			lat = 50.087451,
			lon = 14.420671,
			accuracy = 7.5f,
			time = 1_234L,
			altitude = 220.0,
			speed = 3.5f,
			bearing = 180f,
			provider = "gps",
		)

		val emitted = nextLocation.await()
		assertEquals(1, fakeLocationSource.emittedLocations.size)
		assertEquals(emitted, fakeLocationSource.emittedLocations.single())
		assertEquals(50.087451, emitted.latitude, 0.0)
		assertEquals(14.420671, emitted.longitude, 0.0)
		assertEquals(7.5f, emitted.accuracy)
		assertEquals(1_234L, emitted.time)
		assertEquals(220.0, emitted.altitude, 0.0)
		assertEquals(3.5f, emitted.speed)
		assertEquals(180f, emitted.bearing)
		assertEquals("gps", emitted.provider)
	}

	@Test
	fun `fake location source path generator spaces coordinates and timestamps`() {
		val path = FakeLocationSource.generatePath(
			start = 50.0 to 14.0,
			end = 50.3 to 14.6,
			count = 4,
			intervalMs = 500L,
			startTime = 1_000L,
		)

		assertEquals(4, path.size)
		assertEquals(50.0, path.first().lat, 0.0)
		assertEquals(14.0, path.first().lon, 0.0)
		assertEquals(50.3, path.last().lat, 0.000001)
		assertEquals(14.6, path.last().lon, 0.000001)
		assertEquals(1_000L, path.first().time)
		assertEquals(2_500L, path.last().time)
		assertEquals(0.1, path[1].lat - path[0].lat, 0.000001)
		assertEquals(0.2, path[1].lon - path[0].lon, 0.000001)
	}

	@Test
	fun `test dispatchers provider reuses provided dispatcher for every lane`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val provider = TestDispatchersProvider(dispatcher)

		assertSame(dispatcher, provider.testDispatcher)
		assertSame(dispatcher, provider.io)
		assertSame(dispatcher, provider.default)
		assertSame(dispatcher, provider.main)
		assertSame(dispatcher, provider.unconfined)
	}

	@Test
	fun `test dispatchers provider executes queued work through test scheduler`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val provider = TestDispatchersProvider(dispatcher)
		var ran = false

		backgroundScope.launch(provider.io) {
			ran = true
		}

		assertFalse(ran)
		runCurrent()
		assertTrue(ran)
	}

	@Test
	fun `fake repositories expose mutable contract state and reset`() = runTest {
		val settingsRepository = FakeTrackerSettingsRepository()

		settingsRepository.setAutoUnitSwitch(true)
		settingsRepository.setLengthSystem(LengthSystem.Imperial)
		settingsRepository.setSpeedFormat(SpeedFormat.Minute)

		assertTrue(settingsRepository.currentState.autoUnitSwitch)
		assertEquals(LengthSystem.Imperial, settingsRepository.currentState.lengthSystem)
		assertEquals(SpeedFormat.Minute, settingsRepository.currentState.speedFormat)
		assertEquals(settingsRepository.currentState, settingsRepository.data.first())

		settingsRepository.reset()
		assertFalse(settingsRepository.currentState.autoUnitSwitch)
		assertEquals(LengthSystem.Metric, settingsRepository.currentState.lengthSystem)
		assertEquals(SpeedFormat.Hour, settingsRepository.currentState.speedFormat)
	}

	@Test
	fun `fake lock manager combines time and charge locks into isLocked`() {
		val lockManager = FakeLockManager()

		assertFalse(lockManager.isLocked)
		lockManager.setTimeLocked(true)
		assertTrue(lockManager.isTimeLocked)
		assertTrue(lockManager.isLocked)
		assertTrue(lockManager.isLockedFlow.value)

		lockManager.setChargeLocked(true)
		lockManager.setTimeLocked(false)
		assertTrue(lockManager.isChargeLocked)
		assertTrue(lockManager.isLocked)

		lockManager.setChargeLocked(false)
		assertFalse(lockManager.isLocked)
		assertFalse(lockManager.isLockedFlow.value)
	}

	@Test
	fun `test data factory provides empty long and path-oriented fixtures`() {
		val emptySession = TestDataFactory.createEmptySession(id = 9L)
		val longSession = TestDataFactory.createLongSession(id = 11L, durationHours = 6)
		val path = TestDataFactory.createLocationPath(
			count = 3,
			startTime = 2_000L,
			intervalMillis = 2_500L,
			startLatitude = 49.0,
			startLongitude = 15.0,
			latitudeStep = 0.01,
			longitudeStep = 0.02,
		)

		assertEquals(9L, emptySession.id)
		assertEquals(0, emptySession.collections)
		assertEquals(0f, emptySession.distanceInM)
		assertEquals(0, emptySession.steps)

		assertEquals(11L, longSession.id)
		assertEquals(6 * 3600, longSession.collections)
		assertEquals(6 * 5000f, longSession.distanceInM)
		assertEquals(6 * 6000, longSession.steps)
		assertEquals(6 * 3_600_000L, longSession.end - longSession.start)

		assertEquals(3, path.size)
		assertEquals(2_000L, path.first().time)
		assertEquals(7_000L, path.last().time)
		assertEquals(49.02, path.last().latitude, 0.0)
		assertEquals(15.04, path.last().longitude, 0.0)
	}
}
