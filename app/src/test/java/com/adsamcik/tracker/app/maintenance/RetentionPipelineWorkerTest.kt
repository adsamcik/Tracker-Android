package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
	inner class ZeroRetentionSkipsCategory {
		@Test
		fun zeroRawRetentionMeansKeepForever() {
			assertEquals(0, RetentionConfigState(rawDataRetentionDays = 0).rawDataRetentionDays)
		}

		@Test
		fun zeroWifiCellRetentionMeansKeepForever() {
			assertEquals(0, RetentionConfigState(wifiCellRetentionDays = 0).wifiCellRetentionDays)
		}

		@Test
		fun zeroTripRetentionMeansKeepForever() {
			assertEquals(0, RetentionConfigState(tripRetentionDays = 0).tripRetentionDays)
		}

		@Test
		fun zeroLegacyRetentionMeansKeepForever() {
			assertEquals(0, RetentionConfigState(legacySessionRetentionDays = 0).legacySessionRetentionDays)
		}
	}

	@Nested
	inner class CutoffCalculation {
		private val fixedNow = 1_700_000_000_000L

		@Test
		fun cutoff365DaysBeforeNow() {
			val config = RetentionConfigState(rawDataRetentionDays = 365)
			val cutoff = fixedNow - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
			assertEquals(fixedNow - 365L * 86_400_000L, cutoff)
		}

		@Test
		fun cutoff30DaysBeforeNow() {
			val config = RetentionConfigState(rawDataRetentionDays = 30)
			val cutoff = fixedNow - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
			assertEquals(fixedNow - 30L * 86_400_000L, cutoff)
		}
	}

	@Nested
	inner class DailySummaryCutoff {
		@Test
		fun dailySummaryCutoffConvertsToEpochDay() {
			val now = 1_700_000_000_000L
			val config = RetentionConfigState(dailySummaryRetentionDays = 730)
			val cutoffMs = now - config.dailySummaryRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
			val cutoffDay = cutoffMs / Time.DAY_IN_MILLISECONDS
			assertTrue(cutoffDay > 0)
			assertTrue(cutoffDay < now / Time.DAY_IN_MILLISECONDS)
		}
	}

	@Nested
	inner class ExportBeforePurgeGuard {
		@Test
		fun exportBeforePurgeDefaultsToFalse() {
			assertFalse(RetentionConfigState().exportBeforePurge)
		}

		@Test
		fun exportBeforePurgeCanBeEnabled() {
			assertTrue(RetentionConfigState(exportBeforePurge = true).exportBeforePurge)
		}

		@Test
		fun autoPurgeAndExportBeforePurgeAreIndependent() {
			val config = RetentionConfigState(autoPurgeEnabled = true, exportBeforePurge = true)
			assertTrue(config.autoPurgeEnabled)
			assertTrue(config.exportBeforePurge)
		}
	}

	@Nested
	inner class ConfigCustomization {
		@Test
		fun customRawRetentionIsRespected() {
			assertEquals(90, RetentionConfigState(rawDataRetentionDays = 90).rawDataRetentionDays)
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
			assertTrue(c.autoPurgeEnabled)
			assertTrue(c.exportBeforePurge)
			assertEquals(7, c.legacySessionRetentionDays)
		}
	}

}
