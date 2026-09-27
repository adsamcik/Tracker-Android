package com.adsamcik.tracker.tracker.source.runtime

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CellRefreshResponsesTest {
	@Test
	fun `each owned SIM must respond once within original deadline`() {
		val responses = CellRefreshResponses(setOf(1, 2), 20_000L)

		assertFalse(responses.record(9, 1_000L))
		assertFalse(responses.record(1, 1_000L))
		assertFalse(responses.record(1, 2_000L))
		assertFalse(responses.record(2, 20_001L))
		assertTrue(responses.record(2, 20_000L))
		assertFalse(responses.record(2, 20_000L))
	}

	@Test
	fun `default modem scope is owned explicitly and empty scope never succeeds`() {
		assertTrue(CellRefreshResponses(setOf(null), 20_000L).record(null, 1_000L))
		assertFalse(CellRefreshResponses(emptySet(), 20_000L).record(null, 1_000L))
	}
}
