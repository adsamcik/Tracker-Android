package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LengthUnit - distance unit enum")
class LengthUnitTest {

	@Test
	fun `has 4 entries`() {
		LengthUnit.entries.size shouldBe 4
	}

	@Test
	fun `contains Meter`() {
		LengthUnit.valueOf("Meter") shouldBe LengthUnit.Meter
	}

	@Test
	fun `contains Kilometer`() {
		LengthUnit.valueOf("Kilometer") shouldBe LengthUnit.Kilometer
	}

	@Test
	fun `contains Mile`() {
		LengthUnit.valueOf("Mile") shouldBe LengthUnit.Mile
	}

	@Test
	fun `contains NauticalMile`() {
		LengthUnit.valueOf("NauticalMile") shouldBe LengthUnit.NauticalMile
	}
}
