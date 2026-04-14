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

class RepositoryDataClassesTest {

	@Nested
	inner class AchievementProgressDataTests {

		@Test
		fun `construction with all fields`() {
			val data = AchievementProgressData(
				achievementId = "explorer_cells",
				currentValue = 42L,
				targetValue = 100L,
				tier = "BRONZE",
				isUnlocked = true,
			)
			data.achievementId shouldBe "explorer_cells"
			data.currentValue shouldBe 42L
			data.targetValue shouldBe 100L
			data.tier shouldBe "BRONZE"
			data.isUnlocked shouldBe true
		}

		@Test
		fun `construction with null tier`() {
			val data = AchievementProgressData(
				achievementId = "test",
				currentValue = 0L,
				targetValue = 10L,
				tier = null,
				isUnlocked = false,
			)
			data.tier.shouldBeNull()
			data.isUnlocked shouldBe false
		}

		@Test
		fun `data class equality`() {
			val a = AchievementProgressData("a", 1L, 10L, "BRONZE", true)
			val b = AchievementProgressData("a", 1L, 10L, "BRONZE", true)
			a shouldBe b
		}

		@Test
		fun `copy preserves unmodified fields`() {
			val original = AchievementProgressData("a", 1L, 10L, "BRONZE", true)
			val copied = original.copy(currentValue = 5L)
			copied.achievementId shouldBe "a"
			copied.currentValue shouldBe 5L
			copied.tier shouldBe "BRONZE"
		}
	}

	@Nested
	inner class DailySummaryTests {

		@Test
		fun `construction preserves all fields`() {
			val summary = DailySummary(
				dayEpoch = 20000L,
				totalDistance = DistanceM(5000f),
				totalSteps = StepCount(8000),
				totalDuration = DurationMs(3_600_000L),
				tripCount = 3,
				activeTrackingDuration = DurationMs(2_400_000L),
			)
			summary.dayEpoch shouldBe 20000L
			summary.totalDistance shouldBe DistanceM(5000f)
			summary.totalSteps shouldBe StepCount(8000)
			summary.totalDuration shouldBe DurationMs(3_600_000L)
			summary.tripCount shouldBe 3
			summary.activeTrackingDuration shouldBe DurationMs(2_400_000L)
		}

		@Test
		fun `data class equality`() {
			val a = DailySummary(1L, DistanceM(100f), StepCount(200), DurationMs(300L), 1, DurationMs(200L))
			val b = DailySummary(1L, DistanceM(100f), StepCount(200), DurationMs(300L), 1, DurationMs(200L))
			a shouldBe b
		}
	}

	@Nested
	inner class ExplorationStatsTests {

		@Test
		fun `default values are zero`() {
			val stats = ExplorationStats()
			stats.totalCells shouldBe 0
			stats.recentDiscoveries shouldBe 0
		}

		@Test
		fun `construction with explicit values`() {
			val stats = ExplorationStats(totalCells = 150, recentDiscoveries = 5)
			stats.totalCells shouldBe 150
			stats.recentDiscoveries shouldBe 5
		}

		@Test
		fun `data class equality`() {
			ExplorationStats(10, 2) shouldBe ExplorationStats(10, 2)
		}
	}

