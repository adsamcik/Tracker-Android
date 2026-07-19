package com.adsamcik.tracker.shared.preferences

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesAssistTest {

	private fun mockRepo(
		location: Boolean = false,
		cell: Boolean = false,
		wifi: Boolean = false,
		activity: Boolean = false,
		steps: Boolean = false,
		barometer: Boolean = false,
	): TrackingParamsRepository = mockk {
		every { data } returns flowOf(
			TrackingParamsState(
				locationEnabled = location,
					cellEnabled = cell,
					wifiEnabled = wifi,
					activityEnabled = activity,
					stepsEnabled = steps,
					barometerEnabled = barometer,
			)
		)
	}

	@Nested
	inner class HasAnythingToTrack {

		@Test
		fun `returns false when all tracking disabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo()) shouldBe false
		}

		@Test
		fun `returns true when only location enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(location = true)) shouldBe true
		}

		@Test
		fun `returns true when only cell enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(cell = true)) shouldBe true
		}

		@Test
		fun `returns true when only wifi scanning enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(wifi = true)) shouldBe true
		}

		@Test
		fun `returns true when only barometer enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(barometer = true)) shouldBe true
		}

		@Test
		fun `returns true when only activity enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(activity = true)) shouldBe true
		}

		@Test
		fun `returns true when only steps enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(steps = true)) shouldBe true
		}

		@Test
		fun `returns true when all tracking enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(
				mockRepo(location = true, cell = true, wifi = true, barometer = true)
			) shouldBe true
		}

		@Test
		fun `returns true when location and cell enabled`() = runTest {
			PreferencesAssist.hasAnythingToTrack(mockRepo(location = true, cell = true)) shouldBe true
		}

	}
}
