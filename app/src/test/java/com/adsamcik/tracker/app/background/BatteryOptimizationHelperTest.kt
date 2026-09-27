package com.adsamcik.tracker.app.background

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BatteryOptimizationHelper")
class BatteryOptimizationHelperTest {

    @Test
	fun `explicit background restriction takes precedence over allowlist state`() {
		resolveBackgroundReliabilityCondition(
			batteryOptimizationsExempt = true,
			backgroundRestricted = true,
		) shouldBe BackgroundReliabilityCondition.BACKGROUND_RESTRICTED
		resolveBackgroundReliabilityCondition(
			batteryOptimizationsExempt = false,
			backgroundRestricted = true,
		) shouldBe BackgroundReliabilityCondition.BACKGROUND_RESTRICTED
    }

    @Test
	fun `battery optimization is actionable only when explicitly observed`() {
		resolveBackgroundReliabilityCondition(
			batteryOptimizationsExempt = false,
			backgroundRestricted = false,
		) shouldBe BackgroundReliabilityCondition.BATTERY_OPTIMIZED
		BackgroundReliabilityState(false, false).actionRequired shouldBe true
		BackgroundReliabilityState(true, false).actionRequired shouldBe false
	}

	@Test
	fun `unknown signals do not invent a platform cause`() {
		resolveBackgroundReliabilityCondition(null, null) shouldBe
			BackgroundReliabilityCondition.UNKNOWN
		resolveBackgroundReliabilityCondition(true, null) shouldBe
			BackgroundReliabilityCondition.UNKNOWN
		resolveBackgroundReliabilityCondition(null, false) shouldBe
			BackgroundReliabilityCondition.UNKNOWN
		resolveBackgroundReliabilityCondition(true, false) shouldBe
			BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION
    }
}
