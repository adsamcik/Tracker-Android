package com.adsamcik.tracker.map.v2.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MapViewModelTest {
    @Test
    fun `view model instantiates with default state`() {
        val vm = MapViewModel()
        val state = vm.state.value
        assertNotNull(state)
        assertEquals(null, state.selectedLayerId)
        assertEquals(1f, state.quality)
    }
}
