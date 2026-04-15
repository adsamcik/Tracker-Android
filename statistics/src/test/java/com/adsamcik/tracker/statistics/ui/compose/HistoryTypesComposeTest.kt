package com.adsamcik.tracker.statistics.ui.compose

import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import com.adsamcik.tracker.statistics.viewmodel.ExplorationStats
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.TimelineState
import com.adsamcik.tracker.statistics.viewmodel.TimelineEntry
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.statistics.viewmodel.activityIcon
import com.adsamcik.tracker.statistics.viewmodel.formatDistanceLabel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Train
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Unit tests for HistoryTypes.kt data classes, enums, and helper functions.
 */
class HistoryTypesComposeTest {

	// ─── activityLabel ───────────────────────────────────────────────────

	@Test
	fun `activityLabel returns Walk for 7`() {
		activityLabel(7) shouldBe "Walk"
	}

	@Test
	fun `activityLabel returns Run for 8`() {
		activityLabel(8) shouldBe "Run"
	}

	@Test
	fun `activityLabel returns Cycle for 1`() {
		activityLabel(1) shouldBe "Cycle"
	}

	@Test
	fun `activityLabel returns Drive for 0`() {
		activityLabel(0) shouldBe "Drive"
	}

	@Test
	fun `activityLabel returns Trip for null`() {
		activityLabel(null) shouldBe "Trip"
	}

	@Test
	fun `activityLabel returns Trip for unknown value`() {
		activityLabel(999) shouldBe "Trip"
	}

	// ─── activityIcon ────────────────────────────────────────────────────

	@Test
	fun `activityIcon returns walk icon for 7`() {
		activityIcon(7) shouldBe Icons.AutoMirrored.Filled.DirectionsWalk
	}

	@Test
	fun `activityIcon returns run icon for 8`() {
		activityIcon(8) shouldBe Icons.AutoMirrored.Filled.DirectionsRun
	}

	@Test
	fun `activityIcon returns bike icon for 1`() {
		activityIcon(1) shouldBe Icons.AutoMirrored.Filled.DirectionsBike
	}

	@Test
	fun `activityIcon returns car icon for 0`() {
		activityIcon(0) shouldBe Icons.Filled.DirectionsCar
	}

	@Test
	fun `activityIcon returns train icon for 4`() {
		activityIcon(4) shouldBe Icons.Filled.Train
	}

	@Test
	fun `activityIcon returns question mark for null`() {
		activityIcon(null) shouldBe Icons.Filled.QuestionMark
	}

	// ─── formatDistanceLabel ─────────────────────────────────────────────

	@Test
	fun `formatDistanceLabel returns km for distances over 1000m`() {
		val result = formatDistanceLabel(2500f)
		assert(result.contains("km")) { "Expected km label but got: $result" }
		assert(result.contains("2.5")) { "Expected 2.5 but got: $result" }
	}

	@Test
	fun `formatDistanceLabel returns m for distances under 1000m`() {
		val result = formatDistanceLabel(500f)
		assert(result.contains("m")) { "Expected m label but got: $result" }
		assert(result.contains("500")) { "Expected 500 but got: $result" }
	}

	@Test
	fun `formatDistanceLabel returns 0 m for zero`() {
		val result = formatDistanceLabel(0f)
		assert(result.contains("0")) { "Expected 0 but got: $result" }
	}

	@Test
	fun `formatDistanceLabel returns km at boundary 1000m`() {
		val result = formatDistanceLabel(1000f)
		assert(result.contains("km")) { "Expected km at 1000m but got: $result" }
	}

	// ─── HistoryTab enum ─────────────────────────────────────────────────

	@Test
	fun `HistoryTab has three values`() {
		HistoryTab.entries.size shouldBe 3
	}

	@Test
	fun `HistoryTab values are correct`() {
		HistoryTab.entries shouldBe listOf(
			HistoryTab.TIMELINE,
			HistoryTab.TRIPS,
			HistoryTab.CALENDAR,
		)
	}

	// ─── TimelineEntry IDs ───────────────────────────────────────────────

	@Test
	fun `TripEntry id is prefixed with trip_`() {
		val entry = TimelineEntry.TripEntry(
			tripId = 42L,
			timestampMs = 0L,
			title = "",
			subtitle = "",
			timeLabel = "",
			modeIcon = Icons.AutoMirrored.Filled.DirectionsWalk,
		)
		entry.id shouldBe "trip_42"
	}

	@Test
	fun `DaySummaryEntry id is prefixed with day_`() {
		val entry = TimelineEntry.DaySummaryEntry(
			epochDay = 19000L,
			timestampMs = 0L,
			dateLabel = "",
			distanceLabel = "",
			stepsLabel = "",
			tripsLabel = "",
		)
		entry.id shouldBe "day_19000"
	}

	@Test
	fun `DiscoveryEntry uses provided id`() {
		val entry = TimelineEntry.DiscoveryEntry(
			id = "custom_id",
			timestampMs = 0L,
			title = "",
			subtitle = "",
		)
		entry.id shouldBe "custom_id"
	}

	// ─── CalendarState defaults ──────────────────────────────────────────

	@Test
	fun `CalendarState defaults have expected values`() {
		val state = CalendarState()
		state.dayData shouldBe emptyMap()
		state.selectedDay shouldBe null
		state.selectedDayDetail shouldBe null
	}

	// ─── ExplorationStats defaults ───────────────────────────────────────

	@Test
	fun `ExplorationStats defaults to zero`() {
		val stats = ExplorationStats()
		stats.totalCells shouldBe 0
		stats.currentStreak shouldBe 0
		stats.bestStreak shouldBe 0
	}

	// ─── CalendarDayData ─────────────────────────────────────────────────

	@Test
	fun `CalendarDayData holds correct values`() {
		val date = LocalDate.of(2024, 6, 15)
		val data = CalendarDayData(date = date, intensity = 0.75f, tripCount = 3)
		data.date shouldBe date
		data.intensity shouldBe 0.75f
		data.tripCount shouldBe 3
	}
}
