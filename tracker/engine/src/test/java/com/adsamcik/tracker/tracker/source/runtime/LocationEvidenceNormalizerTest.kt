package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.testing.fake.FakeLocationSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationEvidenceNormalizerTest {
	@Test
	fun `missing provider elapsed time is not replaced by callback receipt time`() {
		val location = location(elapsedNanos = 0L)

		assertEquals(null, location.qualifiedObservedElapsedRealtimeNanos(receivedElapsedRealtimeNanos = 10L))
	}

	@Test
	fun `future provider elapsed time is rejected before admission`() {
		val location = location(elapsedNanos = 11L)

		assertEquals(null, location.qualifiedObservedElapsedRealtimeNanos(receivedElapsedRealtimeNanos = 10L))
	}

	@Test
	fun `provider elapsed time at callback receipt is qualified`() {
		val location = location(elapsedNanos = 10L)

		assertEquals(10L, location.qualifiedObservedElapsedRealtimeNanos(receivedElapsedRealtimeNanos = 10L))
	}

	@Test
	fun `sorts a fused batch by provider event time with stable ties`() {
		val late = location("late", 3_000L, 300L)
		val firstTie = location("first-tie", 1_000L, 200L)
		val early = location("early", 1_000L, 100L)
		val secondTie = location("second-tie", 1_000L, 200L)

		val sorted = normalizeLocationBatch(listOf(late, firstTie, early, secondTie))

		assertEquals(listOf("early", "first-tie", "second-tie", "late"), sorted.map { it.provider })
	}

	@Test
	fun `rejects invalid coordinates while retaining missing accuracy`() {
		val valid = location("gps", 1_000L, 1L)
		assertTrue(valid.isValidLocationEvidence())

		val invalidCoordinate = location("gps", 1_000L, 1L).apply { latitude = Double.NaN }
		assertFalse(invalidCoordinate.isValidLocationEvidence())

		val missingAccuracy = location("gps", 1_000L, 1L).apply { removeAccuracy() }
		assertTrue(missingAccuracy.isValidLocationEvidence())
	}

	@Test
	fun `deduplicates only identical provider observations after event-time sorting`() {
		val original = location("gps", 2_000L, 200L)
		val identical = location("gps", 2_000L, 200L)
		val sameTimeDifferentCoordinate = location("gps", 2_000L, 200L).apply { latitude = 50.0001 }

		val normalized = normalizeLocationBatch(listOf(identical, sameTimeDifferentCoordinate, original))

		assertEquals(2, normalized.size)
		assertEquals(listOf(50.0, 50.0001), normalized.map { it.latitude })
	}


    @Test
    fun `normalization keeps coordinates and original times while clearing invalid optional values`() {
        val original = location("gps", 2_000L, 300L).apply {
            accuracy = Float.NaN
            altitude = Double.NaN
            verticalAccuracyMeters = Float.POSITIVE_INFINITY
            speed = Float.NaN
            speedAccuracyMetersPerSecond = Float.NaN
            bearingAccuracyDegrees = Float.NaN
        }
        val clean = normalizeLocationBatch(listOf(original)).single()
        assertEquals(original.latitude, clean.latitude)
        assertEquals(original.longitude, clean.longitude)
        assertEquals(300L, clean.time)
        assertEquals(2_000L, clean.elapsedRealtimeNanos)
        assertFalse(clean.hasAccuracy())
        assertFalse(clean.hasAltitude())
        assertFalse(clean.hasVerticalAccuracy())
        assertFalse(clean.hasSpeed())
        assertFalse(clean.hasSpeedAccuracy())
        assertFalse(clean.hasBearing())
        assertFalse(clean.hasBearingAccuracy())
        assertTrue(original.hasAccuracy())
        assertTrue(original.accuracy.isNaN())
    }

    @Test
    @Config(sdk = [28])
    fun `compat normalization removes invalid accuracy extras on supported older Android`() {
        val original = location("gps", 2_000L, 300L).apply {
            verticalAccuracyMeters = Float.NaN
            speedAccuracyMetersPerSecond = Float.POSITIVE_INFINITY
            bearingAccuracyDegrees = Float.NaN
        }
        val clean = normalizeLocationBatch(listOf(original)).single()
        assertEquals(5f, clean.accuracy)
        assertFalse(clean.hasVerticalAccuracy())
        assertFalse(clean.hasSpeedAccuracy())
        assertFalse(clean.hasBearingAccuracy())
    }

	private fun location(
		provider: String = "gps",
		elapsedNanos: Long,
		wallTimeMs: Long = 1L,
	) =
		FakeLocationSource.createLocation(
			lat = 50.0,
			lon = 14.0,
			accuracy = 5f,
			time = wallTimeMs,
			provider = provider,
		).apply {
			elapsedRealtimeNanos = elapsedNanos
		}
}
