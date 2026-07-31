package com.adsamcik.tracker.shared.base.result

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException

@DisplayName("SafetyExtensions result helpers")
class SafetyExtensionsResultTest {
	@Test
	fun `returns successful result with value`() {
		val result = runCatchingCancellable { "success" }

		result.isSuccess shouldBe true
		result.getOrThrow() shouldBe "success"
	}

	@Test
	fun `returns failure with original exception`() {
		val exception = IllegalArgumentException("failure")

		val result = runCatchingCancellable<String> { throw exception }

		result.isFailure shouldBe true
		result.exceptionOrNull().shouldBeSameInstanceAs(exception)
	}

	@Test
	fun `rethrows cancellation`() {
		val cancellation = CancellationException("stop")

		val thrown = assertThrows<CancellationException> {
			runCatchingCancellable<String> { throw cancellation }
		}

		thrown.shouldBeSameInstanceAs(cancellation)
	}
}
