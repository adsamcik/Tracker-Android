package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StringExtensionsTest {

	@Test
	fun `formatTrackedSteps keeps positive counts readable`() {
		12.formatTrackedSteps(
			hasVerifiedCoverage = false,
			unavailableText = "Unavailable",
		) shouldBe 12.formatReadable()
	}

	@Test
	fun `formatTrackedSteps shows zero only with verified coverage`() {
		0.formatTrackedSteps(
			hasVerifiedCoverage = true,
			unavailableText = "Unavailable",
		) shouldBe "0"
	}

	@Test
	fun `formatTrackedSteps shows placeholder without verified coverage`() {
		0.formatTrackedSteps(
			hasVerifiedCoverage = false,
			unavailableText = "Unavailable",
		) shouldBe "Unavailable"
	}

	@Test
	fun `formatTrackedSteps never turns an invalid negative value into zero`() {
		(-1).formatTrackedSteps(
			hasVerifiedCoverage = true,
			unavailableText = "Unavailable",
		) shouldBe "Unavailable"
	}
}
