package com.adsamcik.tracker.app.ui

import com.adsamcik.tracker.app.ui.navigation.AppRoute
import com.adsamcik.tracker.app.ui.navigation.Tracker
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
    fun `initial route is Tracker`() = runTest {
        val viewModel = MainViewModel()
        assertEquals(Tracker, viewModel.currentRoute.first())
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
