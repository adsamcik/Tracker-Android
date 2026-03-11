package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StringExtensionsTest {

	@Test
	fun `formatTrackedSteps keeps positive counts readable`() {
		12.formatTrackedSteps(stepCounterSupported = false) shouldBe 12.formatReadable()
	}

	@Test
	fun `formatTrackedSteps shows zero when step counter is supported`() {
		0.formatTrackedSteps(stepCounterSupported = true) shouldBe "0"
	}

	@Test
	fun `formatTrackedSteps shows placeholder when step counter is unavailable`() {
		0.formatTrackedSteps(stepCounterSupported = false) shouldBe "—"
	}
}
