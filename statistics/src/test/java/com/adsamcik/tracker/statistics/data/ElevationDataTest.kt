package com.adsamcik.tracker.statistics.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ElevationData")
class ElevationDataTest {

	private fun sampleLocation() = com.adsamcik.tracker.shared.base.database.data.LocationSample(
		id = 1,
		timeMs = 1000L,
		elapsedRealtimeNanos = 1_000_000_000L,
		latE7 = 500_000_000,
		lonE7 = 150_000_000,
		altitudeM = 300f,
		rawGpsAltitudeM = 298f,
		hAccM = 5f,
		vAccM = 10f,
		speedMps = 1.5f,
		speedAccuracyMps = 0.5f,
		provider = "gps",
		quality = com.adsamcik.tracker.shared.base.database.data.SampleQuality.HIGH,
		motionState = com.adsamcik.tracker.shared.base.database.data.MotionState.MOVING,
		policy = null,
		bucketId = null,
		createdAt = 1000L,
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `stores all fields`() {
			val raw = sampleLocation()
			val data = ElevationData(
				raw = raw,
				altitude = 300.0,
				totalChange = 50.0,
				changePerSecond = 0.5
			)
			data.raw shouldBe raw
			data.altitude shouldBe 300.0
			data.totalChange shouldBe 50.0
			data.changePerSecond shouldBe 0.5
		}
	}

	@Nested
	@DisplayName("Equality")
	inner class Equality {

		@Test
		fun `equality works`() {
			val raw = sampleLocation()
			val d1 = ElevationData(raw, 300.0, 50.0, 0.5)
			val d2 = ElevationData(raw, 300.0, 50.0, 0.5)
			d1 shouldBe d2
			d1.hashCode() shouldBe d2.hashCode()
		}

		@Test
		fun `inequality for different altitude`() {
			val raw = sampleLocation()
			val d1 = ElevationData(raw, 300.0, 50.0, 0.5)
			val d2 = ElevationData(raw, 400.0, 50.0, 0.5)
			d1 shouldNotBe d2
		}
	}

	@Nested
	@DisplayName("Copy and Destructuring")
	inner class CopyAndDestructuring {

		@Test
		fun `copy works`() {
			val raw = sampleLocation()
			val original = ElevationData(raw, 300.0, 50.0, 0.5)
			val copy = original.copy(altitude = 500.0)
			copy.altitude shouldBe 500.0
			copy.totalChange shouldBe 50.0
		}

		@Test
		fun `destructuring works`() {
			val raw = sampleLocation()
			val data = ElevationData(raw, 300.0, 50.0, 0.5)
			val (r, alt, total, perSec) = data
			r shouldBe raw
			alt shouldBe 300.0
			total shouldBe 50.0
			perSec shouldBe 0.5
		}
	}
}
