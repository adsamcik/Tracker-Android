package com.adsamcik.tracker.shared.preferences.map

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MapSettingsStateTest {

	@Nested
	inner class `default construction` {
		@Test
		fun `default quality matches companion constant`() {
			MapSettingsState().quality shouldBe MapSettingsState.DEFAULT_QUALITY
		}

		@Test
		fun `default maxHeatPoints matches companion constant`() {
			MapSettingsState().maxHeatPoints shouldBe MapSettingsState.DEFAULT_MAX_HEAT
		}

		@Test
		fun `default visitThresholdSeconds matches companion constant`() {
			MapSettingsState().visitThresholdSeconds shouldBe MapSettingsState.DEFAULT_VISIT_THRESHOLD
		}
	}

	@Nested
	inner class `companion constants` {
		@Test
		fun `DEFAULT_QUALITY is 1 point 0`() {
			MapSettingsState.DEFAULT_QUALITY shouldBe 1.0f
		}

		@Test
		fun `DEFAULT_MAX_HEAT is 10`() {
			MapSettingsState.DEFAULT_MAX_HEAT shouldBe 10
		}

		@Test
		fun `DEFAULT_VISIT_THRESHOLD is 15`() {
			MapSettingsState.DEFAULT_VISIT_THRESHOLD shouldBe 15
		}
	}

	@Nested
	inner class `data class behavior` {
		@Test
		fun `equality for identical states`() {
			MapSettingsState() shouldBe MapSettingsState()
		}

		@Test
		fun `inequality for different quality`() {
			MapSettingsState(quality = 2.0f) shouldNotBe MapSettingsState()
		}

		@Test
		fun `inequality for different maxHeatPoints`() {
			MapSettingsState(maxHeatPoints = 20) shouldNotBe MapSettingsState()
		}

		@Test
		fun `inequality for different visitThresholdSeconds`() {
			MapSettingsState(visitThresholdSeconds = 30) shouldNotBe MapSettingsState()
		}

		@Test
		fun `copy preserves unmodified fields`() {
			val original = MapSettingsState()
			val copied = original.copy(quality = 5.0f)
			copied.quality shouldBe 5.0f
			copied.maxHeatPoints shouldBe original.maxHeatPoints
			copied.visitThresholdSeconds shouldBe original.visitThresholdSeconds
		}

		@Test
		fun `custom construction overrides all fields`() {
			val state = MapSettingsState(
				quality = 0.5f,
				maxHeatPoints = 100,
				visitThresholdSeconds = 120,
			)
			state.quality shouldBe 0.5f
			state.maxHeatPoints shouldBe 100
			state.visitThresholdSeconds shouldBe 120
		}
	}
}
