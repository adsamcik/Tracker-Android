package com.adsamcik.tracker.tracker.source

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.junit.Test

class NonCancellationResultTest {
	@Test
	fun `coroutine cancellation is never converted to an operational failure`() {
		assertFailsWith<CancellationException> {
			runCatchingNonCancellation<Unit> { throw CancellationException("stop") }
		}
	}

	@Test
	fun `ordinary failures remain inspectable results`() {
		val result = runCatchingNonCancellation<Unit> { error("storage") }

		assertTrue(result.isFailure)
		assertEquals("storage", result.exceptionOrNull()?.message)
	}
}
