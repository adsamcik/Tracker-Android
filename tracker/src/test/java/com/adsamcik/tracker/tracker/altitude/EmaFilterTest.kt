package com.adsamcik.tracker.tracker.altitude

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("EmaFilter")
class EmaFilterTest {

	@Nested
	@DisplayName("constructor validation")
	inner class ConstructorValidation {
		@Test
		fun `rejects alpha of 0`() {
			shouldThrow<IllegalArgumentException> { EmaFilter(0f) }
		}

		@Test
		fun `rejects negative alpha`() {
			shouldThrow<IllegalArgumentException> { EmaFilter(-0.1f) }
		}

		@Test
		fun `accepts alpha of 1`() {
			EmaFilter(1f)
		}

		@Test
		fun `accepts typical alpha`() {
			EmaFilter(0.2f)
		}
	}

	@Nested
	@DisplayName("filtering behavior")
	inner class FilteringBehavior {
		@Test
		fun `first value passes through unfiltered`() {
			val filter = EmaFilter(0.2f)
			filter.update(100.0) shouldBe 100.0
		}

		@Test
		fun `alpha 1 passes values through unfiltered`() {
			val filter = EmaFilter(1f)
			filter.update(100.0)
			filter.update(200.0) shouldBe 200.0
		}

		@Test
		fun `smooths a spike`() {
			val filter = EmaFilter(0.2f)
			filter.update(100.0) // initialize
			val afterSpike = filter.update(200.0) // huge spike
			// With alpha=0.2: 0.2*200 + 0.8*100 = 120
			abs(afterSpike - 120.0) shouldBeLessThan 0.01
		}

		@Test
		fun `converges toward constant input`() {
			val filter = EmaFilter(0.2f)
			filter.update(100.0)
			// Feed constant 200 many times — should converge
			var value = 0.0
			repeat(50) { value = filter.update(200.0) }
			abs(value - 200.0) shouldBeLessThan 0.01
		}

		@Test
		fun `lower alpha produces smoother output`() {
			val smoothFilter = EmaFilter(0.1f)
			val responsiveFilter = EmaFilter(0.5f)

			smoothFilter.update(100.0)
			responsiveFilter.update(100.0)

			val smoothResult = smoothFilter.update(200.0)
			val responsiveResult = responsiveFilter.update(200.0)

			// More responsive filter should be closer to 200
			responsiveResult shouldBeGreaterThan smoothResult
		}
	}

	@Nested
	@DisplayName("state management")
	inner class StateManagement {
		@Test
		fun `isInitialized is false before first update`() {
			val filter = EmaFilter(0.2f)
			filter.isInitialized shouldBe false
		}

		@Test
		fun `isInitialized is true after first update`() {
			val filter = EmaFilter(0.2f)
			filter.update(100.0)
			filter.isInitialized shouldBe true
		}

		@Test
		fun `reset clears state`() {
			val filter = EmaFilter(0.2f)
			filter.update(100.0)
			filter.reset()
			filter.isInitialized shouldBe false
			// After reset, next value should pass through unfiltered
			filter.update(200.0) shouldBe 200.0
		}
	}
}
