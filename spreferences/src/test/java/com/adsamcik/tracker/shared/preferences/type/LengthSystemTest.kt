package com.adsamcik.tracker.shared.preferences.type

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LengthSystemTest {

	@Nested
	inner class `enum values` {
		@Test
		fun `should have exactly five variants`() {
			LengthSystem.entries.size shouldBe 5
		}

		@Test
		fun `should contain all expected variants`() {
			LengthSystem.entries.map { it.name } shouldContainAll listOf(
				"Metric", "Imperial", "AncientRoman", "Sailing", "Flying"
			)
		}
	}

	@Nested
	inner class `ordinal ordering` {
		@Test
		fun `Metric is first`() {
			LengthSystem.Metric.ordinal shouldBe 0
		}

		@Test
		fun `Imperial is second`() {
			LengthSystem.Imperial.ordinal shouldBe 1
		}

		@Test
		fun `AncientRoman is third`() {
			LengthSystem.AncientRoman.ordinal shouldBe 2
		}

		@Test
		fun `Sailing is fourth`() {
			LengthSystem.Sailing.ordinal shouldBe 3
		}

		@Test
		fun `Flying is fifth`() {
			LengthSystem.Flying.ordinal shouldBe 4
		}
	}

	@Nested
	inner class `valueOf lookup` {
		@Test
		fun `valueOf returns correct variant for each name`() {
			LengthSystem.entries.forEach { system ->
				LengthSystem.valueOf(system.name) shouldBe system
			}
		}

		@Test
		fun `valueOf throws for invalid name`() {
			assertThrows<IllegalArgumentException> {
				LengthSystem.valueOf("Invalid")
			}
		}
	}
}
