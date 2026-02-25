package com.adsamcik.tracker.app.ui

import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.ui.navigation.Dashboard
import com.adsamcik.tracker.app.ui.navigation.Stats
import com.adsamcik.tracker.app.ui.navigation.Map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MainViewModelTest {

    @Test
    fun `initial route is Dashboard`() = runTest {
        val viewModel = MainViewModel()
        assertEquals(Dashboard, viewModel.currentRoute.first())
    }

    @Test
    fun `setCurrentRoute updates state correctly`() = runTest {
        val viewModel = MainViewModel()
        
        viewModel.setCurrentRoute(Stats)
        assertEquals(Stats, viewModel.currentRoute.first())
        
        viewModel.setCurrentRoute(Map)
        assertEquals(Map, viewModel.currentRoute.first())
    }
}
