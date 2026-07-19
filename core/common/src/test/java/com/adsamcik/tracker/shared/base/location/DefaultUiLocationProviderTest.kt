package com.adsamcik.tracker.shared.base.location

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
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
        val owner = TestLifecycleOwner()
        val provider = DefaultUiLocationProvider(client, owner.lifecycle) { 123L }

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

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun moveTo(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }
}
