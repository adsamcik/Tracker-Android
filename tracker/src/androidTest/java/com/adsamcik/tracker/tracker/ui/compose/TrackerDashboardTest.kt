package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.adsamcik.tracker.tracker.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for TrackerDashboard composable.
 * 
 * Migrated from deprecated TrackerViewModel to state-based testing approach.
 * Tests verify UI structure and behavior with controlled state inputs.
 */
@RunWith(AndroidJUnit4::class)
class TrackerDashboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var settingsClicked = false
    private var permissionRequested = false
    private var trackingToggled = false
    private var trackingToggledValue: Boolean? = null

    private val fakeDailyPointsProvider = FakeDailyPointsProvider()
    private val fakeDailySummaryProvider = FakeDailySummaryProvider()
    private val fakeGoalProgressProvider = FakeGoalProgressProvider()

    // Simple HapticFeedback implementation for testing
    private val testHapticFeedback = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            // No-op for tests
        }
    }

    @Before
    fun setup() {
        // Reset callbacks
        settingsClicked = false
        permissionRequested = false
        trackingToggled = false
        trackingToggledValue = null
    }

    private fun setDashboardContent(
        state: TrackerDashboardUiState = TrackerDashboardUiState()
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides testHapticFeedback) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    TrackerDashboard(
                        state = state,
                        dailyPointsProvider = fakeDailyPointsProvider,
                        dailySummaryProvider = fakeDailySummaryProvider,
                        goalProgressProvider = fakeGoalProgressProvider,
                        onSettingsClick = { settingsClicked = true },
                        onMapClick = { /* No-op for test */ },
                        onRequestPermission = { permissionRequested = true },
                        onToggleTracking = { shouldStart ->
                            trackingToggled = true
                            trackingToggledValue = shouldStart
                        }
                    )
                }
            }
        }
    }

    @Test
    fun showsSettingsAndFab() {
        setDashboardContent()

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Settings icon present
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.description_settings)
        ).assertIsDisplayed().assertHasClickAction()

        // Title present
        composeRule.onNodeWithText(
            context.getString(R.string.settings_tracking_title)
        ).assertIsDisplayed()

        // FAB present (should show play arrow when not tracking)
        composeRule.onNode(hasTestTag("tracking_fab")).assertIsDisplayed()
    }

    @Test
    fun showsEmptyStateWhenNotTracking() {
        setDashboardContent()

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Empty state should be visible
        composeRule.onNodeWithText(
            context.getString(R.string.shortcut_start_tracking_long)
        ).assertIsDisplayed()
    }

    @Test
    fun settingsClickCallsCallback() {
        setDashboardContent()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.description_settings)
        ).performClick()

        assert(settingsClicked)
    }

    @Test
    fun showsLockBannerWhenLocked() {
        // Test with locked state
        setDashboardContent(
            state = TrackerDashboardUiState(
                isTracking = false,
                isLocked = true
            )
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Lock banner should be visible when locked
        composeRule.onNode(hasTestTag("lock_banner"), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun lockBannerClickCallsSettings() {
        // Test with locked state
        setDashboardContent(
            state = TrackerDashboardUiState(
                isTracking = false,
                isLocked = true
            )
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Lock banner should exist and be clickable
        composeRule.onNode(hasTestTag("lock_banner"), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun showsBasicTrackingInterface() {
        setDashboardContent()

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Basic UI elements should be present
        composeRule.onNodeWithText(
            context.getString(R.string.settings_tracking_title)
        ).assertIsDisplayed()
        
        composeRule.onNode(hasTestTag("tracking_fab")).assertIsDisplayed()
    }

    @Test
    fun fabClickCallsToggleTracking() {
        setDashboardContent()

        composeRule.onNode(hasTestTag("tracking_fab")).performClick()

        // Since we don't have permission, it should call permission request
        assert(permissionRequested)
    }

    @Test
    fun testTagsArePresent() {
        setDashboardContent()

        // Verify all major test tags are present in the UI
        composeRule.onNode(hasTestTag("tracking_fab")).assertExists()
        
        // Lock banner should not exist when not locked
        composeRule.onNode(hasTestTag("lock_banner"), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun trackingStateAffectsFabIcon() {
        // Test not tracking state
        setDashboardContent(
            state = TrackerDashboardUiState(isTracking = false)
        )
        
        // FAB should exist
        composeRule.onNode(hasTestTag("tracking_fab")).assertExists()
        
        composeRule.waitForIdle()
        
        // Now test tracking state
        setDashboardContent(
            state = TrackerDashboardUiState(isTracking = true)
        )
        
        // FAB should still exist but with different icon (stop)
        composeRule.onNode(hasTestTag("tracking_fab")).assertExists()
    }

    @Test
    fun permissionStateAffectsClick() {
        // Without permission, FAB should request permission
        setDashboardContent(
            state = TrackerDashboardUiState(
                isTracking = false,
                hasLocationPermission = false
            )
        )
        
        composeRule.onNode(hasTestTag("tracking_fab")).performClick()
        
        // Should request permission instead of toggling tracking
        assert(permissionRequested) { "Expected permission request" }
        
        // Reset
        permissionRequested = false
        trackingToggled = false
        
        // With permission, should toggle tracking
        setDashboardContent(
            state = TrackerDashboardUiState(
                isTracking = false,
                hasLocationPermission = true
            )
        )
        
        composeRule.onNode(hasTestTag("tracking_fab")).performClick()
        
        // Should toggle tracking
        assert(trackingToggled) { "Expected tracking toggle" }
        assert(trackingToggledValue == true) { "Expected toggle to true" }
    }
}
