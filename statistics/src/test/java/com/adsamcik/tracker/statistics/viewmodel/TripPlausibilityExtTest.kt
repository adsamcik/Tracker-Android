package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TripPlausibilityExt")
class TripPlausibilityExtTest {

	private fun trip(
		distanceM: Float,
		durationMs: Long,
		primaryActivity: Int? = null,
		hasDistanceAnomaly: Boolean = false,
	) = Trip(
		id = 1L,
		startTimeMs = 1_000_000L,
		endTimeMs = 1_000_000L + durationMs,
		distanceM = distanceM,
		steps = null,
		primaryActivity = primaryActivity,
		activityConfidence = null,
		sampleCount = 10,
		source = SegmentSource.USER_CREATED,
		createdAt = 1_000_000L,
		hasDistanceAnomaly = hasDistanceAnomaly,
	)

	@Nested
	@DisplayName("hasDistanceAnomaly authoritative flag")
	inner class HasDistanceAnomaly {
		@Test
		fun `normal trip has no anomaly`() {
			val t = trip(distanceM = 5_000f, durationMs = 3_600_000L, primaryActivity = 7)
			t.hasDistanceAnomaly shouldBe false
		}

		@Test
		fun `F02 case 1 - anomaly flag persisted as true`() {
			val t = trip(
				distanceM = 9_393_800f,
				durationMs = 121_000L,
				hasDistanceAnomaly = true,
			)
			t.hasDistanceAnomaly shouldBe true
		}

		@Test
		fun `F02 case 2 - anomaly flag persisted as true`() {
			val t = trip(
				distanceM = 5_400f,
				durationMs = 41_000L,
				hasDistanceAnomaly = true,
			)
			t.hasDistanceAnomaly shouldBe true
		}

		@Test
		fun `reasonable drive has no anomaly`() {
			val t = trip(distanceM = 100_000f, durationMs = 3_600_000L, primaryActivity = 0)
			t.hasDistanceAnomaly shouldBe false
		}

		@Test
		fun `stationary trip has no anomaly`() {
			val t = trip(distanceM = 0f, durationMs = 60_000L, primaryActivity = 7)
			t.hasDistanceAnomaly shouldBe false
		}
	}

	@Nested
	@DisplayName("detectedActivityType mapping")
	inner class ActivityTypeMapping {
		@Test
		fun `maps walking correctly`() {
			trip(0f, 1000L, primaryActivity = 7).detectedActivityType() shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `maps running correctly`() {
			trip(0f, 1000L, primaryActivity = 8).detectedActivityType() shouldBe DetectedActivityType.RUNNING
		}

		@Test
		fun `maps cycling correctly`() {
			trip(0f, 1000L, primaryActivity = 1).detectedActivityType() shouldBe DetectedActivityType.ON_BICYCLE
		}

		@Test
		fun `maps vehicle correctly`() {
			trip(0f, 1000L, primaryActivity = 0).detectedActivityType() shouldBe DetectedActivityType.IN_VEHICLE
		}

		@Test
		fun `maps on_foot correctly`() {
			trip(0f, 1000L, primaryActivity = 2).detectedActivityType() shouldBe DetectedActivityType.ON_FOOT
		}

		@Test
		fun `maps null activity to null`() {
			trip(0f, 1000L, primaryActivity = null).detectedActivityType() shouldBe null
		}

		@Test
		fun `maps unknown activity code to null`() {
			trip(0f, 1000L, primaryActivity = 999).detectedActivityType() shouldBe null
		}
	}
}
