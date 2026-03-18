package com.adsamcik.tracker.shared.preferences

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesAssistTest {

	private val mockPreferences: Preferences = mockk()

	private fun stubTrackingFlags(
		location: Boolean = false,
		cell: Boolean = false,
		wifiCount: Boolean = false,
		wifiNetwork: Boolean = false,
	) {
		coEvery {
			mockPreferences.fetchBoolean(
				PreferenceKeys.LOCATION_ENABLED,
				PreferenceKeys.LOCATION_ENABLED_DEFAULT,
			)
		} returns location

		coEvery {
			mockPreferences.fetchBoolean(
				PreferenceKeys.CELL_ENABLED,
				PreferenceKeys.CELL_ENABLED_DEFAULT,
			)
		} returns cell

		coEvery {
			mockPreferences.fetchBoolean(
				PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED,
				PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT,
			)
		} returns wifiCount

		coEvery {
			mockPreferences.fetchBoolean(
				PreferenceKeys.WIFI_NETWORK_ENABLED,
				PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT,
			)
		} returns wifiNetwork
	}

	@Nested
	inner class HasAnythingToTrackAsync {

		@Test
		fun `returns false when all tracking disabled`() = runTest {
			stubTrackingFlags(
				location = false,
				cell = false,
				wifiCount = false,
				wifiNetwork = false,
			)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe false
		}

		@Test
		fun `returns true when only location enabled`() = runTest {
			stubTrackingFlags(location = true)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}

		@Test
		fun `returns true when only cell enabled`() = runTest {
			stubTrackingFlags(cell = true)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}

		@Test
		fun `returns true when only wifi count enabled`() = runTest {
			stubTrackingFlags(wifiCount = true)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}

		@Test
		fun `returns true when only wifi network enabled`() = runTest {
			stubTrackingFlags(wifiNetwork = true)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}

		@Test
		fun `returns true when all tracking enabled`() = runTest {
			stubTrackingFlags(
				location = true,
				cell = true,
				wifiCount = true,
				wifiNetwork = true,
			)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}

		@Test
		fun `returns true when location and cell enabled`() = runTest {
			stubTrackingFlags(location = true, cell = true)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}

		@Test
		fun `returns true when both wifi flags enabled`() = runTest {
			stubTrackingFlags(wifiCount = true, wifiNetwork = true)
			PreferencesAssist.hasAnythingToTrackAsync(mockPreferences) shouldBe true
		}
	}

}
