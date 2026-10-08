package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SharedUiLocationProviderTest {
	@Before
	fun installMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }

	@After
	fun restoreMainDispatcher() { Dispatchers.resetMain() }

	@Test
	fun `cancelling one feature collector releases only its temporary lease`() = runTest {
		val owner = mockk<SharedLocationSourceController>()
		val lifecycle = mockk<Lifecycle>(relaxed = true)
		val fixes = MutableSharedFlow<UiLocationEvent>(extraBufferCapacity = 1)
		every { lifecycle.currentState } returns Lifecycle.State.STARTED
		every { owner.uiLocationEvents } returns fixes
		every { owner.uiPermissionGranted() } returns true
		every { owner.uiProviderAvailable() } returns true
		coEvery { owner.acquireUiLease(any()) } returns 7L
		coEvery { owner.releaseUiLease(7L) } returns Unit
		val provider = SharedUiLocationProvider(owner, lifecycle,
			elapsedRealtimeNanos = { 20_000_000_000L }, wallTimeMillis = { 100L })
		val received = mutableListOf<Location>()
		val collector = backgroundScope.launch {
			provider.locationUpdates(UiLocationRequest("game", 2_000L,
				cancelOnAppBackground = false)).collect { received += it }
		}
		runCurrent()
		assertEquals(1, provider.activeRequests.value.size)
		fixes.tryEmit(UiLocationEvent.Fix(location(19_000_000_000L)))
		runCurrent()
		assertEquals(1, received.size)
		collector.cancelAndJoin()
		assertEquals(0, provider.activeRequests.value.size)
		coVerify(exactly = 1) { owner.releaseUiLease(7L) }
	}

	@Test
	fun `map lease releases in background and reacquires on foreground return`() = runTest {
		val lifecycleOwner = mockk<LifecycleOwner>()
		val lifecycle = LifecycleRegistry(lifecycleOwner)
		every { lifecycleOwner.lifecycle } returns lifecycle
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
		val owner = mockk<SharedLocationSourceController>()
		every { owner.uiLocationEvents } returns MutableSharedFlow<UiLocationEvent>(extraBufferCapacity = 1)
		every { owner.uiPermissionGranted() } returns true
		every { owner.uiProviderAvailable() } returns true
		coEvery { owner.acquireUiLease(any()) } returnsMany listOf(1L, 2L)
		coEvery { owner.releaseUiLease(any()) } returns Unit
		val provider = SharedUiLocationProvider(owner, lifecycle)
		val collector = backgroundScope.launch {
			provider.locationUpdates(UiLocationRequest("map", 2_000L)).collect()
		}
		runCurrent()
		assertEquals(0, provider.activeRequests.value.size)
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
		runCurrent()
		assertEquals(1, provider.activeRequests.value.size)
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
		runCurrent()
		assertEquals(0, provider.activeRequests.value.size)
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
		runCurrent()
		assertEquals(1, provider.activeRequests.value.size)
		collector.cancelAndJoin()
		coVerify(exactly = 1) { owner.releaseUiLease(1L) }
		coVerify(exactly = 1) { owner.releaseUiLease(2L) }
	}

	@Test
	fun `provider failure ends a game lease and releases it`() = runTest {
		val owner = mockk<SharedLocationSourceController>()
		val lifecycle = mockk<Lifecycle>(relaxed = true)
		val events = MutableSharedFlow<UiLocationEvent>(extraBufferCapacity = 1)
		every { lifecycle.currentState } returns Lifecycle.State.CREATED
		every { owner.uiLocationEvents } returns events
		every { owner.uiPermissionGranted() } returns true
		every { owner.uiProviderAvailable() } returns true
		coEvery { owner.acquireUiLease(any()) } returns 8L
		coEvery { owner.releaseUiLease(8L) } returns Unit
		val provider = SharedUiLocationProvider(owner, lifecycle)
		val result = async {
			runCatching {
				provider.locationUpdates(UiLocationRequest("game", 1_000L,
					cancelOnAppBackground = false)).first()
			}
		}
		runCurrent()
		events.tryEmit(UiLocationEvent.Unavailable)
		runCurrent()
		assertTrue(result.await().exceptionOrNull() is IllegalStateException)
		coVerify(exactly = 1) { owner.releaseUiLease(8L) }
	}
	@Test
	fun `temporary consumers receive only real recent provider fixes`() {
		val now = 20_000_000_000L
		val recent = location(now - 2_000_000_000L)
		assertTrue(recent.isFreshForUi(5_000L, now))
		assertFalse(recent.isFreshForUi(1_000L, now))
		assertFalse(location(now + 1L).isFreshForUi(10_000L, now))
		assertFalse(location(0L).isFreshForUi(10_000L, now))
		assertFalse(location(now - 11_000_000_000L).isFreshForUi(10_000L, now))
	}


    @Test
    fun `My location receives a fresh unknown-accuracy point with invalid optional values absent`() = runTest {
        val owner = mockk<SharedLocationSourceController>()
        val lifecycle = mockk<Lifecycle>(relaxed = true)
        val fixes = MutableSharedFlow<UiLocationEvent>(extraBufferCapacity = 1)
        every { owner.uiLocationEvents } returns fixes
        every { owner.uiPermissionGranted() } returns true
        every { owner.uiProviderAvailable() } returns true
        coEvery { owner.acquireUiLease(any()) } returns 9L
        coEvery { owner.releaseUiLease(9L) } returns Unit
        val provider = SharedUiLocationProvider(owner, lifecycle,
            elapsedRealtimeNanos = { 20_000_000_000L })
        val result = async { provider.locationUpdates(UiLocationRequest("my-location", 10_000L,
            cancelOnAppBackground = false)).first() }
        runCurrent()
        val raw = location(19_000_000_000L).apply { removeAccuracy(); speed = Float.NaN }
        fixes.tryEmit(UiLocationEvent.Fix(raw))
        runCurrent()
        val received = result.await()
        assertEquals(raw.latitude, received.latitude, 0.0)
        assertEquals(raw.elapsedRealtimeNanos, received.elapsedRealtimeNanos)
        assertFalse(received.hasAccuracy())
        assertFalse(received.hasSpeed())
        assertTrue(raw.hasSpeed())
        coVerify(exactly = 1) { owner.releaseUiLease(9L) }
    }

	private fun location(observedElapsedNanos: Long) = Location("test").apply {
		latitude = 50.0
		longitude = 14.0
		accuracy = 5f
		elapsedRealtimeNanos = observedElapsedNanos
	}
}
