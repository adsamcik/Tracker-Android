package com.adsamcik.tracker.shared.preferences.type

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpeedFormatTest {

	@Nested
	inner class `enum values` {
		@Test
		fun `should have exactly three variants`() {
			SpeedFormat.entries.size shouldBe 3
		}

		@Test
		fun `should contain all expected variants`() {
			SpeedFormat.entries.map { it.name } shouldContainAll listOf(
				"Second", "Minute", "Hour"
			)
		}
	}

	@Nested
	inner class `ordinal ordering` {
		@Test
		fun `Second is first`() {
			SpeedFormat.Second.ordinal shouldBe 0
		}

		@Test
		fun `Minute is second`() {
			SpeedFormat.Minute.ordinal shouldBe 1
		}

		@Test
		fun `Hour is third`() {
			SpeedFormat.Hour.ordinal shouldBe 2
		}
	}

	@Nested
	inner class `valueOf lookup` {
		@Test
		fun `valueOf returns correct variant for each name`() {
			SpeedFormat.entries.forEach { format ->
				SpeedFormat.valueOf(format.name) shouldBe format
			}
		}

		@Test
		fun `valueOf throws for invalid name`() {
			assertThrows<IllegalArgumentException> {
				SpeedFormat.valueOf("Invalid")
			}
		}
	}
}