	@Nested
	inner class LiveStatsTests {

		@Test
		fun `default LiveStats has zero values`() {
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
		fun `construction with all fields`() {
			val stats = LiveStats(
				sessionDistance = DistanceM(500f),
				sessionSteps = StepCount(650),
				sessionDuration = DurationMs(1200_000L),
				currentSpeed = SpeedMps(1.5f),
				avgSpeed = SpeedMps(1.2f),
				maxSpeed = SpeedMps(2.0f),
				dayTotalDistance = DistanceM(5000f),
				dayTotalSteps = StepCount(6500),
				dominantActivity = DetectedActivityType.WALKING,
				tripCount = 3,
			)
			stats.sessionDistance shouldBe DistanceM(500f)
			stats.dominantActivity shouldBe DetectedActivityType.WALKING
			stats.tripCount shouldBe 3
		}

		@Test
		fun `data class equality`() {
			val a = LiveStats(sessionDistance = DistanceM(100f))
			val b = LiveStats(sessionDistance = DistanceM(100f))
			a shouldBe b
		}
	}

	@Nested
	inner class SessionStatsSnapshotTests {

		@Test
		fun `construction preserves all fields`() {
			val snap = SessionStatsSnapshot(
				duration = DurationMs(7200_000L),
				collections = 100L,
				totalDistance = DistanceM(10_000f),
				onFootDistance = DistanceM(5000f),
				inVehicleDistance = DistanceM(5000f),
				steps = StepCount(12_000),
				tripCount = 5L,
				locationCount = 500L,
				wifiCount = 200L,
				cellCount = 150L,
			)
			snap.duration shouldBe DurationMs(7200_000L)
			snap.collections shouldBe 100L
			snap.totalDistance shouldBe DistanceM(10_000f)
			snap.onFootDistance shouldBe DistanceM(5000f)
			snap.inVehicleDistance shouldBe DistanceM(5000f)
			snap.steps shouldBe StepCount(12_000)
			snap.tripCount shouldBe 5L
			snap.locationCount shouldBe 500L
			snap.wifiCount shouldBe 200L
			snap.cellCount shouldBe 150L
		}

		@Test
		fun `data class equality`() {
			val a = SessionStatsSnapshot(
				DurationMs(100L), 1L, DistanceM(10f), DistanceM(5f),
				DistanceM(5f), StepCount(100), 1L, 10L, 5L, 3L,
			)
			val b = SessionStatsSnapshot(
				DurationMs(100L), 1L, DistanceM(10f), DistanceM(5f),
				DistanceM(5f), StepCount(100), 1L, 10L, 5L, 3L,
			)
			a shouldBe b
		}
	}

	@Nested
	inner class TripSummaryTests {

		@Test
		fun `construction preserves all fields`() {
			val trip = TripSummary(
				id = 42L,
				startTimeMs = EpochMs(1_700_000_000_000L),
				endTimeMs = EpochMs(1_700_003_600_000L),
				distance = DistanceM(5000f),
				steps = StepCount(6500),
				duration = DurationMs(3_600_000L),
				primaryMode = TransportMode.WALK,
				sampleCount = 120,
			)
			trip.id shouldBe 42L
			trip.startTimeMs shouldBe EpochMs(1_700_000_000_000L)
			trip.endTimeMs shouldBe EpochMs(1_700_003_600_000L)
			trip.distance shouldBe DistanceM(5000f)
			trip.steps shouldBe StepCount(6500)
			trip.duration shouldBe DurationMs(3_600_000L)
			trip.primaryMode shouldBe TransportMode.WALK
			trip.sampleCount shouldBe 120
		}

		@Test
		fun `data class equality`() {
			val a = TripSummary(1L, EpochMs(100L), EpochMs(200L), DistanceM(10f), StepCount(20), DurationMs(100L), TransportMode.WALK, 5)
			val b = TripSummary(1L, EpochMs(100L), EpochMs(200L), DistanceM(10f), StepCount(20), DurationMs(100L), TransportMode.WALK, 5)
			a shouldBe b
		}

		@Test
		fun `copy with different mode preserves other fields`() {
			val original = TripSummary(1L, EpochMs(100L), EpochMs(200L), DistanceM(10f), StepCount(20), DurationMs(100L), TransportMode.WALK, 5)
			val modified = original.copy(primaryMode = TransportMode.CYCLE)
			modified.id shouldBe original.id
			modified.distance shouldBe original.distance
			modified.primaryMode shouldBe TransportMode.CYCLE
		}
	}

	@Nested
	inner class WifiObservationTests {

		@Test
		fun `WifiObservationBrowseFilter defaults`() {
			val filter = WifiObservationBrowseFilter()
			filter.bssid.shouldBeNull()
			filter.ssid.shouldBeNull()
			filter.capabilities.shouldBeNull()
			filter.frequencyPrefix.shouldBeNull()
			filter.limit shouldBe DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT
		}

		@Test
		fun `DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT is 1000`() {
			DEFAULT_WIFI_OBSERVATION_BROWSE_LIMIT shouldBe 1_000
		}

		@Test
		fun `WifiObservationBrowseFilter with all fields`() {
			val filter = WifiObservationBrowseFilter(
				bssid = "AA:BB:CC:DD:EE:FF",
				ssid = "TestNetwork",
				capabilities = "[WPA2-PSK]",
				frequencyPrefix = "5",
				limit = 500,
			)
			filter.bssid shouldBe "AA:BB:CC:DD:EE:FF"
			filter.ssid shouldBe "TestNetwork"
			filter.capabilities shouldBe "[WPA2-PSK]"
			filter.frequencyPrefix shouldBe "5"
			filter.limit shouldBe 500
		}

		@Test
		fun `WifiObservationBrowseItem construction`() {
			val item = WifiObservationBrowseItem(
				bssid = "AA:BB:CC:DD:EE:FF",
				ssid = "MyNetwork",
				capabilities = "[WPA2]",
				frequency = 5180,
				firstSeenAt = EpochMs(1_000_000L),
				lastSeenAt = EpochMs(2_000_000L),
			)
			item.bssid shouldBe "AA:BB:CC:DD:EE:FF"
			item.ssid shouldBe "MyNetwork"
			item.frequency shouldBe 5180
			item.firstSeenAt shouldBe EpochMs(1_000_000L)
			item.lastSeenAt shouldBe EpochMs(2_000_000L)
		}

		@Test
		fun `WifiObservationStatsSummary construction`() {
			val summary = WifiObservationStatsSummary(
				uniqueNetworks = 42L,
				totalScans = 1000L,
				averageNetworksPerScan = 5.5,
			)
			summary.uniqueNetworks shouldBe 42L
			summary.totalScans shouldBe 1000L
			summary.averageNetworksPerScan shouldBe 5.5
		}

		@Test
		fun `WifiObservationBrowseFilter data class equality`() {
			val a = WifiObservationBrowseFilter(bssid = "AA:BB")
			val b = WifiObservationBrowseFilter(bssid = "AA:BB")
			a shouldBe b
		}
	}

	@Nested
	inner class DomainEventRepositoryCompanion {

		@Test
		fun `DEFAULT_UNCONSUMED_BATCH_SIZE is 100`() {
			DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE shouldBe 100
		}
	}
}
