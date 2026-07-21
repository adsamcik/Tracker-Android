package com.adsamcik.tracker.tracker.component.trigger

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.LocationIngressDisposition
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.tracker.component.LocationRequestFidelity
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.isLocationObservationOnly
import com.adsamcik.tracker.tracker.test.FakePreferencesHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for FusedLocationCollectionTrigger.updateInterval() functionality.
 *
 * Coverage:
 * - updateInterval() creates new LocationRequest with correct interval
 * - updateInterval() applies minDistanceMeters parameter
 * - updateInterval() removes old location updates before restarting
 * - Multiple updateInterval() calls work correctly
 * - LocationRequest parameters match policy requirements
 *
 * Note: Uses MockK to verify Google Play Services LocationRequest configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FusedLocationCollectionTriggerTest {

	private lateinit var context: Context
	private lateinit var receiver: TrackerTimerReceiver
	private lateinit var trigger: FusedLocationCollectionTrigger
	private lateinit var mockClient: FusedLocationProviderClient

	@Before
	fun setup() {
		// Setup fake preferences to avoid Resources$NotFoundException
		FakePreferencesHelper.setup()
		
		context = ApplicationProvider.getApplicationContext()
		receiver = mockk(relaxed = true)
		trigger = FusedLocationCollectionTrigger()
		
		// Mock FusedLocationProviderClient
		mockClient = mockk(relaxed = true)
		
		// Mock LocationServices.getFusedLocationProviderClient()
		mockkStatic("com.google.android.gms.location.LocationServices")
		every { 
			com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(any<Context>())
		} returns mockClient

		// Mock Reporter to prevent uninitialized errors in tests
		mockkObject(com.adsamcik.tracker.logger.Reporter)
		every { com.adsamcik.tracker.logger.Reporter.report(any<Throwable>()) } just runs
		every { com.adsamcik.tracker.logger.Reporter.report(any<String>()) } just runs
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
			mockClient.requestLocationUpdates(
				any<LocationRequest>(), 
				any<LocationCallback>(), 
				any()
			) 
		}
	}

	@Test
	fun `updateInterval re-requests location updates with same callback (no remove gap)`() {
		// Enable trigger first
		trigger.onEnable(context, receiver)

		// Clear mock invocations
		clearMocks(mockClient, answers = false)

		// Update interval
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		// Production behavior (see FusedLocationCollectionTrigger.updateInterval KDoc):
		// re-requesting with the SAME callback atomically updates delivery parameters
		// without a forced stop/start gap. removeLocationUpdates is NOT called here —
		// it would only introduce a brief gap where location samples could be missed.
		verify(exactly = 0) { mockClient.removeLocationUpdates(any<LocationCallback>()) }

		// Verify new updates were requested with the same callback instance.
		verify(exactly = 1) {
			mockClient.requestLocationUpdates(
				any<LocationRequest>(),
				any<LocationCallback>(),
				any(),
			)
		}
	}

	@Test
	fun `updateInterval with 30-second interval configures LocationRequest correctly`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		// Capture LocationRequest on updateInterval
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		// Update to ACTIVE_MODERATE interval (30s, 15m)
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		// Verify LocationRequest was created
		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify LocationRequest parameters
		val request = requestSlot.captured
		assertEquals(30 * Time.SECOND_IN_MILLISECONDS, request.intervalMillis)
		assertEquals(15.0f, request.minUpdateDistanceMeters)
		assertEquals(30 * Time.SECOND_IN_MILLISECONDS, request.minUpdateIntervalMillis)
	}

	@Test
	fun `updateInterval with 10-second interval configures LocationRequest for high-frequency tracking`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		// Capture LocationRequest
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		// Update to ACTIVE_ELEVATED interval (10s, 10m)
		trigger.updateInterval(context, intervalSeconds = 10, minDistanceMeters = 10)

		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify high-frequency parameters
		val request = requestSlot.captured
		assertEquals(10 * Time.SECOND_IN_MILLISECONDS, request.intervalMillis)
		assertEquals(10.0f, request.minUpdateDistanceMeters)
		assertEquals(10 * Time.SECOND_IN_MILLISECONDS, request.minUpdateIntervalMillis)
	}

	@Test
	fun `updateInterval with 300-second interval configures LocationRequest for passive tracking`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		// Capture LocationRequest
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		// Update to PASSIVE_LOW interval (300s, 50m)
		trigger.updateInterval(context, intervalSeconds = 300, minDistanceMeters = 50)

		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify passive tracking parameters
		val request = requestSlot.captured
		assertEquals(300 * Time.SECOND_IN_MILLISECONDS, request.intervalMillis)
		assertEquals(50.0f, request.minUpdateDistanceMeters)
		assertEquals(300 * Time.SECOND_IN_MILLISECONDS, request.minUpdateIntervalMillis)
	}

	@Test
	fun `updateInterval with 120-second interval configures LocationRequest for movement detection`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		// Capture LocationRequest
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		// Update to MOVEMENT_SUSPECTED interval (120s, 30m)
		trigger.updateInterval(context, intervalSeconds = 120, minDistanceMeters = 30)

		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify movement detection parameters
		val request = requestSlot.captured
		assertEquals(120 * Time.SECOND_IN_MILLISECONDS, request.intervalMillis)
		assertEquals(30.0f, request.minUpdateDistanceMeters)
		assertEquals(120 * Time.SECOND_IN_MILLISECONDS, request.minUpdateIntervalMillis)
	}

	@Test
	fun `multiple updateInterval calls each re-request updates without intermediate removes`() {
		// Enable trigger
		trigger.onEnable(context, receiver)

		clearMocks(mockClient, answers = false)

		// First update: ACTIVE_MODERATE (30s, 15m)
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		// Verify first update — re-request only, no remove (production avoids stop/start gap).
		verify(exactly = 0) { mockClient.removeLocationUpdates(any<LocationCallback>()) }
		verify(exactly = 1) { mockClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any()) }

		clearMocks(mockClient, answers = false)

		// Second update: ACTIVE_ELEVATED (10s, 10m)
		trigger.updateInterval(context, intervalSeconds = 10, minDistanceMeters = 10)

		// Verify second update
		verify(exactly = 0) { mockClient.removeLocationUpdates(any<LocationCallback>()) }
		verify(exactly = 1) { mockClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any()) }

		clearMocks(mockClient, answers = false)

		// Third update: PASSIVE_LOW (300s, 50m)
		trigger.updateInterval(context, intervalSeconds = 300, minDistanceMeters = 50)

		// Verify third update
		verify(exactly = 0) { mockClient.removeLocationUpdates(any<LocationCallback>()) }
		verify(exactly = 1) { mockClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any()) }
	}

	@Test
	fun `updateInterval applies both interval and distance parameters`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		// Update with specific values
		val intervalSeconds = 45
		val minDistanceMeters = 25

		trigger.updateInterval(context, intervalSeconds, minDistanceMeters)

		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify both parameters applied
		val request = requestSlot.captured
		assertEquals(45 * Time.SECOND_IN_MILLISECONDS, request.intervalMillis)
		assertEquals(25.0f, request.minUpdateDistanceMeters)
		assertEquals(45 * Time.SECOND_IN_MILLISECONDS, request.minUpdateIntervalMillis)
	}

	@Test
	fun `onDisable removes location updates`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		clearMocks(mockClient, answers = false)

		// Disable trigger
		trigger.onDisable(context)

		// Verify location updates were removed
		verify(exactly = 1) { mockClient.removeLocationUpdates(any<LocationCallback>()) }
	}

	@Test
	fun `location callback ignores updates after disable`() {
		val callbackSlot = slot<LocationCallback>()
		every {
			mockClient.requestLocationUpdates(any<LocationRequest>(), capture(callbackSlot), any())
		} returns mockk(relaxed = true)

		trigger.onEnable(context, receiver)
		trigger.onDisable(context)

		val location = Location("test").apply {
			latitude = 50.0
			longitude = 14.0
			time = System.currentTimeMillis()
			elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
		}

		callbackSlot.captured.onLocationResult(LocationResult.create(listOf(location)))

		verify(exactly = 0) { receiver.onUpdate(any()) }
		verify(exactly = 0) { receiver.onError(any()) }
	}

	@Test
	fun `provider batch preserves rejected fixes before accepted filtering`() {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val callbackSlot = slot<LocationCallback>()
		val cycleSlot = slot<TrackingCycle>()
		every {
			mockClient.requestLocationUpdates(any<LocationRequest>(), capture(callbackSlot), any())
		} returns mockk(relaxed = true)
		every { receiver.onUpdate(capture(cycleSlot)) } returns mockk(relaxed = true)
		trigger.onEnable(context, receiver)

		val nowElapsed = SystemClock.elapsedRealtimeNanos()
		val stale = Location("fused").apply {
			latitude = 50.0
			longitude = 14.0
			time = System.currentTimeMillis() - 20_000L
			elapsedRealtimeNanos = 1L
		}
		val invalid = Location("fused").apply {
			latitude = Double.NaN
			longitude = 14.0
			time = System.currentTimeMillis()
			elapsedRealtimeNanos = nowElapsed
		}
		val valid = Location("fused").apply {
			latitude = 50.1
			longitude = 14.1
			time = System.currentTimeMillis()
			elapsedRealtimeNanos = nowElapsed
		}

		callbackSlot.captured.onLocationResult(LocationResult.create(listOf(stale, invalid, valid)))

		verify(exactly = 1) { receiver.onUpdate(any()) }
		val cycle = cycleSlot.captured
		assertEquals(listOf(valid), cycle.location!!.locations)
		assertEquals(listOf(
			LocationIngressDisposition.REJECTED_STALE,
			LocationIngressDisposition.REJECTED_INVALID_COORDINATE,
			LocationIngressDisposition.DELIVERED_VALID,
		), cycle.locationObservations.map { it.ingressDisposition })
		assertEquals(listOf(0, 1, 2), cycle.locationObservations.map { it.metadata.batchIndex })
		assertEquals(listOf(3, 3, 3), cycle.locationObservations.map { it.metadata.batchSize })
	}

	@Test
	fun `invalid-only provider callback still emits a durable observation cycle`() {
		val callbackSlot = slot<LocationCallback>()
		val cycleSlot = slot<TrackingCycle>()
		every {
			mockClient.requestLocationUpdates(any<LocationRequest>(), capture(callbackSlot), any())
		} returns mockk(relaxed = true)
		every { receiver.onUpdate(capture(cycleSlot)) } returns mockk(relaxed = true)
		trigger.onEnable(context, receiver)

		val invalid = Location("fused").apply {
			latitude = 95.0
			longitude = 14.0
			time = System.currentTimeMillis()
			elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		}
		callbackSlot.captured.onLocationResult(LocationResult.create(listOf(invalid)))

		verify(exactly = 1) { receiver.onUpdate(any()) }
		val cycle = cycleSlot.captured
		assertTrue(cycle.isLocationObservationOnly())
		assertEquals(
			LocationIngressDisposition.REJECTED_INVALID_COORDINATE,
			cycle.locationObservations.single().ingressDisposition,
		)
	}

	@Test
	fun `LocationRequest defaults to balanced priority even with fine permission`() {
		// Fine permission alone must not activate GNSS before policy requests elevated fidelity.
		val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
		shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify balanced-first priority.
		val request = requestSlot.captured
		assertEquals(
			Priority.PRIORITY_BALANCED_POWER_ACCURACY,
			request.priority
		)
	}

	@Test
	fun `elevated fidelity uses high accuracy when fine permission is available`() {
		val shadowApp = org.robolectric.Shadows.shadowOf(
			context.applicationContext as android.app.Application,
		)
		shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		trigger.updateRequestFidelity(LocationRequestFidelity.HIGH_ACCURACY)

		val requestSlot = slot<LocationRequest>()
		every {
			mockClient.requestLocationUpdates(capture(requestSlot), any<LocationCallback>(), any())
		} returns mockk(relaxed = true)

		trigger.onEnable(context, receiver)

		assertEquals(Priority.PRIORITY_HIGH_ACCURACY, requestSlot.captured.priority)
	}

	@Test
	fun `delivered fix metadata records the active high accuracy request`() {
		val shadowApp = org.robolectric.Shadows.shadowOf(
			context.applicationContext as android.app.Application,
		)
		shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		trigger.updateRequestFidelity(LocationRequestFidelity.HIGH_ACCURACY)

		val callbackSlot = slot<LocationCallback>()
		every {
			mockClient.requestLocationUpdates(any<LocationRequest>(), capture(callbackSlot), any())
		} returns mockk(relaxed = true)
		trigger.onEnable(context, receiver)

		val location = Location("fused").apply {
			latitude = 50.0
			longitude = 14.0
			time = System.currentTimeMillis()
			elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		}
		callbackSlot.captured.onLocationResult(LocationResult.create(listOf(location)))

		val cycleSlot = slot<TrackingCycle>()
		verify(exactly = 1) { receiver.onUpdate(capture(cycleSlot)) }
		assertEquals(
			LocationRequestPriority.HIGH_ACCURACY,
			cycleSlot.captured.location?.lastFixMetadata?.requestPriority,
		)
	}

	@Test
	fun `elevated fidelity remains balanced with approximate permission`() {
		val shadowApp = org.robolectric.Shadows.shadowOf(
			context.applicationContext as android.app.Application,
		)
		shadowApp.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		shadowApp.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
		trigger.updateRequestFidelity(LocationRequestFidelity.HIGH_ACCURACY)

		val requestSlot = slot<LocationRequest>()
		every {
			mockClient.requestLocationUpdates(capture(requestSlot), any<LocationCallback>(), any())
		} returns mockk(relaxed = true)

		trigger.onEnable(context, receiver)

		assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, requestSlot.captured.priority)
	}

	@Test
	fun `updateInterval with zero distance still configures valid LocationRequest`() {
		// Enable trigger
		trigger.onEnable(context, receiver)
		
		val requestSlot = slot<LocationRequest>()
		clearMocks(mockClient, answers = false)

		// Update with zero distance (edge case)
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 0)

		verify { 
			mockClient.requestLocationUpdates(
				capture(requestSlot), 
				any<LocationCallback>(), 
				any()
			) 
		}

		// Verify request was created (zero distance is valid)
		val request = requestSlot.captured
		assertEquals(0.0f, request.minUpdateDistanceMeters)
		assertTrue(request.intervalMillis > 0)
	}

	@Test
	fun `onEnable handles SecurityException when permission revoked`() {
		// Simulate SecurityException thrown by FusedLocationProviderClient when permission is revoked
		every {
			mockClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
		} throws SecurityException("Client must have ACCESS_FINE_LOCATION permission")

		// Should not throw — graceful degradation
		trigger.onEnable(context, receiver)

		// Verify error was reported to receiver with STOP_SERVICE severity
		val errorSlot = slot<TrackerTimerErrorData>()
		verify { receiver.onError(capture(errorSlot)) }
		assertEquals(TrackerTimerErrorSeverity.STOP_SERVICE, errorSlot.captured.severity)
	}

	@Test
	fun `updateInterval handles SecurityException when permission revoked mid-tracking`() {
		// Enable normally first
		trigger.onEnable(context, receiver)

		clearMocks(mockClient, answers = false)
		// Now simulate permission revoked before updateInterval
		every {
			mockClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
		} throws SecurityException("Client must have ACCESS_FINE_LOCATION permission")
		every { mockClient.removeLocationUpdates(any<LocationCallback>()) } returns mockk(relaxed = true)

		// Should not throw — graceful degradation
		trigger.updateInterval(context, intervalSeconds = 30, minDistanceMeters = 15)

		// Verify error was reported
		val errorSlot = slot<TrackerTimerErrorData>()
		verify { receiver.onError(capture(errorSlot)) }
		assertEquals(TrackerTimerErrorSeverity.STOP_SERVICE, errorSlot.captured.severity)
	}
}
