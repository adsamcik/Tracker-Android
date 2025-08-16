package com.adsamcik.tracker.map.v2.presentation

import com.adsamcik.tracker.map.presentation.MapViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Duration

class MapViewModelTest {
    @Test
    fun `view model instantiates with default state`() {
        val vm = MapViewModel()
        val state = vm.state.value
        assertNotNull(state)
        assertEquals(null, state.selectedLayerId)
        assertEquals(1f, state.quality)
        assertEquals(0, state.refreshVersion)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `select layer updates state and triggers debounced refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = MapViewModel(dispatcher = dispatcher, refreshDebounce = Duration.ofMillis(50))
    // Ensure collector is started
    advanceUntilIdle()
        vm.selectLayer("location-heatmap")
        // Before debounce elapses, no refresh yet
        assertEquals(0, vm.state.value.refreshVersion)
        // Advance virtual time to pass debounce
        advanceTimeBy(50)
    advanceUntilIdle()
        val state = vm.state.value
        assertEquals("location-heatmap", state.selectedLayerId)
        assertEquals(1, state.refreshVersion)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `quality updates coalesce into one refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = MapViewModel(dispatcher = dispatcher, refreshDebounce = Duration.ofMillis(60))
    // Ensure collector is started
    advanceUntilIdle()
        vm.setQuality(0.8f)
        vm.setQuality(0.9f)
        vm.setQuality(1.0f)
        // Only one refresh after the last emission once debounce elapses
        advanceTimeBy(60)
    advanceUntilIdle()
        val state = vm.state.value
        assertEquals(1.0f, state.quality)
        assertEquals(1, state.refreshVersion)
    }
}
