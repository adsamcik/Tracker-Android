package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Sessionless enums - SampleQuality, MotionState, CoordinateProvenance, SegmentSource, SkiSegmentType")
class SessionlessEnumsTest {

	@Nested
	@DisplayName("SampleQuality")
	inner class SampleQualityTest {
		@Test
		fun `has 4 entries`() {
			SampleQuality.entries.size shouldBe 4
		}

		@Test
		fun `contains expected values`() {
			SampleQuality.entries.map { it.name } shouldBe listOf("HIGH", "MEDIUM", "LOW", "COARSE")
		}

		@Test
		fun `valueOf round-trips`() {
			SampleQuality.entries.forEach { q ->
				SampleQuality.valueOf(q.name) shouldBe q
			}
		}
	}

	@Nested
	@DisplayName("MotionState")
	inner class MotionStateTest {
		@Test
		fun `has 3 entries`() {
			MotionState.entries.size shouldBe 3
		}

		@Test
		fun `contains expected values`() {
			MotionState.entries.map { it.name } shouldBe listOf("MOVING", "STILL", "UNKNOWN")
		}

		@Test
		fun `valueOf round-trips`() {
			MotionState.entries.forEach { m ->
				MotionState.valueOf(m.name) shouldBe m
			}
		}
	}

	@Nested
	@DisplayName("CoordinateProvenance")
	inner class CoordinateProvenanceTest {
		@Test
		fun `has 5 entries`() {
			CoordinateProvenance.entries.size shouldBe 5
		}

		@Test
		fun `contains expected values`() {
			CoordinateProvenance.entries.map { it.name } shouldBe
					listOf("UNKNOWN", "NEAREST_LOCATION", "INTERPOLATED", "DWELL_CENTER", "DIRECT")
		}

		@Test
		fun `valueOf round-trips`() {
			CoordinateProvenance.entries.forEach { p ->
				CoordinateProvenance.valueOf(p.name) shouldBe p
			}
		}
	}

	@Nested
	@DisplayName("SegmentSource")
	inner class SegmentSourceTest {
		@Test
		fun `has 5 entries`() {
			SegmentSource.entries.size shouldBe 5
		}

		@Test
		fun `contains expected values`() {
			SegmentSource.entries.map { it.name } shouldBe listOf(
				"USER_CREATED", "INFERRED_HIGH_CONFIDENCE", "INFERRED_MEDIUM_CONFIDENCE",
				"INFERRED_LOW_CONFIDENCE", "LEGACY_MIGRATION"
			)
		}

		@Test
		fun `valueOf round-trips`() {
			SegmentSource.entries.forEach { s ->
				SegmentSource.valueOf(s.name) shouldBe s
			}
		}
	}

	@Nested
	@DisplayName("SkiSegmentType")
	inner class SkiSegmentTypeTest {
		@Test
		fun `has 4 entries`() {
			SkiSegmentType.entries.size shouldBe 4
		}

		@Test
		fun `contains expected values`() {
			SkiSegmentType.entries.map { it.name } shouldBe
					listOf("DOWNHILL_RUN", "LIFT_UP", "IDLE", "WALK")
		}

		@Test
		fun `valueOf round-trips`() {
			SkiSegmentType.entries.forEach { t ->
				SkiSegmentType.valueOf(t.name) shouldBe t
			}
		}
	}
}
