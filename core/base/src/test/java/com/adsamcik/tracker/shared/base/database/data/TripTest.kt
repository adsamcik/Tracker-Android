package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Trip - read-only session segment projection")
class TripTest {

	private fun trip(
		id: Long = 1,
		startTimeMs: Long = 1000L,
		endTimeMs: Long = 2000L,
		distanceM: Float = 500f,
		steps: Int? = 100,
		primaryActivity: Int? = 0,
		activityConfidence: Int? = 80,
		sampleCount: Int = 10,
		source: SegmentSource = SegmentSource.USER_CREATED,
		createdAt: Long = 1000L,
		hasDistanceAnomaly: Boolean = false
	) = Trip(id, startTimeMs, endTimeMs, distanceM, steps, primaryActivity,
		activityConfidence, sampleCount, source, createdAt, hasDistanceAnomaly)

	@Nested
	@DisplayName("Computed properties")
	inner class ComputedProperties {
		@Test
		fun `durationMs is endTimeMs minus startTimeMs`() {
			trip(startTimeMs = 1000L, endTimeMs = 5000L).durationMs shouldBe 4000L
		}

		@Test
		fun `durationMs is zero for same start and end`() {
			trip(startTimeMs = 1000L, endTimeMs = 1000L).durationMs shouldBe 0L
		}

		@Test
		fun `isUserInitiated true when source is USER_CREATED`() {
			trip(source = SegmentSource.USER_CREATED).isUserInitiated shouldBe true
		}

		@Test
		fun `isUserInitiated false for inferred source`() {
			trip(source = SegmentSource.INFERRED_HIGH_CONFIDENCE).isUserInitiated shouldBe false
			trip(source = SegmentSource.INFERRED_MEDIUM_CONFIDENCE).isUserInitiated shouldBe false
			trip(source = SegmentSource.INFERRED_LOW_CONFIDENCE).isUserInitiated shouldBe false
			trip(source = SegmentSource.LEGACY_MIGRATION).isUserInitiated shouldBe false
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			trip() shouldBe trip()
		}

		@Test
		fun `inequality`() {
			trip(id = 1) shouldNotBe trip(id = 2)
		}

		@Test
		fun `copy`() {
			val t = trip().copy(distanceM = 999f)
			t.distanceM shouldBe 999f
		}

		@Test
		fun `hasDistanceAnomaly defaults to false`() {
			val t = Trip(
				id = 1, startTimeMs = 0, endTimeMs = 0, distanceM = 0f,
				steps = null, primaryActivity = null, activityConfidence = null,
				sampleCount = 0, source = SegmentSource.USER_CREATED, createdAt = 0
			)
			t.hasDistanceAnomaly shouldBe false
		}
	}
}

@DisplayName("TripDaySummary - aggregated trip summary")
class TripDaySummaryTest {

	@Test
	fun `stores all fields`() {
		val summary = TripDaySummary(
			tripCount = 5,
			totalDistanceM = 10000f,
			totalSteps = 15000,
			totalDurationMs = 3600000L
		)
		summary.tripCount shouldBe 5
		summary.totalDistanceM shouldBe 10000f
		summary.totalSteps shouldBe 15000
		summary.totalDurationMs shouldBe 3600000L
	}

	@Test
	fun `equality`() {
		val a = TripDaySummary(1, 100f, 200, 300L)
		val b = TripDaySummary(1, 100f, 200, 300L)
		a shouldBe b
	}
}
