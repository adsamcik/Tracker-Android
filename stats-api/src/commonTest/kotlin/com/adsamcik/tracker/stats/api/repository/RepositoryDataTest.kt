package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RepositoryDataTest {

	// ── LiveStats ──────────────────────────────────────────────────────

	@Nested
	inner class LiveStatsDefaults {

		@Test
		fun `all defaults are zero or null`() {
			val stats = LiveStats()
			stats.sessionDistance shouldBe DistanceM.ZERO
			stats.sessionSteps shouldBe StepCount.ZERO
			stats.sessionDuration shouldBe DurationMs.ZERO
			stats.currentSpeed shouldBe SpeedMps.ZERO
			stats.avgSpeed shouldBe SpeedMps.ZERO
			stats.maxSpeed shouldBe SpeedMps.ZERO
			stats.dayTotalDistance shouldBe DistanceM.ZERO
			stats.dayTotalSteps shouldBe StepCount.ZERO
			stats.dominantActivity.shouldBeNull()
			stats.tripCount shouldBe 0
		}

		@Test
		fun `preserves custom values`() {
			val stats = LiveStats(
				sessionDistance = DistanceM(500f),
				sessionSteps = StepCount(700),
				currentSpeed = SpeedMps(1.5f),
				dominantActivity = DetectedActivityType.WALKING,
				tripCount = 2,
			)
			stats.sessionDistance.raw shouldBe 500f
			stats.sessionSteps.raw shouldBe 700
			stats.dominantActivity shouldBe DetectedActivityType.WALKING
			stats.tripCount shouldBe 2
		}

		@Test
		fun `copy updates only specified fields`() {
			val original = LiveStats(sessionDistance = DistanceM(100f), tripCount = 1)
			val updated = original.copy(tripCount = 2)
			updated.sessionDistance.raw shouldBe 100f
			updated.tripCount shouldBe 2
		}
	}

	// ── TripSummary ────────────────────────────────────────────────────

	@Nested
	inner class TripSummaryTest {

		@Test
		fun `preserves all fields`() {
			val trip = TripSummary(
				id = 1L,
				startTimeMs = EpochMs(1000L),
				endTimeMs = EpochMs(5000L),
				distance = DistanceM(800f),
				steps = StepCount(1200),
				duration = DurationMs(4000L),
				primaryMode = TransportMode.WALK,
				sampleCount = 40,
			)
			trip.id shouldBe 1L
			trip.startTimeMs.raw shouldBe 1000L
			trip.endTimeMs.raw shouldBe 5000L
			trip.distance.raw shouldBe 800f
			trip.primaryMode shouldBe TransportMode.WALK
			trip.sampleCount shouldBe 40
		}

		@Test
		fun `equality`() {
			val trip1 = TripSummary(1L, EpochMs(100L), EpochMs(200L), DistanceM(10f), StepCount(5), DurationMs(100L), TransportMode.RUN, 2)
			val trip2 = TripSummary(1L, EpochMs(100L), EpochMs(200L), DistanceM(10f), StepCount(5), DurationMs(100L), TransportMode.RUN, 2)
			trip1 shouldBe trip2
		}
	}

	// ── DailySummary ───────────────────────────────────────────────────

	@Nested
	inner class DailySummaryTest {

		@Test
		fun `preserves all fields`() {
			val summary = DailySummary(
				dayEpoch = 19700L,
				totalDistance = DistanceM(5000f),
				totalSteps = StepCount(6500),
				totalDuration = DurationMs(7200_000L),
				tripCount = 3,
				activeTrackingDuration = DurationMs(3600_000L),
			)
			summary.dayEpoch shouldBe 19700L
			summary.totalDistance.raw shouldBe 5000f
			summary.activeTrackingDuration.raw shouldBe 3600_000L
		}

		@Test
		fun `equality`() {
			val s1 = DailySummary(1L, DistanceM(10f), StepCount(10), DurationMs(10L), 1, DurationMs(5L))
			val s2 = DailySummary(1L, DistanceM(10f), StepCount(10), DurationMs(10L), 1, DurationMs(5L))
			s1 shouldBe s2
		}
	}

	// ── ExplorationStats ───────────────────────────────────────────────

	@Nested
	inner class ExplorationStatsTest {

		@Test
		fun `defaults are zero`() {
			val stats = ExplorationStats()
			stats.totalCells shouldBe 0
			stats.recentDiscoveries shouldBe 0
		}

		@Test
		fun `preserves custom values`() {
			val stats = ExplorationStats(totalCells = 150, recentDiscoveries = 5)
			stats.totalCells shouldBe 150
			stats.recentDiscoveries shouldBe 5
		}
	}

	// ── AchievementProgressData ────────────────────────────────────────

	@Nested
	inner class AchievementProgressDataTest {

		@Test
		fun `preserves all fields`() {
			val data = AchievementProgressData(
				achievementId = "explorer_cells",
				currentValue = 50L,
				targetValue = 100L,
				tier = "Bronze",
				isUnlocked = false,
			)
			data.achievementId shouldBe "explorer_cells"
			data.currentValue shouldBe 50L
			data.targetValue shouldBe 100L
			data.tier shouldBe "Bronze"
			data.isUnlocked shouldBe false
		}

		@Test
		fun `tier can be null for unstarted`() {
			val data = AchievementProgressData("a1", 0L, 10L, tier = null, isUnlocked = false)
			data.tier.shouldBeNull()
		}

		@Test
		fun `equality`() {
			val d1 = AchievementProgressData("a", 1L, 2L, "Gold", true)
			val d2 = AchievementProgressData("a", 1L, 2L, "Gold", true)
			d1 shouldBe d2
		}
	}
}
