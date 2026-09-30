package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.*
import kotlin.test.*
import org.junit.Test

class RetainedRadioContextGroupsTest {
    @Test fun `two purpose associations never double a single purpose scan`() {
        val ambient = group("one")
        val capture = ambient.copy(purpose = RadioContextPurpose.CAPTURE,
            input = ambient.input.copy(scope = RadioContextScope.Session(SessionContextScope("logical", "run", 1, "boot", "boot", 0))))
        val gaps = mutableSetOf<RadioContextReadGap>()
        val result = consolidateRetainedRadioGroups(listOf(ambient, ambient, capture), gaps)
        assertEquals(2, result.size)
        assertEquals(setOf(RadioContextPurpose.CAPTURE, RadioContextPurpose.AMBIENT), result.map { it.purpose }.toSet())
        assertTrue(gaps.isEmpty())
    }
    @Test fun `disjoint qualified native groups combine once with their full observation interval`() {
        val earlier = group("one").copy(input = group("one").input.copy(observedFromElapsedNanos = 5))
        val later = group("two").copy(input = group("two").input.copy(observedElapsedNanos = 20, observedWallTimeMs = 2_000))
        val result = consolidateRetainedRadioGroups(listOf(earlier, later), mutableSetOf()).single()
        assertEquals(5L, result.input.observedFromElapsedNanos)
        assertEquals(20L, result.input.observedElapsedNanos)
        assertEquals(2, (result.quality as RadioContextSignalQuality.Wifi).value.sampleCount)
    }
    @Test fun `coverage-only reference cannot multiply its aggregate or adopt an incompatible owner`() {
        val first = group("owner")
        val repeated = first.copy(input = first.input.copy(observedElapsedNanos = 20, observedWallTimeMs = 2_000))
        val result = consolidateRetainedRadioGroups(listOf(first, repeated), mutableSetOf()).single()
        assertEquals(1, (result.quality as RadioContextSignalQuality.Wifi).value.sampleCount)
        val gaps = mutableSetOf<RadioContextReadGap>()
        val wrongBoot = repeated.copy(input = repeated.input.copy(scope = RadioContextScope.Ambient(AmbientContextScope("other", "other", 0))))
        assertTrue(consolidateRetainedRadioGroups(listOf(first, wrongBoot), gaps).isEmpty())
        assertEquals(setOf(RadioContextReadGap(RadioContextPurpose.AMBIENT,
            RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)), gaps)
    }
    private fun group(owner: String) = QualifiedRetainedRadio(RadioContextPurpose.AMBIENT,
        RadioContextInput(RadioContextSource.WIFI, "event", RadioContextScope.Ambient(AmbientContextScope("boot", "boot", 0)),
            10, 1_000, true), 1_000, RadioContextSignalQuality.Wifi(WifiHistorySignalQuality(-40, -40, -40.0, 1)), owner)
}
