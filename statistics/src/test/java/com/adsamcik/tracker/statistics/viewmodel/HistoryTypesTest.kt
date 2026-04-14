package com.adsamcik.tracker.statistics.viewmodel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

@DisplayName("HistoryTypes")
class HistoryTypesTest {

	@Nested
	@DisplayName("HistoryTab")
	inner class HistoryTabTests {

		@Test
		fun `has all expected values`() {
			HistoryTab.entries.map { it.name } shouldBe listOf("TIMELINE", "TRIPS", "CALENDAR")
		}
	}

	@Nested
	@DisplayName("TimelineEntry")
	inner class TimelineEntryTests {

		@Test
		fun `TripEntry id is prefixed with trip_`() {
			val entry = TimelineEntry.TripEntry(
				tripId = 42,
				timestampMs = 1000L,
				title = "Morning Run",
				subtitle = "5 km",
				timeLabel = "08:00",
				modeIcon = activityIcon(7),
			)
			entry.id shouldBe "trip_42"
		}

		@Test
		fun `TripEntry default isDistancePlausible is true`() {
			val entry = TimelineEntry.TripEntry(
				tripId = 1,
				timestampMs = 1000L,
				title = "t",
				subtitle = "s",
				timeLabel = "l",
				modeIcon = activityIcon(null),
			)
			entry.isDistancePlausible shouldBe true
		}

		@Test
		fun `DiscoveryEntry stores all fields`() {
			val entry = TimelineEntry.DiscoveryEntry(
				id = "discovery_1",
				timestampMs = 2000L,
				title = "New area",
				subtitle = "3 cells",
			)
			entry.id shouldBe "discovery_1"
			entry.timestampMs shouldBe 2000L
			entry.title shouldBe "New area"
			entry.subtitle shouldBe "3 cells"
		}

		@Test
		fun `DaySummaryEntry id is prefixed with day_`() {
			val entry = TimelineEntry.DaySummaryEntry(
				epochDay = 19800L,
				timestampMs = 3000L,
				dateLabel = "Mon, Jan 1",
				distanceLabel = "5.0 km",
				stepsLabel = "8000",
				tripsLabel = "3 trips",
			)
			entry.id shouldBe "day_19800"
		}
	}

	@Nested
	@DisplayName("TimelineState")
	inner class TimelineStateTests {

		@Test
		fun `Loading is singleton`() {
			TimelineState.Loading.shouldBeInstanceOf<TimelineState.Loading>()
		}

		@Test
		fun `Empty is singleton`() {
			TimelineState.Empty.shouldBeInstanceOf<TimelineState.Empty>()
		}

		@Test
		fun `Content stores entries`() {
			val entry = TimelineEntry.DiscoveryEntry("d1", 100L, "title", "sub")
			val state = TimelineState.Content(entries = listOf(entry))
			state.entries.size shouldBe 1
			state.entries[0] shouldBe entry
		}
	}

	@Nested
	@DisplayName("CalendarDayData")
	inner class CalendarDayDataTests {

		@Test
		fun `stores all fields`() {
			val date = LocalDate.of(2024, 6, 15)
			val data = CalendarDayData(date = date, intensity = 0.75f, tripCount = 3)
			data.date shouldBe date
			data.intensity shouldBe 0.75f
			data.tripCount shouldBe 3
		}

		@Test
		fun `equality works`() {
			val date = LocalDate.of(2024, 1, 1)
			val d1 = CalendarDayData(date, 0.5f, 1)
			val d2 = CalendarDayData(date, 0.5f, 1)
			d1 shouldBe d2
		}
	}

	@Nested
	@DisplayName("CalendarState")
	inner class CalendarStateTests {

		@Test
		fun `default values`() {
			val state = CalendarState()
			state.dayData shouldBe emptyMap()
			state.selectedDay shouldBe null
			state.selectedDayDetail shouldBe null
		}

		@Test
		fun `DayDetail stores all fields`() {
			val detail = CalendarState.DayDetail(
				totalDistanceM = 5000f,
				totalSteps = 10000,
				tripCount = 2,
				trips = emptyList(),
			)
			detail.totalDistanceM shouldBe 5000f
			detail.totalSteps shouldBe 10000
			detail.tripCount shouldBe 2
		}
	}

	@Nested
	@DisplayName("ExplorationStats")
	inner class ExplorationStatsTests {

		@Test
		fun `default values are zero`() {
			val stats = ExplorationStats()
			stats.totalCells shouldBe 0
			stats.currentStreak shouldBe 0
			stats.bestStreak shouldBe 0
		}

		@Test
		fun `stores all fields`() {
			val stats = ExplorationStats(totalCells = 100, currentStreak = 5, bestStreak = 10)
			stats.totalCells shouldBe 100
			stats.currentStreak shouldBe 5
			stats.bestStreak shouldBe 10
		}
	}

	@Nested
	@DisplayName("activityLabel")
	inner class ActivityLabelTests {

		@Test
		fun `walking returns Walk`() {
			activityLabel(7) shouldBe "Walk"
		}

		@Test
		fun `running returns Run`() {
			activityLabel(8) shouldBe "Run"
		}

		@Test
		fun `cycling returns Cycle`() {
			activityLabel(1) shouldBe "Cycle"
		}

		@Test
		fun `driving returns Drive`() {
			activityLabel(0) shouldBe "Drive"
		}

		@Test
		fun `null returns Trip`() {
			activityLabel(null) shouldBe "Trip"
		}

		@Test
		fun `unknown value returns Trip`() {
			activityLabel(99) shouldBe "Trip"
		}

		@Test
		fun `negative value returns Trip`() {
			activityLabel(-1) shouldBe "Trip"
		}
	}

	@Nested
	@DisplayName("formatDistanceLabel")
	inner class FormatDistanceLabelTests {

		@Test
		fun `meters below 1000 formatted as meters`() {
			val result = formatDistanceLabel(500f)
			result shouldBe "500 m"
		}

		@Test
		fun `exactly 1000 meters formatted as km`() {
			val result = formatDistanceLabel(1000f)
			result shouldBe "1.0 km"
		}

		@Test
		fun `above 1000 meters formatted as km`() {
			val result = formatDistanceLabel(2500f)
			result shouldBe "2.5 km"
		}

		@Test
		fun `zero meters`() {
			val result = formatDistanceLabel(0f)
			result shouldBe "0 m"
		}

		@Test
		fun `large distance`() {
			val result = formatDistanceLabel(42195f)
			result shouldBe "42.2 km"
		}
	}

	@Nested
	@DisplayName("activityIcon")
	inner class ActivityIconTests {

		@Test
		fun `returns non-null icon for all known types`() {
			// Should not throw for any known activity type
			activityIcon(7) shouldNotBe null
			activityIcon(8) shouldNotBe null
			activityIcon(1) shouldNotBe null
			activityIcon(0) shouldNotBe null
			activityIcon(4) shouldNotBe null
			activityIcon(null) shouldNotBe null
			activityIcon(99) shouldNotBe null
		}
	}
}
