package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LocationSample - raw location sample entity")
class LocationSampleTest {

	private fun sample(
		id: Long = 0,
		timeMs: Long = 1000L,
		elapsedRealtimeNanos: Long = 5000000000L,
		latE7: Int? = 500000000,
		lonE7: Int? = 140000000,
		altitudeM: Float? = 300f,
		rawGpsAltitudeM: Float? = 298f,
		hAccM: Float? = 5f,
		vAccM: Float? = 10f,
		speedMps: Float? = 1.5f,
		speedAccuracyMps: Float? = 0.5f,
		provider: String = "fused",
		quality: SampleQuality = SampleQuality.HIGH,
		motionState: MotionState? = MotionState.MOVING,
		policy: String? = "PASSIVE_LOW",
		bucketId: Long? = null,
		createdAt: Long = 1000L
	) = LocationSample(
		id, timeMs, elapsedRealtimeNanos, latE7, lonE7, altitudeM, rawGpsAltitudeM,
		hAccM, vAccM, speedMps, speedAccuracyMps, provider, quality, motionState,
		policy, bucketId, createdAt
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val s = sample()
			s.timeMs shouldBe 1000L
			s.elapsedRealtimeNanos shouldBe 5000000000L
			s.latE7 shouldBe 500000000
			s.lonE7 shouldBe 140000000
			s.altitudeM shouldBe 300f
			s.rawGpsAltitudeM shouldBe 298f
			s.hAccM shouldBe 5f
			s.vAccM shouldBe 10f
			s.speedMps shouldBe 1.5f
			s.speedAccuracyMps shouldBe 0.5f
			s.provider shouldBe "fused"
			s.quality shouldBe SampleQuality.HIGH
			s.motionState shouldBe MotionState.MOVING
			s.policy shouldBe "PASSIVE_LOW"
			s.bucketId shouldBe null
		}

		@Test
		fun `nullable fields accept null`() {
			val s = sample(
				latE7 = null, lonE7 = null, altitudeM = null, rawGpsAltitudeM = null,
				hAccM = null, vAccM = null, speedMps = null, speedAccuracyMps = null,
				motionState = null, policy = null, bucketId = null
			)
			s.latE7 shouldBe null
			s.lonE7 shouldBe null
			s.altitudeM shouldBe null
			s.motionState shouldBe null
			s.policy shouldBe null
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			sample() shouldBe sample()
		}

		@Test
		fun `inequality`() {
			sample(timeMs = 1) shouldNotBe sample(timeMs = 2)
		}

		@Test
		fun `copy`() {
			val s = sample().copy(quality = SampleQuality.LOW)
			s.quality shouldBe SampleQuality.LOW
			s.provider shouldBe "fused"
		}
	}
}
