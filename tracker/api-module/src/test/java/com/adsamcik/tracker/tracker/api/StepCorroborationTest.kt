package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.base.data.GroupedActivity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("isOnFootAutoStartCorroborated")
class StepCorroborationTest {

	private val required = 75
	private val corroborated = 50

	private fun evaluate(
		activity: GroupedActivity,
		confidence: Int,
		hasRecentSteps: Boolean,
	): Boolean = isOnFootAutoStartCorroborated(
		groupedActivity = activity,
		confidence = confidence,
		requiredConfidence = required,
		corroboratedConfidence = corroborated,
		hasRecentSteps = hasRecentSteps,
	)

	@Nested
	@DisplayName("full confidence path (unchanged behaviour)")
	inner class FullConfidence {
		@Test
		fun `qualifies at or above the required confidence regardless of steps`() {
			evaluate(GroupedActivity.ON_FOOT, confidence = 75, hasRecentSteps = false) shouldBe true
		}

		@Test
		fun `qualifies for non-foot activities at full confidence`() {
			evaluate(GroupedActivity.IN_VEHICLE, confidence = 90, hasRecentSteps = false) shouldBe true
		}
	}

	@Nested
	@DisplayName("step-corroborated on-foot path")
	inner class Corroborated {
		@Test
		fun `qualifies below required confidence when steps confirm walking`() {
			evaluate(GroupedActivity.ON_FOOT, confidence = 60, hasRecentSteps = true) shouldBe true
		}

		@Test
		fun `does not qualify below required confidence without recent steps`() {
			evaluate(GroupedActivity.ON_FOOT, confidence = 60, hasRecentSteps = false) shouldBe false
		}

		@Test
		fun `does not qualify below the corroborated floor even with steps`() {
			evaluate(GroupedActivity.ON_FOOT, confidence = 40, hasRecentSteps = true) shouldBe false
		}
	}

	@Nested
	@DisplayName("non-foot activities are never step-corroborated")
	inner class NonFoot {
		@Test
		fun `vehicle below required confidence never qualifies on steps`() {
			evaluate(GroupedActivity.IN_VEHICLE, confidence = 60, hasRecentSteps = true) shouldBe false
		}
	}
}
