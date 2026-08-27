package com.adsamcik.tracker.shared.base.location

import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultUiLocationProviderTest {
    @Test
    fun `backgrounding cancels requests and clears their bookkeeping`() = runTest {
        val client = mockk<FusedLocationProviderClient>(relaxed = true)
        every {
            client.requestLocationUpdates(
                any<LocationRequest>(),
                any<LocationCallback>(),
                any<Looper>(),
            )
        } returns Tasks.forResult<Void>(null)
        val owner = TestLifecycleOwner()
        val provider = DefaultUiLocationProvider(client, owner.lifecycle, nowMillis = { 123L })

        owner.moveTo(Lifecycle.Event.ON_START)
        val collection = async {
            provider.locationUpdates(UiLocationRequest("map", 1_000)).collect { _ -> }
        }
        advanceUntilIdle()
        provider.activeRequests.value.shouldContainExactly(UiLocationRequestInfo("map", 123L))

        owner.moveTo(Lifecycle.Event.ON_STOP)
        advanceUntilIdle()

        provider.activeRequests.value.shouldContainExactly()
        verify(exactly = 1) {
            client.removeLocationUpdates(any<com.google.android.gms.location.LocationCallback>())
        }
        collection.await()
    }

    @Test
    fun `uses framework GPS when Play Services is unavailable`() = runTest {
        val client = mockk<FusedLocationProviderClient>(relaxed = true)
        val manager = mockk<LocationManager>()
        val listener = slot<LocationListener>()
        every { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true
        every {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                capture(listener),
                any(),
            )
        } just Runs
        every { manager.removeUpdates(any<LocationListener>()) } just Runs
        val owner = TestLifecycleOwner()
        val provider = DefaultUiLocationProvider(
            client,
            owner.lifecycle,
            nowMillis = { 321L },
            locationManager = manager,
            fusedAvailable = { false },
        )

        owner.moveTo(Lifecycle.Event.ON_START)
        val collection = async {
            provider.locationUpdates(UiLocationRequest("map", 1_000)).collect { _ -> }
        }
        advanceUntilIdle()
        provider.activeRequests.value.shouldContainExactly(UiLocationRequestInfo("map", 321L))

        verify(exactly = 1) {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                listener.captured,
                any(),
            )
        }
        verify(exactly = 0) {
            client.requestLocationUpdates(
                any<LocationRequest>(),
                any<LocationCallback>(),
                any<Looper>(),
            )
        }

        owner.moveTo(Lifecycle.Event.ON_STOP)
        advanceUntilIdle()

        collection.await()
        provider.activeRequests.value.shouldContainExactly()
        verify(exactly = 1) { manager.removeUpdates(listener.captured) }
    }

    @Test
    fun `falls back when fused registration fails asynchronously`() = runTest {
        val client = mockk<FusedLocationProviderClient>(relaxed = true)
        every {
            client.requestLocationUpdates(
                any<LocationRequest>(),
                any<LocationCallback>(),
                any<Looper>(),
            )
        } returns Tasks.forException<Void>(IllegalStateException("GMS unavailable"))
        val manager = mockk<LocationManager>(relaxed = true)
        every { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true
        val owner = TestLifecycleOwner()
        val provider = DefaultUiLocationProvider(
            client,
            owner.lifecycle,
            nowMillis = { 654L },
            locationManager = manager,
            fusedAvailable = { true },
        )

        owner.moveTo(Lifecycle.Event.ON_START)
        val collection = async {
            provider.locationUpdates(UiLocationRequest("map", 1_000)).collect { _ -> }
        }
        advanceUntilIdle()

        verify(exactly = 1) {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                any<LocationListener>(),
                any(),
            )
        }
        provider.activeRequests.value.shouldContainExactly(UiLocationRequestInfo("map", 654L))

        owner.moveTo(Lifecycle.Event.ON_STOP)
        advanceUntilIdle()
        collection.await()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun moveTo(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }
}
