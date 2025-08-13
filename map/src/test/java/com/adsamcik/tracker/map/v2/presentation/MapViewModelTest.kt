package com.adsamcik.tracker.map.v2.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    @Test
    fun `view model instantiates with default state`() = runTest {
        val vm = MapViewModel()
        val state = vm.state.first()
        assertNotNull(state)
        assertEquals(null, state.selectedLayerId)
        assertEquals(1f, state.quality)
    }
}
