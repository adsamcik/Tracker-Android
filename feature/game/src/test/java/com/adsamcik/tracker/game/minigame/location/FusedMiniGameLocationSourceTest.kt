package com.adsamcik.tracker.game.minigame.location

import android.Manifest
import android.app.Application
import android.location.Location as PlatformLocation
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.Time
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Isolated unit tests for [FusedMiniGameLocationSource].
 *
 * Robolectric provides a real Android [Application] so we can drive the
 * permission gate via `shadowOf(application).grantPermissions(...)`. The
 * Google Play Services [LocationServices.getFusedLocationProviderClient]
 * static call is mocked so we never touch real GMS, and the captured
 * [LocationCallback] lets us deliver synthetic [LocationResult]s to exercise
 * the mapping logic and lifecycle behaviour.
 *
 * Coverage:
 *  - permission missing -> flow terminates with SecurityException
 *  - permission granted -> fixes map into MiniGameLocationSample correctly
 *    (lat/lon/speed/accuracy/time)
 *  - missing optional fields (no speed, no accuracy, time == 0) fall back to
 *    safe defaults and Time.nowMillis
 *  - flow cancellation removes the registered callback so the framework
 *    subscription is released
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FusedMiniGameLocationSourceTest {

	private lateinit var application: Application
	private lateinit var client: FusedLocationProviderClient
	private lateinit var source: FusedMiniGameLocationSource

	private val request: LocationRequest =
		LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		shadowOf(application).denyPermissions(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
		client = mockk(relaxed = true)
		mockkStatic(LocationServices::class)
		every { LocationServices.getFusedLocationProviderClient(any<android.content.Context>()) } returns client
		mockkObject(Time)
		every { Time.nowMillis } returns 1_700_000_000_000L
		source = FusedMiniGameLocationSource(application)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `flow closes with SecurityException when permission missing`() = runTest {
		source.samples(request).test {
			val error = awaitError()
			error.shouldBeInstanceOf<SecurityException>()
			error.message shouldBe "Location permission not granted"
		}
	}

	@Test
	fun `valid fix maps lat lon speed accuracy and time`() = runTest {
		grantFineLocation()
		val callbackSlot = captureCallback()

		source.samples(request).test {
			callbackSlot.captured.onLocationResult(
				LocationResult.create(
					listOf(
						platformLocation(
							latitude = 50.0876,
							longitude = 14.4213,
							speed = 2.5f,
							accuracy = 7.5f,
							time = 1_700_000_000_500L,
						),
					),
				),
			)
			val sample = awaitItem()
			sample.latitude shouldBe 50.0876
			sample.longitude shouldBe 14.4213
			sample.speedMps shouldBe 2.5f
			sample.accuracyM shouldBe 7.5f
			sample.timestampMs shouldBe 1_700_000_000_500L
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `missing speed and accuracy fall back to safe defaults`() = runTest {
		grantFineLocation()
		val callbackSlot = captureCallback()

		val location = PlatformLocation("test").apply {
			latitude = 1.0
			longitude = 2.0
			time = 1_700_000_000_500L
			// no speed, no accuracy set on purpose
		}

		source.samples(request).test {
			callbackSlot.captured.onLocationResult(LocationResult.create(listOf(location)))
			val sample = awaitItem()
			sample.speedMps shouldBe 0f
			sample.accuracyM shouldBe Float.MAX_VALUE
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `non-positive timestamp falls back to current time`() = runTest {
		grantFineLocation()
		val callbackSlot = captureCallback()

		source.samples(request).test {
			callbackSlot.captured.onLocationResult(
				LocationResult.create(
					listOf(
						platformLocation(
							latitude = 0.0,
							longitude = 0.0,
							speed = 0f,
							accuracy = 1f,
							time = 0L,
						),
					),
				),
			)
			awaitItem().timestampMs shouldBe 1_700_000_000_000L
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `cancellation removes location updates`() = runTest {
		grantFineLocation()
		val callbackSlot = captureCallback()

		source.samples(request).test {
			cancelAndIgnoreRemainingEvents()
		}

		verify { client.removeLocationUpdates(callbackSlot.captured) }
	}

	private fun grantFineLocation() {
		shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
	}

	private fun captureCallback(): CapturingSlot<LocationCallback> {
		val callbackSlot = slot<LocationCallback>()
		every {
			client.requestLocationUpdates(
				any<LocationRequest>(),
				capture(callbackSlot),
				any(),
			)
		} returns mockk(relaxed = true)
		return callbackSlot
	}

	private fun platformLocation(
		latitude: Double,
		longitude: Double,
		speed: Float,
		accuracy: Float,
		time: Long,
	): PlatformLocation = PlatformLocation("test").apply {
		this.latitude = latitude
		this.longitude = longitude
		this.speed = speed
		this.accuracy = accuracy
		this.time = time
	}
}
