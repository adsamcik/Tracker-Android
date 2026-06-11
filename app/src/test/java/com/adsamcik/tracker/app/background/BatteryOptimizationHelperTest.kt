package com.adsamcik.tracker.app.background

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BatteryOptimizationHelper")
class BatteryOptimizationHelperTest {

    @Test
    fun `recognizes samsung regardless of case and whitespace`() {
        BatteryOptimizationHelper.isSamsungManufacturer("samsung") shouldBe true
        BatteryOptimizationHelper.isSamsungManufacturer("Samsung") shouldBe true
        BatteryOptimizationHelper.isSamsungManufacturer("SAMSUNG") shouldBe true
        BatteryOptimizationHelper.isSamsungManufacturer("  samsung  ") shouldBe true
    }

    @Test
    fun `rejects non-samsung and null`() {
        BatteryOptimizationHelper.isSamsungManufacturer("Google") shouldBe false
        BatteryOptimizationHelper.isSamsungManufacturer("Xiaomi") shouldBe false
        BatteryOptimizationHelper.isSamsungManufacturer("") shouldBe false
        BatteryOptimizationHelper.isSamsungManufacturer(null) shouldBe false
    }
}
