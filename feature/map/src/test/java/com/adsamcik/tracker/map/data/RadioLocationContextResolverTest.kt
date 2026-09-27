package com.adsamcik.tracker.map.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadioLocationContextResolverTest {
	private val owner = SessionContextScope("tracking", "run", 7L, "boot", "clock", 3L)
	private val capture = RadioContextScope.Session(owner)
	private val ambient = RadioContextScope.Ambient(AmbientContextScope("boot", "clock", 3L))

	private fun radio(
		source: GeoSource = GeoSource.WIFI,
		scope: RadioContextScope = capture,
		elapsed: Long = 10_000_000_000L,
		eligible: Boolean = true,
		from: Long = elapsed,
	) = RadioContextInput(source, "radio", scope, elapsed, 1_000L, eligible, from)

	private fun fix(
		scope: RadioContextScope = capture,
		elapsed: Long = 10_000_000_000L,
		accuracy: Float = 12f,
		eligible: Boolean = true,
		id: String = "location",
	) = LocationContextInput(id, scope, elapsed, 500_000_000, 140_000_000, accuracy,
		eligible, observedWallTimeMs = 900L)

	@Test
	fun `wifi and cell independently gain context from an exact retained fix`() {
		val inputs = listOf(radio(), radio(source = GeoSource.CELL).copy(sourceEventId = "cell"))
		val points = RadioLocationContextResolver.resolve(inputs, listOf(fix()))
		assertEquals(listOf("radio", "cell"), points.map { it.radioSourceEventId })
		assertTrue(points.all { it.locationSourceEventId == "location" })
		assertEquals(50.0, points.first().latitude)
		assertEquals(14.0, points.first().longitude)
	}

	@Test
	fun `capture and independently authorized ambient never lend purpose authority`() {
		assertTrue(RadioLocationContextResolver.resolve(listOf(radio(scope = ambient)), listOf(fix())).isEmpty())
		assertTrue(RadioLocationContextResolver.resolve(listOf(radio()), listOf(fix(scope = ambient))).isEmpty())
		val points = RadioLocationContextResolver.resolve(listOf(radio(scope = ambient)), listOf(fix(scope = ambient)))
		assertEquals("location", points.single().locationSourceEventId)
	}

	@Test
	fun `physical run segment boot clock and epoch fence capture context`() {
		val mismatches = listOf(
			owner.copy(logicalTrackingId = "other"), owner.copy(serviceRunId = "other"),
			owner.copy(sessionSegmentId = 8L), owner.copy(bootId = "other"),
			owner.copy(clockDomainId = "other"), owner.copy(collectedDataEpoch = 4L),
		)
		mismatches.forEach { changed ->
			assertTrue(RadioLocationContextResolver.resolve(listOf(radio()),
				listOf(fix(scope = RadioContextScope.Session(changed)))).isEmpty())
		}
	}

	@Test
	fun `ambient matches only its shared boot clock and epoch`() {
		val original = ambient.owner
		listOf(original.copy(bootId = "other"), original.copy(clockDomainId = "other"),
			original.copy(collectedDataEpoch = 4L)).forEach { changed ->
			assertTrue(RadioLocationContextResolver.resolve(listOf(radio(scope = ambient)),
				listOf(fix(scope = RadioContextScope.Ambient(changed)))).isEmpty())
		}
	}

	@Test
	fun `wifi requires both qualified bracket endpoints except for an exact instant`() {
		val before = fix(elapsed = 8_000_000_000L, id = "before")
		val after = fix(elapsed = 12_000_000_000L, id = "after")
		assertTrue(RadioLocationContextResolver.resolve(listOf(radio()), listOf(before)).isEmpty())
		assertTrue(RadioLocationContextResolver.resolve(listOf(radio()), listOf(after)).isEmpty())
		val points = RadioLocationContextResolver.resolve(listOf(radio()), listOf(before, after))
		assertEquals("before", points.single().locationSourceEventId)
		assertEquals(2_000L, points.single().separationMillis)
		assertEquals("location", RadioLocationContextResolver.resolve(listOf(radio()),
			listOf(fix(), before, after)).single().locationSourceEventId)
	}

	@Test
	fun `wifi picks nearest observed endpoint without interpolation`() {
		val before = fix(elapsed = 7_000_000_000L, id = "before")
		val after = fix(elapsed = 12_000_000_000L, id = "after").copy(latitudeE7 = 600_000_000)
		val context = RadioLocationContextResolver.resolve(listOf(radio()), listOf(before, after)).single()
		assertEquals("after", context.locationSourceEventId)
		assertEquals(60.0, context.latitude)
		assertEquals(2_000L, context.separationMillis)
	}

	@Test
	fun `wifi both bracket endpoints must be within thirty seconds including boundary`() {
		val fact = radio(elapsed = 40_000_000_000L)
		val before = fix(elapsed = 10_000_000_000L, id = "before")
		val after = fix(elapsed = 70_000_000_000L, id = "after")
		assertEquals("before", RadioLocationContextResolver.resolve(listOf(fact), listOf(before, after))
			.single().locationSourceEventId)
		for (locations in listOf(listOf(before.copy(observedElapsedNanos = 9_999_999_999L), after),
			listOf(before, after.copy(observedElapsedNanos = 70_000_000_001L)))) {
			assertTrue(RadioLocationContextResolver.resolve(listOf(fact), locations).isEmpty())
		}
	}

	@Test
	fun `radio observation intervals require conservative full interval separation`() {
		val fact = radio(elapsed = 40_000_000_000L, from = 20_000_000_000L)
		val before = fix(elapsed = 10_000_000_000L, id = "before")
		val after = fix(elapsed = 50_000_000_000L, id = "after")
		val points = RadioLocationContextResolver.resolve(listOf(fact), listOf(before, after))
		assertEquals("before", points.single().locationSourceEventId)
		assertEquals(30_000L, points.single().separationMillis)
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact),
			listOf(before.copy(observedElapsedNanos = 9_999_999_999L), after)).isEmpty())
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact),
			listOf(before, after.copy(observedElapsedNanos = 50_000_000_001L))).isEmpty())
		// A fix inside an aggregate's interval does not locate the whole aggregate.
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact),
			listOf(fix(elapsed = 30_000_000_000L))).isEmpty())
	}

	@Test
	fun `cell uses only preceding fixes within sixty seconds`() {
		val fact = radio(source = GeoSource.CELL, elapsed = 70_000_000_000L)
		val before = fix(elapsed = 10_000_000_000L, id = "before")
		val future = fix(elapsed = 70_000_000_001L, id = "future")
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact), listOf(future)).isEmpty())
		val point = RadioLocationContextResolver.resolve(listOf(fact), listOf(before, future)).single()
		assertEquals("before", point.locationSourceEventId)
		assertEquals(60_000L, point.separationMillis)
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact),
			listOf(before.copy(observedElapsedNanos = 9_999_999_999L), future)).isEmpty())
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact.copy(observedFromElapsedNanos = 9_999_999_999L)),
			listOf(before)).isEmpty())
	}

	@Test
	fun `coarse finite accuracy and both wall uncertainties remain visible`() {
		val fact = radio().copy(wallTimeUncertaintyMs = 80L)
		val location = fix(accuracy = 1_200f).copy(wallTimeUncertaintyMs = 200L)
		val point = RadioLocationContextResolver.resolve(listOf(fact), listOf(location)).single()
		assertEquals(1_200f, point.horizontalAccuracyMeters)
		assertEquals(1_000L, point.observedWallTimeMs)
		assertEquals(900L, point.locationObservedWallTimeMs)
		assertEquals(80L, point.radioWallTimeUncertaintyMs)
		assertEquals(200L, point.locationWallTimeUncertaintyMs)
	}

	@Test
	fun `invalid accuracy is not repaired to a precise point`() {
		for (accuracy in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertTrue(RadioLocationContextResolver.resolve(listOf(radio()), listOf(fix(accuracy = accuracy))).isEmpty())
		}
		assertEquals(0f, RadioLocationContextResolver.resolve(listOf(radio()), listOf(fix(accuracy = 0f)))
			.single().horizontalAccuracyMeters)
	}

	@Test
	fun `equal time endpoint identities are deterministic for input order and both sources`() {
		val a = fix(id = "a").copy(latitudeE7 = 400_000_000)
		val b = fix(id = "b")
		for (source in listOf(GeoSource.WIFI, GeoSource.CELL)) {
			for (locations in listOf(listOf(a, b), listOf(b, a))) {
				assertEquals("a", RadioLocationContextResolver.resolve(listOf(radio(source = source)), locations)
					.single().locationSourceEventId)
			}
		}
	}

	@Test
	fun `retired or erased endpoints retract derived points without changing primary radio observations`() {
		val inputs = listOf(radio(), radio(source = GeoSource.CELL).copy(sourceEventId = "cell"))
		assertEquals(2, RadioLocationContextResolver.resolve(inputs, listOf(fix())).size)
		assertTrue(RadioLocationContextResolver.resolve(inputs, emptyList()).isEmpty())
		assertTrue(RadioLocationContextResolver.resolve(inputs, listOf(fix(eligible = false))).isEmpty())
		assertEquals(listOf("radio", "cell"), inputs.map { it.sourceEventId })
		assertEquals(1, RadioLocationContextResolver.resolve(listOf(inputs[0].copy(eligibleForMap = false), inputs[1]),
			listOf(fix())).size)
	}

	@Test
	fun `clock extremes cannot overflow delta or round separation downward`() {
		val fact = radio(source = GeoSource.CELL, elapsed = Long.MAX_VALUE)
		val location = fix(elapsed = Long.MAX_VALUE - 60_000_000_000L)
		assertEquals(60_000L, RadioLocationContextResolver.resolve(listOf(fact), listOf(location)).single().separationMillis)
		assertTrue(RadioLocationContextResolver.resolve(listOf(fact), listOf(fix(elapsed = 0L))).isEmpty())
		assertEquals(1L, RadioLocationContextResolver.resolve(listOf(radio(source = GeoSource.CELL)),
			listOf(fix(elapsed = 9_999_999_999L))).single().separationMillis)
	}
}
