package com.adsamcik.tracker.app.onboarding.ui

import com.adsamcik.tracker.app.onboarding.data.SetupStep
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private const val AUTO_TRACKING_MODE_DISABLED = 0
private const val AUTO_TRACKING_MODE_ON_FOOT = 1
private const val AUTO_TRACKING_MODE_IN_MOTION = 2

@DisplayName("SetupUiState")
class SetupUiStateTest {

    @Nested
    @DisplayName("Progress calculation")
    inner class Progress {

        @Test
        fun `Welcome step is 1 of 5`() {
            val state = SetupUiState(currentStep = SetupStep.Welcome)
            assertEquals(1f / 5f, state.progress)
        }

        @Test
        fun `HowToTrack step is 2 of 5`() {
            val state = SetupUiState(currentStep = SetupStep.HowToTrack)
            assertEquals(2f / 5f, state.progress)
        }

        @Test
        fun `WhatToCollect step is 3 of 5`() {
            val state = SetupUiState(currentStep = SetupStep.WhatToCollect)
            assertEquals(3f / 5f, state.progress)
        }

        @Test
        fun `BackgroundAccess step is 4 of 5`() {
            val state = SetupUiState(currentStep = SetupStep.BackgroundAccess)
            assertEquals(4f / 5f, state.progress)
        }

        @Test
        fun `OnlineMapTiles step is 5 of 5`() {
            val state = SetupUiState(currentStep = SetupStep.OnlineMapTiles)
            assertEquals(1f, state.progress)
        }
    }

    @Nested
    @DisplayName("Permission requirements")
    inner class PermissionRequirements {

        @Test
        fun `location permission needed when location enabled`() {
            val state = SetupUiState(locationEnabled = true)
            assertTrue(state.needsLocationPermission)
        }

        @Test
        fun `location permission not needed when location disabled`() {
            val state = SetupUiState(locationEnabled = false)
            assertFalse(state.needsLocationPermission)
        }

        @Test
        fun `activity permission needed when auto-tracking on foot`() {
            val state = SetupUiState(autoTrackingMode = AUTO_TRACKING_MODE_ON_FOOT, activityEnabled = false)
            assertTrue(state.needsActivityPermission)
        }

        @Test
        fun `activity permission needed when activity tracking enabled`() {
            val state = SetupUiState(autoTrackingMode = AUTO_TRACKING_MODE_DISABLED, activityEnabled = true)
            assertTrue(state.needsActivityPermission)
        }

        @Test
		fun `activity permission not needed when both disabled`() {
			val state = SetupUiState(
				autoTrackingMode = AUTO_TRACKING_MODE_DISABLED,
				activityEnabled = false,
				stepsEnabled = false,
			)
			assertFalse(state.needsActivityPermission)
		}

		@Test
		fun `activity permission needed when step tracking enabled`() {
			val state = SetupUiState(
				autoTrackingMode = AUTO_TRACKING_MODE_DISABLED,
				activityEnabled = false,
				stepsEnabled = true,
			)
			assertTrue(state.needsActivityPermission)
		}

        @Test
        fun `background location needed when in-motion auto-tracking enabled and location on`() {
            val state = SetupUiState(autoTrackingMode = AUTO_TRACKING_MODE_IN_MOTION, locationEnabled = true)
            assertTrue(state.needsBackgroundLocationPermission)
        }

        @Test
        fun `background location not needed when auto-tracking disabled`() {
            val state = SetupUiState(autoTrackingMode = AUTO_TRACKING_MODE_DISABLED, locationEnabled = true)
            assertFalse(state.needsBackgroundLocationPermission)
        }

        @Test
        fun `background location not needed when location disabled`() {
            val state = SetupUiState(autoTrackingMode = AUTO_TRACKING_MODE_IN_MOTION, locationEnabled = false)
            assertFalse(state.needsBackgroundLocationPermission)
        }

        @Test
        fun `wifi permission needed when wifi enabled but not granted`() {
            val state = SetupUiState(wifiEnabled = true)
            assertTrue(state.needsWifiPermission)
        }

        @Test
        fun `wifi permission not needed when wifi disabled`() {
            val state = SetupUiState(wifiEnabled = false)
            assertFalse(state.needsWifiPermission)
        }

        @Test
        fun `cell permission needed when cell enabled but not granted`() {
            val state = SetupUiState(cellEnabled = true, cellPermissionGranted = false)
            assertTrue(state.needsCellPermission)
        }

        @Test
        fun `cell permission not needed once granted`() {
            val state = SetupUiState(cellEnabled = true, cellPermissionGranted = true)
            assertFalse(state.needsCellPermission)
        }
    }

    @Nested
    @DisplayName("Default state")
    inner class Defaults {

        @Test
        fun `default starts on Welcome`() {
            assertEquals(SetupStep.Welcome, SetupUiState().currentStep)
        }

        @Test
        fun `default auto-tracking is on foot`() {
            assertEquals(AUTO_TRACKING_MODE_ON_FOOT, SetupUiState().autoTrackingMode)
        }

        @Test
        fun `default preset is balanced for everyday use`() {
            assertEquals(TrackingPolicyPreset.BALANCED, SetupUiState().trackingPreset)
        }

        @Test
        fun `default location precision is precise`() {
            assertEquals(LocationPrecisionMode.PRECISE, SetupUiState().locationPrecision)
        }

        @Test
        fun `default has location enabled`() {
            assertTrue(SetupUiState().locationEnabled)
        }

        @Test
        fun `default has wifi disabled`() {
            assertFalse(SetupUiState().wifiEnabled)
        }

        @Test
        fun `default has cell disabled`() {
            assertFalse(SetupUiState().cellEnabled)
        }

        @Test
        fun `default has online map tiles enabled`() {
            assertTrue(SetupUiState().onlineMapTilesEnabled)
        }
    }
}

@DisplayName("SetupStep")
class SetupStepTest {

    @Test
    fun `totalSteps is 5`() {
        assertEquals(5, SetupStep.totalSteps)
    }

    @Test
    fun `fromIndex returns correct step`() {
        assertEquals(SetupStep.Welcome, SetupStep.fromIndex(0))
        assertEquals(SetupStep.HowToTrack, SetupStep.fromIndex(1))
        assertEquals(SetupStep.WhatToCollect, SetupStep.fromIndex(2))
        assertEquals(SetupStep.BackgroundAccess, SetupStep.fromIndex(3))
        assertEquals(SetupStep.OnlineMapTiles, SetupStep.fromIndex(4))
    }

    @Test
    fun `fromIndex returns null for out of bounds`() {
        assertEquals(null, SetupStep.fromIndex(-1))
        assertEquals(null, SetupStep.fromIndex(5))
    }

    @Test
    fun `step indices are sequential`() {
        assertEquals(0, SetupStep.Welcome.index)
        assertEquals(1, SetupStep.HowToTrack.index)
        assertEquals(2, SetupStep.WhatToCollect.index)
        assertEquals(3, SetupStep.BackgroundAccess.index)
        assertEquals(4, SetupStep.OnlineMapTiles.index)
    }
}
