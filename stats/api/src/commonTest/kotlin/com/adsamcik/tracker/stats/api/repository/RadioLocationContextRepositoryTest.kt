package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RadioLocationContextRepositoryTest {
	@Test
	fun `range and evidence bound cannot silently widen or truncate`() {
		for ((from, to) in listOf(-1L to 1L, 1L to 1L, 2L to 1L)) {
			assertFailsWith<IllegalArgumentException> { RadioLocationContextRequest(RadioContextSource.WIFI, from, to) }
		}
		for (limit in listOf(0, MAX_RADIO_CONTEXT_OBSERVATIONS + 1)) {
			assertFailsWith<IllegalArgumentException> { RadioLocationContextRequest(RadioContextSource.CELL, 0, 1, limit) }
		}
		assertEquals(Long.MAX_VALUE, RadioLocationContextRequest(RadioContextSource.WIFI, 0, Long.MAX_VALUE).toExclusiveMs)
	}

	@Test
	fun `missing Location and sibling gaps preserve the qualified radio observation`() {
		val fact = observation()
		val ready = RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(fact),
			setOf(RadioContextReadGap(RadioContextPurpose.AMBIENT, RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)))
		assertEquals(listOf(fact), ready.observations)
		assertEquals(OptionalRetainedLocationContext.Unlocated(RadioLocationContextGap.NO_COMPATIBLE_LOCATION), fact.location)
	}

	@Test
	fun `a single purpose dependency overflow preserves its independent sibling`() {
		for (overflowed in RadioContextPurpose.entries) {
			val sibling = observation().copy(purpose = RadioContextPurpose.entries.single { it != overflowed })
			val gap = RadioContextReadGap(overflowed, RadioContextReadGapReason.DEPENDENCY_LIMIT)
			val ready = RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(sibling), setOf(gap))
			assertEquals(listOf(sibling), ready.observations)
			assertEquals(setOf(gap), ready.gaps)
		}
	}

	@Test
	fun `independent purpose associations remain distinct without duplicating a purpose event`() {
		val capture = observation()
		val ambient = capture.copy(purpose = RadioContextPurpose.AMBIENT)
		assertEquals(2, RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(capture, ambient)).observations.size)
		assertFailsWith<IllegalArgumentException> {
			RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(capture, capture))
		}
		assertFailsWith<IllegalArgumentException> {
			RadioLocationContextReadResult.Ready(RadioContextSource.CELL, listOf(capture))
		}
	}

	@Test
	fun `coarse observed endpoints preserve distinct times and uncertainty without an accuracy cutoff`() {
		val location = RetainedLocationContext("location", 500_000_000, 140_000_000, 900, 200, 1_200f, 30_000)
		val fact = observation().copy(location = OptionalRetainedLocationContext.Located(location))
		assertEquals(1_000L, fact.observedWallTimeMs)
		assertEquals(80L, fact.wallTimeUncertaintyMs)
		assertEquals(900L, location.observedWallTimeMs)
		assertEquals(200L, location.wallTimeUncertaintyMs)
		assertEquals(1_200f, location.horizontalAccuracyMeters)
		for (accuracy in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
			assertFailsWith<IllegalArgumentException> { location.copy(horizontalAccuracyMeters = accuracy) }
		}
		assertTrue(location.separationMillis > 0)
	}

	private fun observation() = RadioContextObservation("radio", RadioContextPurpose.CAPTURE, 999, 1_000, 80,
		RadioContextSignalQuality.Wifi(WifiHistorySignalQuality(-40, -80, -60.0, 2)),
		OptionalRetainedLocationContext.Unlocated(RadioLocationContextGap.NO_COMPATIBLE_LOCATION))
}
