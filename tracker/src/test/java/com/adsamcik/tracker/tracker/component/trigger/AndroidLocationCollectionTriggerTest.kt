package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.test.FakePreferencesHelper
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Unit tests for AndroidLocationCollectionTrigger.updateInterval() functionality.
 *
 * Coverage:
 * - updateInterval() applies correct interval to LocationManager
 * - updateInterval() applies correct minDistance parameter
 * - updateInterval() removes old updates before restarting
 * - Multiple updateInterval() calls work correctly
 * - LocationManager parameters match policy requirements
 *
 * Note: Uses Robolectric shadow of LocationManager for testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidLocationCollectionTriggerTest {

	private lateinit var context: Context
	private lateinit var receiver: TrackerTimerReceiver
	private lateinit var trigger: AndroidLocationCollectionTrigger
	private lateinit var locationManager: LocationManager

	@Before
	fun setup() {
		// Setup fake preferences to avoid Resources$NotFoundException
		FakePreferencesHelper.setup()
		
		context = ApplicationProvider.getApplicationContext()
		receiver = mockk(relaxed = true)
		trigger = AndroidLocationCollectionTrigger()
		
		// Get real LocationManager from Robolectric context
		locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
		
		// Mock LocationManager for verification
		mockkObject(locationManager)
		every { 
			locationManager.requestLocationUpdates(
				any<String>(),
				any<Long>(),
				any<Float>(),
				any<LocationListener>(),
				any()
			) 
		} just Runs
		every { locationManager.removeUpdates(any<LocationListener>()) } just Runs
	}

	@After
	fun tearDown() {
		FakePreferencesHelper.tearDown()
		unmockkAll()
	}

	@Test
	fun `onEnable requests location updates with default preferences`() {
		// Enable trigger
		trigger.onEnable(context, receiver)

		// Verify location updates were requested
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				any<Long>(),
				any<Float>(),
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval removes old location updates before restarting`() {
		// Enable trigger first
		trigger.onEnable(context, receiver)
		
		// Clear mock invocations
		clearMocks(locationManager, answers = false)

		// Update interval
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		// Verify old updates were removed
		verify(exactly = 1) { locationManager.removeUpdates(any<LocationListener>()) }
		
		// Verify new updates were requested
		verify(exactly = 1) { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				any<Long>(),
				any<Float>(),
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval with 30-second interval configures LocationManager correctly`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update to ACTIVE_MODERATE interval (30s, 15m)
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		// Verify LocationManager parameters
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				30 * Time.SECOND_IN_MILLISECONDS,
				15.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval with 10-second interval configures LocationManager for high-frequency tracking`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update to ACTIVE_ELEVATED interval (10s, 10m)
		trigger.updateInterval(context, intervalSeconds = 10, minDistanceMeters = 10)

		// Verify high-frequency parameters
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				10 * Time.SECOND_IN_MILLISECONDS,
				10.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval with 300-second interval configures LocationManager for passive tracking`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update to PASSIVE_LOW interval (300s, 50m)
		trigger.updateInterval(context, intervalSeconds = 300, minDistanceMeters = 50)

		// Verify passive tracking parameters
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				300 * Time.SECOND_IN_MILLISECONDS,
				50.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval with 120-second interval configures LocationManager for movement detection`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update to MOVEMENT_SUSPECTED interval (120s, 30m)
		trigger.updateInterval(context, intervalSeconds = 120, minDistanceMeters = 30)

		// Verify movement detection parameters
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				120 * Time.SECOND_IN_MILLISECONDS,
				30.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `multiple updateInterval calls correctly restart location updates each time`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// First update: ACTIVE_MODERATE (30s, 15m)
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)
		
		// Verify first update
		verify(exactly = 1) { locationManager.removeUpdates(any<LocationListener>()) }
		verify(exactly = 1) { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				30 * Time.SECOND_IN_MILLISECONDS,
				15.0f,
				any<LocationListener>(),
				any()
			) 
		}

		clearMocks(locationManager, answers = false)

		// Second update: ACTIVE_ELEVATED (10s, 10m)
		trigger.updateInterval(context, intervalSeconds = 10, minDistanceMeters = 10)
		
		// Verify second update
		verify(exactly = 1) { locationManager.removeUpdates(any<LocationListener>()) }
		verify(exactly = 1) { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				10 * Time.SECOND_IN_MILLISECONDS,
				10.0f,
				any<LocationListener>(),
				any()
			) 
		}

		clearMocks(locationManager, answers = false)

		// Third update: PASSIVE_LOW (300s, 50m)
		trigger.updateInterval(context, intervalSeconds = 300, minDistanceMeters = 50)
		
		// Verify third update
		verify(exactly = 1) { locationManager.removeUpdates(any<LocationListener>()) }
		verify(exactly = 1) { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				300 * Time.SECOND_IN_MILLISECONDS,
				50.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval applies both interval and distance parameters`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update with specific values
		val intervalSeconds = 45
		val minDistanceMeters = 25

		trigger.updateInterval(context, intervalSeconds, minDistanceMeters)

		// Verify both parameters applied
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				45 * Time.SECOND_IN_MILLISECONDS,
				25.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `onDisable removes location updates`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Disable trigger
		trigger.onDisable(context)

		// Verify location updates were removed
		verify(exactly = 1) { locationManager.removeUpdates(any<LocationListener>()) }
	}

	@Test
	fun `updateInterval uses GPS_PROVIDER consistently`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update multiple times
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)
		trigger.updateInterval(context, intervalSeconds = 10, minDistanceMeters = 10)
		trigger.updateInterval(context, intervalSeconds = 300, minDistanceMeters = 50)

		// Verify all calls use GPS_PROVIDER
		verify(exactly = 3) { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				any<Long>(),
				any<Float>(),
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval with zero distance still configures valid request`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update with zero distance (edge case)
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 0)

		// Verify request was created (zero distance is valid)
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				30 * Time.SECOND_IN_MILLISECONDS,
				0.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}

	@Test
	fun `updateInterval with very large interval configures correctly`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(locationManager, answers = false)

		// Update with 1-hour interval (edge case for very passive tracking)
		trigger.updateInterval(context, intervalSeconds = 3600, minDistanceMeters = 100)

		// Verify large interval applied correctly
		verify { 
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				3600 * Time.SECOND_IN_MILLISECONDS,
				100.0f,
				any<LocationListener>(),
				any()
			) 
		}
	}
}
