package com.adsamcik.tracker.shared.base.process

import io.kotest.matchers.shouldBe
import java.util.UUID
import org.junit.Test

class ProcessIncarnationIdProviderTest {
	@Test
	fun `identity is generated once and remains stable for the provider lifetime`() {
		val expected = UUID.fromString("50f12fe7-e82f-4f6c-8be8-a400bb6c3642")
		var generationCount = 0
		val provider = ProcessIncarnationIdProvider {
			generationCount += 1
			expected
		}

		provider.current() shouldBe expected.toString()
		provider.current() shouldBe expected.toString()
		generationCount shouldBe 1
	}

	@Test
	fun `injectable constructor creates a UUID identity`() {
		val identity = ProcessIncarnationIdProvider().current()

		UUID.fromString(identity).toString() shouldBe identity
	}
}
