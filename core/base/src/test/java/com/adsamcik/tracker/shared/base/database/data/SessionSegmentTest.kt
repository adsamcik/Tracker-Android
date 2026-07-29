package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SessionSegment - inferred travel episode entity")
class SessionSegmentTest {

	private fun segment(
		id: Long = 0,
		startTimeMs: Long = 1000L,
		endTimeMs: Long = 2000L,
		distanceM: Float = 500f,
		steps: Int? = 100,
		primaryActivity: Int? = 0,
		activityConfidence: Int? = 90,
		sampleCount: Int = 10,
		source: SegmentSource = SegmentSource.USER_CREATED,
		inferenceVersion: String? = "v1",
		createdAt: Long = 1000L,
		hasDistanceAnomaly: Boolean = false
	) = SessionSegment(
		id, startTimeMs, endTimeMs, distanceM, steps, primaryActivity,
		activityConfidence, sampleCount, source, inferenceVersion, createdAt, hasDistanceAnomaly
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val s = segment()
			s.id shouldBe 0
			s.startTimeMs shouldBe 1000L
			s.endTimeMs shouldBe 2000L
			s.distanceM shouldBe 500f
			s.steps shouldBe 100
			s.primaryActivity shouldBe 0
			s.activityConfidence shouldBe 90
			s.sampleCount shouldBe 10
			s.source shouldBe SegmentSource.USER_CREATED
			s.inferenceVersion shouldBe "v1"
			s.createdAt shouldBe 1000L
			s.hasDistanceAnomaly shouldBe false
		}

		@Test
		fun `nullable fields accept null`() {
			val s = segment(steps = null, primaryActivity = null, activityConfidence = null, inferenceVersion = null)
			s.steps shouldBe null
			s.primaryActivity shouldBe null
			s.activityConfidence shouldBe null
			s.inferenceVersion shouldBe null
		}

		@Test
		fun `hasDistanceAnomaly defaults to false`() {
			val s = SessionSegment(
				startTimeMs = 0, endTimeMs = 0, distanceM = 0f,
				steps = null, primaryActivity = null, activityConfidence = null,
				sampleCount = 0, source = SegmentSource.USER_CREATED,
				inferenceVersion = null, createdAt = 0
			)
			s.hasDistanceAnomaly shouldBe false
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			segment() shouldBe segment()
		}

		@Test
		fun `inequality`() {
			segment(distanceM = 100f) shouldNotBe segment(distanceM = 200f)
		}

		@Test
		fun `copy modifies single field`() {
			val s = segment().copy(distanceM = 999f)
			s.distanceM shouldBe 999f
			s.startTimeMs shouldBe 1000L
		}
	}
}
