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
	fun `sorts a fused batch by provider event time with stable ties`() {
		val late = location("late", 3_000L, 300L)
		val firstTie = location("first-tie", 1_000L, 200L)
		val early = location("early", 1_000L, 100L)
		val secondTie = location("second-tie", 1_000L, 200L)

		val sorted = normalizeLocationBatch(listOf(late, firstTie, early, secondTie))

		assertEquals(listOf("early", "first-tie", "second-tie", "late"), sorted.map { it.provider })
	}

	@Test
	fun `rejects invalid coordinates and missing accuracy`() {
		val valid = location("gps", 1_000L, 1L)
		assertTrue(valid.isValidLocationEvidence())

		val invalidCoordinate = location("gps", 1_000L, 1L).apply { latitude = Double.NaN }
		assertFalse(invalidCoordinate.isValidLocationEvidence())

		val missingAccuracy = location("gps", 1_000L, 1L).apply { removeAccuracy() }
		assertFalse(missingAccuracy.isValidLocationEvidence())
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

	private fun location(provider: String, elapsedNanos: Long, wallTimeMs: Long) =
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
