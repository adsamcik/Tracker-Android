package com.adsamcik.tracker.shared.preferences.tracking

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackingPresetTest {

	@Nested
	inner class `enum values` {
		@Test
		fun `should have exactly four variants`() {
			TrackingPreset.entries.size shouldBe 4
		}

		@Test
		fun `should contain all expected variants`() {
			TrackingPreset.entries.map { it.name } shouldContainAll listOf(
				"HIGH_ACCURACY", "BALANCED", "POWER_SAVE", "CUSTOM"
			)
		}
	}

	@Nested
	inner class `ordinal ordering` {
		@Test
		fun `HIGH_ACCURACY is first`() {
			TrackingPreset.HIGH_ACCURACY.ordinal shouldBe 0
		}

		@Test
		fun `BALANCED is second`() {
			TrackingPreset.BALANCED.ordinal shouldBe 1
		}

		@Test
		fun `POWER_SAVE is third`() {
			TrackingPreset.POWER_SAVE.ordinal shouldBe 2
		}

		@Test
		fun `CUSTOM is fourth`() {
			TrackingPreset.CUSTOM.ordinal shouldBe 3
		}
	}

	@Nested
	inner class `preset properties` {
		@Test
		fun `HIGH_ACCURACY enables all sensors`() {
			val preset = TrackingPreset.HIGH_ACCURACY
			preset.locationEnabled shouldBe true
			preset.wifiEnabled shouldBe true
			preset.cellEnabled shouldBe true
			preset.activityEnabled shouldBe true
			preset.stepsEnabled shouldBe true
		}

		@Test
		fun `HIGH_ACCURACY has aggressive distance and time settings`() {
			val preset = TrackingPreset.HIGH_ACCURACY
			preset.minDistanceMeters shouldBe 10
			preset.minTimeSeconds shouldBe 2
			preset.requiredAccuracyMeters shouldBe 50
		}

		@Test
		fun `BALANCED disables cell but enables others`() {
			val preset = TrackingPreset.BALANCED
			preset.locationEnabled shouldBe true
			preset.wifiEnabled shouldBe true
			preset.cellEnabled shouldBe false
			preset.activityEnabled shouldBe true
			preset.stepsEnabled shouldBe true
		}

		@Test
		fun `BALANCED has moderate distance and time settings`() {
			val preset = TrackingPreset.BALANCED
			preset.minDistanceMeters shouldBe 10
			preset.minTimeSeconds shouldBe 2
			preset.requiredAccuracyMeters shouldBe 50
		}

		@Test
		fun `POWER_SAVE disables wifi, cell, and steps`() {
			val preset = TrackingPreset.POWER_SAVE
			preset.locationEnabled shouldBe true
			preset.wifiEnabled shouldBe false
			preset.cellEnabled shouldBe false
			preset.activityEnabled shouldBe true
			preset.stepsEnabled shouldBe false
		}

		@Test
		fun `POWER_SAVE has large distance and time intervals`() {
			val preset = TrackingPreset.POWER_SAVE
			preset.minDistanceMeters shouldBe 30
			preset.minTimeSeconds shouldBe 10
			preset.requiredAccuracyMeters shouldBe 100
		}

		@Test
		fun `CUSTOM has sensible defaults`() {
			val preset = TrackingPreset.CUSTOM
			preset.locationEnabled shouldBe true
			preset.wifiEnabled shouldBe false
			preset.cellEnabled shouldBe false
			preset.activityEnabled shouldBe true
			preset.stepsEnabled shouldBe true
			preset.minDistanceMeters shouldBe 10
			preset.minTimeSeconds shouldBe 2
			preset.requiredAccuracyMeters shouldBe 50
		}
	}

	@Nested
	inner class `companion object` {
		@Test
		fun `DEFAULT is BALANCED`() {
			TrackingPreset.DEFAULT shouldBe TrackingPreset.BALANCED
		}
	}

	@Nested
	inner class `fromName` {
		@Test
		fun `returns matching variant for exact name`() {
			TrackingPreset.entries.forEach { preset ->
				TrackingPreset.fromName(preset.name) shouldBe preset
			}
		}

		@Test
		fun `maps legacy BATTERY_SAVER to POWER_SAVE`() {
			TrackingPreset.fromName("BATTERY_SAVER") shouldBe TrackingPreset.POWER_SAVE
		}

		@Test
		fun `maps legacy HIGH_PRECISION to HIGH_ACCURACY`() {
			TrackingPreset.fromName("HIGH_PRECISION") shouldBe TrackingPreset.HIGH_ACCURACY
		}

		@Test
		fun `returns DEFAULT for unknown name`() {
			TrackingPreset.fromName("NONEXISTENT") shouldBe TrackingPreset.DEFAULT
		}

		@Test
		fun `returns DEFAULT for empty string`() {
			TrackingPreset.fromName("") shouldBe TrackingPreset.DEFAULT
		}
	}

	@Nested
	inner class `valueOf lookup` {
		@Test
		fun `valueOf returns correct variant for each name`() {
			TrackingPreset.entries.forEach { preset ->
				TrackingPreset.valueOf(preset.name) shouldBe preset
			}
		}

		@Test
		fun `valueOf throws for invalid name`() {
			org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
				TrackingPreset.valueOf("Invalid")
			}
		}
	}
}
