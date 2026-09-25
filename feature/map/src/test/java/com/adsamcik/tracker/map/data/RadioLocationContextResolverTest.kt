package com.adsamcik.tracker.map.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadioLocationContextResolverTest {
	private val owner = SessionContextScope("tracking", "run", 7L, "boot", "clock", 3L)

	private fun radio(
		source: GeoSource = GeoSource.WIFI,
		scope: RadioContextScope = RadioContextScope.Session(owner),
		elapsed: Long = 10_000_000_000L,
		eligible: Boolean = true,
	) = RadioContextInput(source, "radio", scope, elapsed, 1_000L, eligible)

	private fun fix(
		scope: SessionContextScope = owner,
		elapsed: Long = 10_000_000_000L,
		accuracy: Float = 12f,
		eligible: Boolean = true,
		id: String = "location",
	) = LocationContextInput(id, scope, elapsed, 500_000_000, 140_000_000, accuracy, eligible)

	@Test
	fun `wifi and cell independently gain context from an exact retained session fix`() {
		val inputs = listOf(radio(), radio(source = GeoSource.CELL).copy(sourceEventId = "cell"))
		val points = RadioLocationContextResolver.resolve(inputs, listOf(fix()))

		assertEquals(listOf("radio", "cell"), points.map { it.radioSourceEventId })
		assertTrue(points.all { it.locationSourceEventId == "location" })
		assertEquals(50.0, points.first().latitude)
		assertEquals(14.0, points.first().longitude)
	}

	@Test
	fun `sessionless ambient radio never borrows a session fix`() {
		assertTrue(RadioLocationContextResolver.resolve(
			listOf(radio(scope = RadioContextScope.Ambient)), listOf(fix()),
		).isEmpty())
	}

	@Test
	fun `physical run segment boot clock and data epoch all fence context`() {
		val mismatches = listOf(
			owner.copy(logicalTrackingId = "other"),
			owner.copy(serviceRunId = "other"),
			owner.copy(sessionSegmentId = 8L),
			owner.copy(bootId = "other"),
			owner.copy(clockDomainId = "other"),
			owner.copy(collectedDataEpoch = 4L),
		)
		mismatches.forEach { changed ->
			assertTrue(RadioLocationContextResolver.resolve(listOf(radio()), listOf(fix(scope = changed))).isEmpty())
		}
	}

	@Test
	fun `deleted or unauthorized evidence never contributes context`() {
		assertTrue(RadioLocationContextResolver.resolve(listOf(radio(eligible = false)), listOf(fix())).isEmpty())
		assertTrue(RadioLocationContextResolver.resolve(listOf(radio()), listOf(fix(eligible = false))).isEmpty())
	}

	@Test
	fun `nearest accepted fix is bounded by time and accuracy`() {
		val points = RadioLocationContextResolver.resolve(
			listOf(radio()),
			listOf(
				fix(elapsed = 8_000_000_000L, id = "before"),
				fix(elapsed = 12_000_000_000L, id = "after"),
				fix(elapsed = 10_000_000_000L, accuracy = 101f, id = "inaccurate"),
			),
		)
		assertEquals("before", points.single().locationSourceEventId)
		assertEquals(2_000L, points.single().separationMillis)
		assertTrue(RadioLocationContextResolver.resolve(
			listOf(radio()), listOf(fix(elapsed = 41_000_000_000L)),
		).isEmpty())
	}
}
