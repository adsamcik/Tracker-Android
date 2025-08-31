package com.adsamcik.tracker.app.onboarding

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.onboarding.data.OnboardingState
import com.adsamcik.tracker.app.onboarding.data.OnboardingStep
import com.adsamcik.tracker.app.onboarding.data.Permission
import com.adsamcik.tracker.app.onboarding.data.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests asserting onboarding step gating for Background Location
 * with/without the OS background permission present.
 *
 * Contract:
 * - BackgroundLocation step is part of the flow when auto-tracking is enabled
 *   (via mode index > 0 or legacy enableAutomaticTracking), regardless of whether
 *   background permission is already granted.
 * - When auto-tracking is disabled (mode index == 0 and legacy disabled), the step
 *   is not shown even if the permission is already granted.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingGatingInstrumentedTest {

    @Test
    fun backgroundLocationShown_whenAutoTrackingEnabled_withoutBackgroundPermission() {
        val prefs = UserPreferences(
            enableLocationTracking = false,
            enableActivityTracking = true,
            enableAutomaticTracking = false,
            autoTrackingModeIndex = 1 // enabled via new selector
        )
        val state = OnboardingState(
            currentStep = OnboardingStep.ActivitySetup,
            userPreferences = prefs,
            grantedPermissions = emptySet()
        )

        // should be part of the flow
        assertTrue(state.shouldShowStep(OnboardingStep.BackgroundLocation))
        // next step from ActivitySetup should be BackgroundLocation
        assertEquals(OnboardingStep.BackgroundLocation, state.getNextStep())
    }

    @Test
    fun backgroundLocationShown_whenAutoTrackingEnabled_withBackgroundPermission() {
        val prefs = UserPreferences(
            enableLocationTracking = false,
            enableActivityTracking = true,
            enableAutomaticTracking = false,
            autoTrackingModeIndex = 2 // enabled via new selector
        )
        val state = OnboardingState(
            currentStep = OnboardingStep.ActivitySetup,
            userPreferences = prefs,
            grantedPermissions = setOf(Permission.LOCATION_FOREGROUND, Permission.LOCATION_BACKGROUND)
        )

        // still part of the flow even if already granted
        assertTrue(state.shouldShowStep(OnboardingStep.BackgroundLocation))
        // next step from ActivitySetup should be BackgroundLocation
        assertEquals(OnboardingStep.BackgroundLocation, state.getNextStep())
    }

    @Test
    fun backgroundLocationHidden_whenAutoTrackingDisabled_evenIfBackgroundPermissionGranted() {
        val prefs = UserPreferences(
            enableLocationTracking = false,
            enableActivityTracking = true,
            enableAutomaticTracking = false,
            autoTrackingModeIndex = 0 // disabled
        )
        val state = OnboardingState(
            currentStep = OnboardingStep.ActivitySetup,
            userPreferences = prefs,
            grantedPermissions = setOf(Permission.LOCATION_FOREGROUND, Permission.LOCATION_BACKGROUND)
        )

        // step should not be shown when auto-tracking is off
        assertFalse(state.shouldShowStep(OnboardingStep.BackgroundLocation))
        // next step should not route to BackgroundLocation; since wifi is off, expect Success
        assertEquals(OnboardingStep.Success, state.getNextStep())
    }

    @Test
    fun backgroundLocationShown_whenLegacyAutomaticTrackingEnabled() {
        val prefs = UserPreferences(
            enableLocationTracking = false,
            enableActivityTracking = true,
            enableAutomaticTracking = true, // legacy path
            autoTrackingModeIndex = 0 // new selector disabled, legacy takes effect
        )
        val state = OnboardingState(
            currentStep = OnboardingStep.ActivitySetup,
            userPreferences = prefs,
            grantedPermissions = emptySet()
        )

        assertTrue(state.shouldShowStep(OnboardingStep.BackgroundLocation))
        assertEquals(OnboardingStep.BackgroundLocation, state.getNextStep())
    }
}
