package com.adsamcik.tracker.game.minigame.location

import android.Manifest
import android.app.Application
import android.location.Location as PlatformLocation
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import com.adsamcik.tracker.testing.fake.FakeUiLocationProvider
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
 * shared location provider is fake, so synthetic platform locations exercise
 * mapping, request translation, and lifecycle behavior without real GMS.
 *
 * Coverage:
 *  - permission missing -> flow terminates with SecurityException
 *  - permission granted -> fixes map into MiniGameLocationSample correctly
 *    (lat/lon/speed/accuracy/time)
 *  - missing optional fields (no speed, no accuracy, time == 0) fall back to
 *    safe defaults and Time.nowMillis
 *  - flow cancellation removes the shared provider request
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FusedMiniGameLocationSourceTest {

	private lateinit var application: Application
	private lateinit var locationProvider: FakeUiLocationProvider
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
		locationProvider = FakeUiLocationProvider()
		mockkObject(Time)
		every { Time.nowMillis } returns 1_700_000_000_000L
		source = FusedMiniGameLocationSource(application, locationProvider)
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

		source.samples(request).test {
			locationProvider.emit(
				platformLocation(
					latitude = 50.0876,
					longitude = 14.4213,
					speed = 2.5f,
					accuracy = 7.5f,
					time = 1_700_000_000_500L,
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

		val location = PlatformLocation("test").apply {
			latitude = 1.0
			longitude = 2.0
			time = 1_700_000_000_500L
			// no speed, no accuracy set on purpose
		}

		source.samples(request).test {
			locationProvider.emit(location)
			val sample = awaitItem()
			sample.speedMps shouldBe 0f
			sample.accuracyM shouldBe Float.MAX_VALUE
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `non-positive timestamp falls back to current time`() = runTest {
		grantFineLocation()

		source.samples(request).test {
			locationProvider.emit(
				platformLocation(
					latitude = 0.0,
					longitude = 0.0,
					speed = 0f,
					accuracy = 1f,
					time = 0L,
				),
			)
			awaitItem().timestampMs shouldBe 1_700_000_000_000L
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `cancellation removes shared location request`() = runTest {
		grantFineLocation()

		source.samples(request).test {
			locationProvider.activeRequests.value.size shouldBe 1
			cancelAndIgnoreRemainingEvents()
		}

		locationProvider.activeRequests.value.size shouldBe 0
	}

	@Test
	fun `preserves game cadence accuracy and displacement in shared request`() = runTest {
		grantFineLocation()
		val provider = mockk<UiLocationProvider>()
		val requestSlot = slot<UiLocationRequest>()
		every { provider.locationUpdates(capture(requestSlot)) } returns flowOf(
			platformLocation(
				latitude = 1.0,
				longitude = 2.0,
				speed = 0f,
				accuracy = 5f,
				time = 1L,
			),
		)
		val gameRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000L)
			.setMinUpdateIntervalMillis(3_000L)
			.setMinUpdateDistanceMeters(10f)
			.build()

		FusedMiniGameLocationSource(application, provider).samples(gameRequest).test {
			awaitItem()
			awaitComplete()
		}

		requestSlot.captured.intervalMillis shouldBe 5_000L
		requestSlot.captured.minUpdateIntervalMillis shouldBe 3_000L
		requestSlot.captured.minimumDisplacementMeters shouldBe 10f
		requestSlot.captured.highAccuracy shouldBe false
	}

	private fun grantFineLocation() {
		shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
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
