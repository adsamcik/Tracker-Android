package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Day presentation changes neither saved UTC readings nor original fact identity/calendar. */
class UnifiedHistoryDayWindowTest {
    @Test fun wifiReadingsHaveExactlyOneHalfOpenDayWithoutRewritingTheirFact() {
        val fact = AmbientWifiFact("wifi", AmbientWifiOrigin.LOCAL_DEVICE, 86_399_950, 86_400_050,
            86_400_051, 0, "America/Los_Angeles", AmbientWifiCoverage.COMPLETE, 2, 2, 0, 0, 0,
            -40, -70, -55.0, networks = listOf(
                WifiObservedNetwork(86_399_950, 1, "a", "A", 2412, -70),
                WifiObservedNetwork(86_400_050, 1, "b", "B", 2412, -40)))
        val snapshot = AmbientWifiReadResult.Snapshot(listOf(fact), emptyList(), AmbientWifiAvailability.AVAILABLE)
        val yesterday = HistorySection.BackgroundWifi(snapshot, HistoryObservationWindow(0, 86_400_000))
        val today = HistorySection.BackgroundWifi(snapshot, HistoryObservationWindow(86_400_000, 172_800_000))
        assertEquals(listOf(86_399_950L), yesterday.networks.map { it.observedTimeMs })
        assertEquals(listOf(86_400_050L), today.networks.map { it.observedTimeMs })
        assertEquals(1, yesterday.readingCount)
        assertEquals(1, today.readingCount)
        assertSame(fact, today.read.facts.single())
        assertEquals(0L, today.read.facts.single().structuralEpochDay)
        assertEquals("America/Los_Angeles", today.read.facts.single().storedZoneId)
    }

    @Test fun cellReadingsUseTheirOwnTimestampAndKeepOriginalSignalAndOwner() {
        val fact = AmbientCellFact("cell", AmbientCellOrigin.PORTABLE_IMPORT, 99, 100, 101, 0, "UTC",
            AmbientCellCoverage.UNVERIFIABLE, AmbientCellSubscriptionCompleteness.UNKNOWN, 2, 1,
            AmbientCellTechnologyMix(0, 0, 0, 0, 1, 1), AmbientCellQualityDistribution(2, 0, 0, 0, 0, 0),
            towers = listOf(CellObservedTower(99, 1, "a", CellHistoryTechnology.LTE, true, -70),
                CellObservedTower(100, 1, "b", CellHistoryTechnology.NR, false, -80)))
        val section = HistorySection.BackgroundCell(AmbientCellReadResult.Snapshot(listOf(fact), emptyList(),
            AmbientCellAvailability.AVAILABLE), HistoryObservationWindow(100, 200))
        assertEquals(listOf("b"), section.towers.map { it.identity })
        assertEquals(1, section.readingCount)
        assertSame(fact, section.read.facts.single())
    }

    @Test fun missingLegacyDetailsNeverBecomeAZeroReadingCount() {
        val wifi = AmbientWifiFact("wifi", AmbientWifiOrigin.LOCAL_DEVICE, 1, 2, 2, 0, "UTC",
            AmbientWifiCoverage.UNVERIFIABLE, 1, 1, 0, 0, 0, -40, -40, -40.0)
        val cell = AmbientCellFact("cell", AmbientCellOrigin.LOCAL_DEVICE, 1, 2, 2, 0, "UTC",
            AmbientCellCoverage.UNVERIFIABLE, AmbientCellSubscriptionCompleteness.UNKNOWN, 1, 1,
            AmbientCellTechnologyMix(0, 0, 0, 0, 1, 0), AmbientCellQualityDistribution(1, 0, 0, 0, 0, 0))
        assertNull(HistorySection.BackgroundWifi(AmbientWifiReadResult.Snapshot(listOf(wifi), emptyList(),
            AmbientWifiAvailability.AVAILABLE), HistoryObservationWindow(0, 100)).readingCount)
        assertNull(HistorySection.BackgroundCell(AmbientCellReadResult.Snapshot(listOf(cell), emptyList(),
            AmbientCellAvailability.AVAILABLE), HistoryObservationWindow(0, 100)).readingCount)
    }
}
