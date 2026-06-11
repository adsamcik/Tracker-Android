package com.adsamcik.tracker.tracker.component

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.data.collection.CellScanData
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackerComponentRequirement")
class TrackerComponentRequirementTest {

	companion object {
		private const val TIME_MS = 1_700_000_000_000L
		private const val ELAPSED_NANOS = 5_000_000_000L
	}

	private fun emptyCycle() = TrackingCycle(
		timestampMs = TIME_MS,
		elapsedRealtimeNanos = ELAPSED_NANOS,
	)

	private fun fullCycle() = TrackingCycle(
		timestampMs = TIME_MS,
		elapsedRealtimeNanos = ELAPSED_NANOS,
		activity = ActivityInfo(activityType = 7, confidence = 80),
		location = mockk<LocationData>(relaxed = true),
		cellScan = mockk<CellScanData>(relaxed = true),
		wifiScan = mockk<WifiScanData>(relaxed = true),
		stepDelta = 10,
	)

	@Nested
	@DisplayName("Enum completeness")
	inner class Completeness {

		@Test
		fun `has exactly 5 requirements`() {
			TrackerComponentRequirement.entries.size shouldBe 5
		}

		@Test
		fun `all requirements are fulfilled when cycle has all data`() {
			TrackerComponentRequirement.entries.forEach { req ->
				req.isRequirementFulfilled(fullCycle()) shouldBe true
			}
		}

		@Test
		fun `no requirements are fulfilled on empty cycle`() {
			TrackerComponentRequirement.entries.forEach { req ->
				req.isRequirementFulfilled(emptyCycle()) shouldBe false
			}
		}
	}

	@Nested
	@DisplayName("Individual requirements")
	inner class Individual {

		@Test
		fun `WIFI requires wifiScan`() {
			val withWifi = emptyCycle().copy(wifiScan = mockk(relaxed = true))
			TrackerComponentRequirement.WIFI.isRequirementFulfilled(withWifi) shouldBe true
			TrackerComponentRequirement.WIFI.isRequirementFulfilled(emptyCycle()) shouldBe false
		}

		@Test
		fun `CELL requires cellScan`() {
			val withCell = emptyCycle().copy(cellScan = mockk(relaxed = true))
			TrackerComponentRequirement.CELL.isRequirementFulfilled(withCell) shouldBe true
			TrackerComponentRequirement.CELL.isRequirementFulfilled(emptyCycle()) shouldBe false
		}

		@Test
		fun `LOCATION requires location`() {
			val withLoc = emptyCycle().copy(location = mockk(relaxed = true))
			TrackerComponentRequirement.LOCATION.isRequirementFulfilled(withLoc) shouldBe true
			TrackerComponentRequirement.LOCATION.isRequirementFulfilled(emptyCycle()) shouldBe false
		}

		@Test
		fun `STEP requires stepDelta`() {
			val withSteps = emptyCycle().copy(stepDelta = 5)
			TrackerComponentRequirement.STEP.isRequirementFulfilled(withSteps) shouldBe true
			TrackerComponentRequirement.STEP.isRequirementFulfilled(emptyCycle()) shouldBe false
		}

		@Test
		fun `ACTIVITY requires activity`() {
			val withActivity = emptyCycle().copy(activity = ActivityInfo(7, 80))
			TrackerComponentRequirement.ACTIVITY.isRequirementFulfilled(withActivity) shouldBe true
			TrackerComponentRequirement.ACTIVITY.isRequirementFulfilled(emptyCycle()) shouldBe false
		}
	}
}
