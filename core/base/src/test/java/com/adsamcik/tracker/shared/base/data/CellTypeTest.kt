package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CellType - cell network technology enum")
class CellTypeTest {

	@Test
	fun `has 7 entries`() {
		CellType.entries.size shouldBe 7
	}

	@Test
	fun `contains expected types`() {
		val names = CellType.entries.map { it.name }
		names shouldBe listOf("Unknown", "GSM", "CDMA", "WCDMA", "LTE", "NR", "None")
	}

	@Test
	fun `valueOf round-trips for all entries`() {
		CellType.entries.forEach { type ->
			CellType.valueOf(type.name) shouldBe type
		}
	}
}
