package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailState
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.stats.api.value.EpochMs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Host UI regression source; no device/evaluator execution during assembly. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceOnlySavedReadingsComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `Steps-only detail displays its saved count without a Location trip`() {
        val snapshot = TrackingHistoryReadSnapshot(7, 11)
        val steps = StepsHistory(42, HistoryAvailability.AVAILABLE, HistoryEvidence.RECORDED,
            HistoryProductState.READY, StepsHistoryCoverage.COMPLETE)
        val capture = HistoryCapture.Exact(listOf(HistoryCaptureRevision(1, EpochMs(1_000), setOf(HistorySource.STEPS), emptySet())))
        val entry = SourceAwareHistoryPageEntry.StepsOnly(StepsOnlyHistoryEntry(TrackingHistoryEntryKey("steps"),
            EpochMs(1_000), EpochMs(2_000), StepsOnlyHistoryListState.AVAILABLE, listOf(1)))
        val member = LiveSessionHistorySnapshot(1, SessionHistoryQuery.Found(SessionHistory(1, capture,
            setOf(HistorySource.STEPS), steps, readSnapshot = snapshot)), ActivityHistoryQuery.NotFound,
            PressureSessionHistoryQuery.NotFound, readSnapshot = snapshot)
        composeRule.setContent { MaterialTheme {
            SourceOnlySavedReadingsContent(SourceHistoryDetailState.Loaded(SourceHistoryDetailSelection(entry, snapshot), listOf(member)))
        } }
        composeRule.onNodeWithText("42").assertIsDisplayed()
        composeRule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.trip_detail_steps)).assertIsDisplayed()
        composeRule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.trip_detail_distance)).assertDoesNotExist()
    }

    @Test fun `imported Pressure-only detail stays readable with no native segment`() {
        val history = PressureHistory(HistoryAvailability.UNAVAILABLE, HistoryEvidence.NONE,
            HistoryProductState.DEGRADED, PressureHistoryCoverage.NONE, emptyList(), setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE))
        val entry = SourceAwareHistoryPageEntry.PressureOnly(PressureOnlyHistoryEntry(TrackingHistoryEntryKey("pressure"),
            PressureHistoryOrigin.Imported(ImportedPressureHistoryIdentity("sha256:" + "a".repeat(64))),
            EpochMs(1_000), EpochMs(1_000), history, importRevision = 2))
        composeRule.setContent { MaterialTheme {
            SourceOnlySavedReadingsContent(SourceHistoryDetailState.Loaded(SourceHistoryDetailSelection(entry,
                TrackingHistoryReadSnapshot(7, 11))))
        } }
        composeRule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.trip_detail_pressure_title)).assertIsDisplayed()
    }
}
