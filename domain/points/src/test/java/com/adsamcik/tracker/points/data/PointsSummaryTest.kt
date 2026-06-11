package com.adsamcik.tracker.points.data

import com.adsamcik.tracker.shared.base.database.data.DateRange
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PointsSummaryTest {

	@Nested
	inner class Construction {
		@Test
		fun `creates summary with valid range and points`() {
			val range = DateRange(1000L, 2000L)
			val points = Points(50.0)
			val summary = PointsSummary(range, points)

			summary.intervalRange shouldBe range
			summary.value shouldBe points
		}

		@Test
		fun `creates summary with zero points`() {
			val range = DateRange(0L, 1000L)
			val summary = PointsSummary(range, Points(0.0))

			summary.value.value shouldBeExactly 0.0
		}

		@Test
		fun `creates summary with same start and end range`() {
			val range = DateRange(5000L, 5000L)
			val summary = PointsSummary(range, Points(10.0))

			summary.intervalRange.start shouldBe summary.intervalRange.endInclusive
		}
	}

	@Nested
	inner class IntervalRange {
		@Test
		fun `range start is before end`() {
			val range = DateRange(100L, 500L)
			val summary = PointsSummary(range, Points(25.0))

			summary.intervalRange.endInclusive shouldBeGreaterThan summary.intervalRange.start
		}

		@Test
		fun `range implements ClosedRange correctly`() {
			val range = DateRange(100L, 500L)
			val summary = PointsSummary(range, Points(25.0))

			summary.intervalRange.start shouldBe 100L
			summary.intervalRange.endInclusive shouldBe 500L
		}

		@Test
		fun `contains check works within range`() {
			val range = DateRange(100L, 500L)
			val summary = PointsSummary(range, Points(25.0))

			(300L in summary.intervalRange) shouldBe true
		}

		@Test
		fun `contains check fails outside range`() {
			val range = DateRange(100L, 500L)
			val summary = PointsSummary(range, Points(25.0))

			(600L in summary.intervalRange) shouldBe false
		}
	}

	@Nested
	inner class Equality {
		@Test
		fun `summaries with same data are equal`() {
			val range = DateRange(100L, 200L)
			val points = Points(10.0)

			PointsSummary(range, points) shouldBe PointsSummary(range, points)
		}

		@Test
		fun `summaries with different points are not equal`() {
			val range = DateRange(100L, 200L)

			PointsSummary(range, Points(10.0)) shouldNotBe PointsSummary(range, Points(20.0))
		}

		@Test
		fun `summaries with different ranges are not equal`() {
			val points = Points(10.0)

			PointsSummary(DateRange(100L, 200L), points) shouldNotBe
					PointsSummary(DateRange(300L, 400L), points)
		}
	}

	@Nested
	inner class CopyBehavior {
		@Test
		fun `copy preserves all fields`() {
			val original = PointsSummary(DateRange(100L, 200L), Points(50.0))
			val copy = original.copy()

			copy shouldBe original
		}

		@Test
		fun `copy with new points updates value`() {
			val original = PointsSummary(DateRange(100L, 200L), Points(50.0))
			val updated = original.copy(value = Points(100.0))

			updated.value.value shouldBeExactly 100.0
			updated.intervalRange shouldBe original.intervalRange
		}

		@Test
		fun `copy with new range updates interval`() {
			val original = PointsSummary(DateRange(100L, 200L), Points(50.0))
			val newRange = DateRange(300L, 400L)
			val updated = original.copy(intervalRange = newRange)

			updated.intervalRange shouldBe newRange
			updated.value shouldBe original.value
		}
	}
}
