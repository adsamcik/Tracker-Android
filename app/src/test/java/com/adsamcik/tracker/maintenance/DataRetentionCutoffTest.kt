package com.adsamcik.tracker.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class DataRetentionCutoffTest {
    @Test
    fun `computeCutoffMillis matches expected for 1-2-3-5-10 years`() {
        val now = 1_700_000_000_000L // fixed reference time for deterministic test
        val year = 365L * 24L * 60L * 60L * 1000L
        val cases = listOf(1, 2, 3, 5, 10)
        cases.forEach { y ->
            val expected = now - (year * y)
            val actual = DataRetentionWorker.computeCutoffMillis(y, now)
            assertEquals("cutoff for $y years", expected, actual)
        }
    }

    @Test
    fun `computeCutoffMillis returns minimum value for keep forever`() {
        val now = 1_700_000_000_000L
        assertEquals(Long.MIN_VALUE, DataRetentionWorker.computeCutoffMillis(0, now))
    }
}
