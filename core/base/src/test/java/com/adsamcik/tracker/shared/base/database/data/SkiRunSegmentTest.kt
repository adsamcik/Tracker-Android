package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SkiRunSegment - ski session segment entity")
class SkiRunSegmentTest {

	private fun segment(
		id: Long = 0,
		sessionId: Long = 1L,
		runIndex: Int = 0,
		segmentType: SkiSegmentType = SkiSegmentType.DOWNHILL_RUN,
		startTimeMs: Long = 1000L,
		endTimeMs: Long = 2000L,
		verticalM: Float = -200f,
		distanceM: Float = 800f,
		maxSpeedMps: Float = 15f,
		avgSpeedMps: Float = 10f,
		liftType: String? = null,
		createdAt: Long = 1000L
	) = SkiRunSegment(
		id, sessionId, runIndex, segmentType, startTimeMs, endTimeMs,
		verticalM, distanceM, maxSpeedMps, avgSpeedMps, liftType, createdAt
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val s = segment()
			s.sessionId shouldBe 1L
			s.runIndex shouldBe 0
			s.segmentType shouldBe SkiSegmentType.DOWNHILL_RUN
			s.verticalM shouldBe -200f
			s.distanceM shouldBe 800f
			s.maxSpeedMps shouldBe 15f
			s.avgSpeedMps shouldBe 10f
			s.liftType shouldBe null
		}

		@Test
		fun `liftType can be set for LIFT_UP segments`() {
			val s = segment(segmentType = SkiSegmentType.LIFT_UP, liftType = "chairlift", verticalM = 300f)
			s.segmentType shouldBe SkiSegmentType.LIFT_UP
			s.liftType shouldBe "chairlift"
			s.verticalM shouldBe 300f
		}

		@Test
		fun `liftType defaults to null`() {
			SkiRunSegment(
				sessionId = 1, runIndex = 0, segmentType = SkiSegmentType.IDLE,
				startTimeMs = 0, endTimeMs = 0, verticalM = 0f, distanceM = 0f,
				maxSpeedMps = 0f, avgSpeedMps = 0f, createdAt = 0
			).liftType shouldBe null
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
			segment(runIndex = 0) shouldNotBe segment(runIndex = 1)
		}

		@Test
		fun `copy`() {
			val s = segment().copy(segmentType = SkiSegmentType.WALK)
			s.segmentType shouldBe SkiSegmentType.WALK
		}
	}
}
