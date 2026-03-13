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
			mockPreferences.fetchBooleanRes(
				R.string.settings_location_enabled_key,
				R.string.settings_location_enabled_default,
			)
		} returns location

		coEvery {
			mockPreferences.fetchBooleanRes(
				R.string.settings_cell_enabled_key,
				R.string.settings_cell_enabled_default,
			)
		} returns cell

		coEvery {
			mockPreferences.fetchBooleanRes(
				R.string.settings_wifi_location_count_enabled_key,
				R.string.settings_wifi_location_count_enabled_default,
			)
		} returns wifiCount

		coEvery {
			mockPreferences.fetchBooleanRes(
				R.string.settings_wifi_network_enabled_key,
				R.string.settings_wifi_network_enabled_default,
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
