package com.adsamcik.tracker.app.ui

import com.adsamcik.tracker.app.ui.navigation.Debug
import com.adsamcik.tracker.app.ui.navigation.Game
import com.adsamcik.tracker.app.ui.navigation.Map
import com.adsamcik.tracker.app.ui.navigation.Settings
import com.adsamcik.tracker.app.ui.navigation.Stats
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MainRootViewModel")
class MainRootViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = MainRootViewModel()

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `map is not expanded by default`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.isMapExpanded.value shouldBe false
        }

        @Test
        fun `current primary route defaults to Stats`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.currentPrimaryRoute.value shouldBe Stats
        }

        @Test
        fun `last non-map route defaults to Stats`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.lastNonMapRoute.value shouldBe Stats
        }
    }

    @Nested
    @DisplayName("getEffectiveRoute")
    inner class GetEffectiveRoute {

        @Test
        fun `returns Map when expanded`() {
            val vm = createViewModel()
            vm.getEffectiveRoute(isExpanded = true, primaryRoute = Stats) shouldBe Map
        }

        @Test
        fun `returns primary route when not expanded`() {
            val vm = createViewModel()
            vm.getEffectiveRoute(isExpanded = false, primaryRoute = Game) shouldBe Game
        }

        @Test
        fun `returns Map regardless of primary route when expanded`() {
            val vm = createViewModel()
            vm.getEffectiveRoute(isExpanded = true, primaryRoute = Game) shouldBe Map
        }
    }

    @Nested
    @DisplayName("shouldHandleBack")
    inner class ShouldHandleBack {

        @Test
        fun `returns true when map is expanded`() {
            val vm = createViewModel()
            vm.shouldHandleBack(isExpanded = true, currentRoute = Stats) shouldBe true
        }

        @Test
        fun `returns true when on non-Stats route`() {
            val vm = createViewModel()
            vm.shouldHandleBack(isExpanded = false, currentRoute = Game) shouldBe true
        }

        @Test
        fun `returns false when collapsed and on Stats`() {
            val vm = createViewModel()
            vm.shouldHandleBack(isExpanded = false, currentRoute = Stats) shouldBe false
        }
    }

    @Nested
    @DisplayName("handleBack")
    inner class HandleBack {

        @Test
        fun `collapses map when expanded`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.setMapExpanded(true)
            advanceUntilIdle()

            val handled = vm.handleBack()
            handled shouldBe true
            vm.isMapExpanded.value shouldBe false
        }

        @Test
        fun `navigates to Stats when on other route`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.setCurrentRoute(Game)
            advanceUntilIdle()

            val handled = vm.handleBack()
            handled shouldBe true
            vm.currentPrimaryRoute.value shouldBe Stats
        }

        @Test
        fun `returns false when already on Stats and collapsed`() = runTest(testDispatcher) {
            val vm = createViewModel()
            val handled = vm.handleBack()
            handled shouldBe false
        }

        @Test
        fun `prioritizes collapsing map over navigating to Stats`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.setMapExpanded(true)
            vm.setCurrentRoute(Game)
            advanceUntilIdle()

            val handled = vm.handleBack()
            handled shouldBe true
            vm.isMapExpanded.value shouldBe false
            // Route should still be Game (map collapse handled first)
            vm.currentPrimaryRoute.value shouldBe Game
        }
    }

    @Nested
    @DisplayName("setCurrentRoute")
    inner class SetCurrentRoute {

        @Test
        fun `updates primary route`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.setCurrentRoute(Game)
            advanceUntilIdle()
            vm.currentPrimaryRoute.value shouldBe Game
        }

        @Test
        fun `updates lastNonMapRoute for non-Map routes`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.setCurrentRoute(Game)
            advanceUntilIdle()
            vm.lastNonMapRoute.value shouldBe Game
        }

        @Test
        fun `does not update lastNonMapRoute when setting Map`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.setCurrentRoute(Game)
            advanceUntilIdle()
            vm.setCurrentRoute(Map)
            advanceUntilIdle()
            vm.lastNonMapRoute.value shouldBe Game
        }
    }

    @Nested
    @DisplayName("Map expansion")
    inner class MapExpansion {

        @Test
        fun `toggleMapExpanded flips state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.isMapExpanded.value shouldBe false

            vm.toggleMapExpanded()
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe true

            vm.toggleMapExpanded()
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe false
        }

        @Test
        fun `expandMap sets true`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.expandMap()
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe true
        }

        @Test
        fun `collapseMap sets false`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.expandMap()
            advanceUntilIdle()
            vm.collapseMap()
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe false
        }
    }

    @Nested
    @DisplayName("initializeWithDestination")
    inner class InitializeWithDestination {

        @Test
        fun `Map destination expands map and sets Stats as primary`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.initializeWithDestination(Map)
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe true
            vm.currentPrimaryRoute.value shouldBe Stats
            vm.lastNonMapRoute.value shouldBe Stats
        }

        @Test
        fun `Game destination keeps map collapsed`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.initializeWithDestination(Game)
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe false
            vm.currentPrimaryRoute.value shouldBe Game
            vm.lastNonMapRoute.value shouldBe Game
        }

        @Test
        fun `Debug destination keeps map collapsed`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.initializeWithDestination(Debug)
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe false
            vm.currentPrimaryRoute.value shouldBe Debug
        }

        @Test
        fun `Settings destination keeps map collapsed`() = runTest(testDispatcher) {
            val vm = createViewModel()
            val settings = Settings()
            vm.initializeWithDestination(settings)
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe false
            vm.currentPrimaryRoute.value shouldBe settings
        }

        @Test
        fun `unknown destination defaults to Stats collapsed`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.initializeWithDestination("unknown")
            advanceUntilIdle()
            vm.isMapExpanded.value shouldBe false
            vm.currentPrimaryRoute.value shouldBe Stats
        }
    }
}
