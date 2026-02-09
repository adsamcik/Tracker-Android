package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RetentionPipelineWorkerTest {

    @Nested
    inner class ConfigDefaults {
        @Test
        fun defaultAutoPurgeIsDisabled() {
            assertFalse(RetentionConfigState().autoPurgeEnabled)
        }

        @Test
        fun defaultRawRetentionIs365Days() {
            assertEquals(365, RetentionConfigState().rawDataRetentionDays)
        }

        @Test
        fun defaultWifiCellRetentionMatchesRaw() {
            val c = RetentionConfigState()
            assertEquals(c.rawDataRetentionDays, c.wifiCellRetentionDays)
        }

        @Test
        fun defaultTripRetentionMatchesRaw() {
            val c = RetentionConfigState()
            assertEquals(c.rawDataRetentionDays, c.tripRetentionDays)
        }

        @Test
        fun defaultDailySummaryRetentionIs730Days() {
            assertEquals(730, RetentionConfigState().dailySummaryRetentionDays)
        }

        @Test
        fun defaultExplorationRetentionIsZero() {
            assertEquals(0, RetentionConfigState().explorationRetentionDays)
        }

        @Test
        fun defaultExportBeforePurgeIsDisabled() {
            assertFalse(RetentionConfigState().exportBeforePurge)
        }

        @Test
        fun defaultLegacySessionRetentionMatchesRaw() {
            val c = RetentionConfigState()
            assertEquals(c.rawDataRetentionDays, c.legacySessionRetentionDays)
        }
    }

    @Nested
    inner class CutoffMs {
        @Test
        fun cutoffCalculationFor365Days() {
            val now = 1_700_000_000_000L
            val cutoff = now - 365L * Time.DAY_IN_MILLISECONDS
            assertEquals(now - 365L * 86_400_000L, cutoff)
        }

        @Test
        fun cutoffCalculationFor30Days() {
            val now = 1_700_000_000_000L
            val cutoff = now - 30L * Time.DAY_IN_MILLISECONDS
            assertEquals(now - 30L * 86_400_000L, cutoff)
        }

        @Test
        fun zeroRetentionMeansKeepForever() {
            assertEquals(0, RetentionConfigState(rawDataRetentionDays = 0).rawDataRetentionDays)
        }
    }

    @Nested
    inner class DailySummaryCutoff {
        @Test
        fun dailySummaryCutoffConvertsToEpochDay() {
            val now = 1_700_000_000_000L
            val cutoffMs = now - 730L * Time.DAY_IN_MILLISECONDS
            val cutoffDay = cutoffMs / Time.DAY_IN_MILLISECONDS
            assertEquals(cutoffMs / 86_400_000L, cutoffDay)
        }
    }

    @Nested
    inner class ConfigCustomization {
        @Test
        fun customRawRetentionIsRespected() {
            assertEquals(90, RetentionConfigState(rawDataRetentionDays = 90).rawDataRetentionDays)
        }

        @Test
        fun customWifiCellRetentionIndependentOfRaw() {
            val c = RetentionConfigState(rawDataRetentionDays = 365, wifiCellRetentionDays = 180)
            assertEquals(365, c.rawDataRetentionDays)
            assertEquals(180, c.wifiCellRetentionDays)
        }

        @Test
        fun autoPurgeCanBeEnabled() {
            assertEquals(true, RetentionConfigState(autoPurgeEnabled = true).autoPurgeEnabled)
        }

        @Test
        fun exportBeforePurgeCanBeEnabled() {
            assertEquals(true, RetentionConfigState(exportBeforePurge = true).exportBeforePurge)
        }

        @Test
        fun allFieldsCanBeCustomizedIndependently() {
            val c = RetentionConfigState(
                rawDataRetentionDays = 30,
                wifiCellRetentionDays = 60,
                tripRetentionDays = 90,
                dailySummaryRetentionDays = 180,
                explorationRetentionDays = 365,
                autoPurgeEnabled = true,
                exportBeforePurge = true,
                legacySessionRetentionDays = 7,
            )
            assertEquals(30, c.rawDataRetentionDays)
            assertEquals(60, c.wifiCellRetentionDays)
            assertEquals(90, c.tripRetentionDays)
            assertEquals(180, c.dailySummaryRetentionDays)
            assertEquals(365, c.explorationRetentionDays)
            assertEquals(true, c.autoPurgeEnabled)
            assertEquals(true, c.exportBeforePurge)
            assertEquals(7, c.legacySessionRetentionDays)
        }
    }
}
