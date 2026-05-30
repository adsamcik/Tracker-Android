package com.adsamcik.tracker.game.minigame.location

import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.stats.api.TrackerLiveLocationFeed
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LiveOrFusedMiniGameLocationSource].
 *
 * Verifies the routing logic between the tracker live feed and the Fused
 * fallback. The Fused fallback itself is mocked so we can prove that:
 *   - when the tracker is active, the fallback is NEVER asked for samples;
 *   - when the tracker is not active, the fallback IS asked;
 *   - switching the tracker active flag mid-stream transparently swaps sides.
 *
 * Pure JVM unit tests — no Robolectric required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveOrFusedMiniGameLocationSourceTest {

	private val request: LocationRequest =
		LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()

	@Before
	fun setUp() {
		mockkObject(Time)
		every { Time.nowMillis } returns 1_700_000_000_000L
	}

	@After
	fun tearDown() {
		unmockkObject(Time)
	}

	@Test
	fun `tracker active routes through live feed and never calls fused fallback`() = runTest {
		val liveLocations = MutableSharedFlow<Location>(replay = 0)
		val feed = FakeLiveFeed(active = true, locationStream = liveLocations)
		val fused = mockk<FusedMiniGameLocationSource>(relaxed = true)
		val source = LiveOrFusedMiniGameLocationSource(feed, fused)

		source.samples(request).test {
			liveLocations.emit(location(latitude = 50.1, longitude = 14.4, speed = 3.5f))
			val sample = awaitItem()
			sample.latitude shouldBe 50.1
			sample.longitude shouldBe 14.4
			sample.speedMps shouldBe 3.5f
			cancelAndConsumeRemainingEvents()
		}

		verify(exactly = 0) { fused.samples(any()) }
	}

	@Test
	fun `tracker inactive delegates to fused fallback`() = runTest {
		val fusedFlow = MutableSharedFlow<MiniGameLocationSample>(replay = 0)
		val feed = FakeLiveFeed(active = false, locationStream = MutableSharedFlow())
		val fused = mockk<FusedMiniGameLocationSource>()
		every { fused.samples(any()) } returns fusedFlow
		val source = LiveOrFusedMiniGameLocationSource(feed, fused)

		source.samples(request).test {
			fusedFlow.emit(
				MiniGameLocationSample(
					latitude = 1.0,
					longitude = 2.0,
					speedMps = 0.5f,
					accuracyM = 5f,
					timestampMs = 42L,
				),
			)
			val sample = awaitItem()
			sample.latitude shouldBe 1.0
			sample.longitude shouldBe 2.0
			cancelAndConsumeRemainingEvents()
		}

		verify(exactly = 1) { fused.samples(any()) }
	}

	@Test
	fun `null speed and accuracy map to safe defaults`() = runTest {
		val liveLocations = MutableSharedFlow<Location>(replay = 0)
		val feed = FakeLiveFeed(active = true, locationStream = liveLocations)
		val fused = mockk<FusedMiniGameLocationSource>(relaxed = true)
		val source = LiveOrFusedMiniGameLocationSource(feed, fused)

		source.samples(request).test {
			liveLocations.emit(
				Location(
					time = 100L,
					latitude = 1.0,
					longitude = 2.0,
					altitude = null,
					horizontalAccuracy = null,
					verticalAccuracy = null,
					speed = null,
					speedAccuracy = null,
				),
			)
			val sample = awaitItem()
			sample.speedMps shouldBe 0f
			sample.accuracyM shouldBe Float.MAX_VALUE
			sample.timestampMs shouldBe 100L
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `non-positive timestamp falls back to current time`() = runTest {
		val liveLocations = MutableSharedFlow<Location>(replay = 0)
		val feed = FakeLiveFeed(active = true, locationStream = liveLocations)
		val fused = mockk<FusedMiniGameLocationSource>(relaxed = true)
		val source = LiveOrFusedMiniGameLocationSource(feed, fused)

		source.samples(request).test {
			liveLocations.emit(location(latitude = 1.0, longitude = 2.0, time = 0L))
			val sample = awaitItem()
			sample.timestampMs shouldBe 1_700_000_000_000L
			cancelAndConsumeRemainingEvents()
		}
	}

	@Test
	fun `tracker toggling on swaps from fused to live mid-stream`() = runTest {
		val liveLocations = MutableSharedFlow<Location>(replay = 0)
		val fusedFlow = MutableSharedFlow<MiniGameLocationSample>(replay = 0)
		val activeState = MutableStateFlow(false)
		val feed = FakeLiveFeed(activeFlow = activeState, locationStream = liveLocations)
		val fused = mockk<FusedMiniGameLocationSource>()
		every { fused.samples(any()) } returns fusedFlow
		val source = LiveOrFusedMiniGameLocationSource(feed, fused)

		source.samples(request).test {
			fusedFlow.emit(
				MiniGameLocationSample(
					latitude = 0.0,
					longitude = 0.0,
					speedMps = 0f,
					accuracyM = 1f,
					timestampMs = 1L,
				),
			)
			awaitItem().latitude shouldBe 0.0

			activeState.value = true
			liveLocations.emit(location(latitude = 9.9, longitude = 8.8, speed = 4f))
			awaitItem().latitude shouldBe 9.9

			cancelAndConsumeRemainingEvents()
		}
	}

	private fun location(
		latitude: Double,
		longitude: Double,
		speed: Float? = 1f,
		time: Long = 1_700_000_000_500L,
	): Location = Location(
		time = time,
		latitude = latitude,
		longitude = longitude,
		altitude = null,
		horizontalAccuracy = 10f,
		verticalAccuracy = null,
		speed = speed,
		speedAccuracy = null,
	)

	private class FakeLiveFeed(
		active: Boolean = false,
		activeFlow: MutableStateFlow<Boolean> = MutableStateFlow(active),
		private val locationStream: Flow<Location>,
	) : TrackerLiveLocationFeed {
		private val state: MutableStateFlow<Boolean> = activeFlow

		override val isActiveFlow: StateFlow<Boolean> = state.asStateFlow()
		override val isActive: Boolean get() = state.value
		override fun locations(): Flow<Location> = locationStream
	}
}
