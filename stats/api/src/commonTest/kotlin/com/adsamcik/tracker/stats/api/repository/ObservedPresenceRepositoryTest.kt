package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservedPresenceRepositoryTest {
	@Test
	fun `bounds preserve an antimeridian crossing viewport`() {
		val bounds = ObservedPresenceBounds(north = 20.0, east = -170.0, south = -20.0, west = 170.0)

		assertTrue(bounds.crossesAntimeridian)
	}

	@Test
	fun `ordinary bounds do not cross the antimeridian`() {
		val bounds = ObservedPresenceBounds(north = 51.0, east = 15.0, south = 49.0, west = 13.0)

		assertFalse(bounds.crossesAntimeridian)
	}

	@Test
	fun `request requires a non-empty half-open window`() {
		assertFailsWith<IllegalArgumentException> {
			ObservedPresenceRequest(
				fromMs = 1_000L,
				toMsExclusive = 1_000L,
				preferredResolutionM = 100,
				maxCells = 1_000,
			)
		}
	}
}
