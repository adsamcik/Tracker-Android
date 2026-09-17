package com.adsamcik.tracker.shared.model.steps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StepsCounterDomainTokenTest {
	@Test
	fun `opaque token equality is stable while text remains redacted`() {
		val encoded = "sha256:${"a".repeat(64)}"
		val first = StepsCounterDomainToken.opaque(encoded)
		val second = StepsCounterDomainToken.opaque(encoded)

		assertEquals(first, second)
		assertEquals(first.hashCode(), second.hashCode())
		assertEquals("StepsCounterDomainToken", first.toString())
		assertFalse(first.toString().contains(encoded))
	}

	@Test
	fun `raw and malformed identifiers are rejected`() {
		assertFailsWith<IllegalArgumentException> {
			StepsCounterDomainToken.opaque("sensor-account-or-device-id")
		}
	}
}
