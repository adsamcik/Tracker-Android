package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.ui.MeasuredHistoryContent
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicInteger

/** Real lazy Compose/paging rendering; saved payloads and page responses are fixtures. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredHistoryContentComposeTest {
	@get:Rule val rule = createComposeRule()
	private val snapshot = TrackingHistoryReadSnapshot(0L, 1L)
	private val zone = ZoneId.of("Europe/Prague")
	private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

	@Test fun `sessionless Wi-Fi shows saved values and expansion without invented optional fields`() {
		val row = wifi("wifi:one", 1_700_000_000_000L, "Cafe", null)
		show(listOf(HistoryPagingItem.Row(row, snapshot)))
		rule.onNodeWithText("Cafe").assertIsDisplayed()
		rule.onNodeWithText(context.getString(R.string.radio_detail_signal_dbm, -45)).assertIsDisplayed()
		val card = rule.onNodeWithTag("history_measurement_wifi:one")
		card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
			context.getString(R.string.history_show_details))).performClick()
		card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
			context.getString(R.string.history_hide_details)))
		rule.onNodeWithText(context.getString(R.string.radio_detail_frequency_mhz, 2412)).assertIsDisplayed()
		rule.onNodeWithText("null").assertDoesNotExist()
	}

	@Test fun `older observations remain reachable beyond the old recent-trip list`() {
		val items = (0..124).map { index -> HistoryPagingItem.Row(
			wifi("wifi:$index", 1_700_000_000_000L - index * 86_400_000L, "Network $index", null), snapshot) }
		show(items)
		rule.onNodeWithTag("measured_history").performScrollToIndex(100)
		rule.onNodeWithTag("history_measurement_wifi:100").assertIsDisplayed()
		rule.onNodeWithText("Network 100").assertIsDisplayed()
	}

	@Test fun `equal UTC times use the displayed local day without hiding distinct readings`() {
		val time = LocalDate.of(2026, 10, 8).atStartOfDay(zone).toInstant().toEpochMilli()
		show(listOf(HistoryPagingItem.Row(wifi("first", time, "First", null), snapshot),
			HistoryPagingItem.Row(wifi("second", time, "Second", null), snapshot)))
		val locale = context.resources.configuration.locales[0]
		val dayText = LocalDate.of(2026, 10, 8).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
		rule.onNodeWithText(dayText).assertIsDisplayed()
		rule.onNodeWithText("First").assertIsDisplayed()
		rule.onNodeWithText("Second").assertIsDisplayed()
	}

	@Test fun `a genuine crossing Steps window expands to its original dates`() {
		val start = LocalDate.of(2026, 10, 7).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
		val end = LocalDate.of(2026, 10, 8).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
		val row = UnifiedHistoryRow(HistoryPosition(start, "local:steps", "steps:one"), end,
			setOf(HistoryOrigin.LOCAL), HistoryPurpose.BACKGROUND, sections = emptyList(),
			measurement = HistoryMeasurement.Steps(StepsHistory(42L, HistoryAvailability.AVAILABLE,
				HistoryEvidence.RECORDED, HistoryProductState.READY, StepsHistoryCoverage.COMPLETE)))
		show(listOf(HistoryPagingItem.Row(row, snapshot)))
		val locale = context.resources.configuration.locales[0]
		val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
		val card = rule.onNodeWithTag("history_measurement_steps:one")
		val compactFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
		card.assert(hasText(java.time.Instant.ofEpochMilli(end).atZone(zone).format(compactFormat), substring = true))
		card.performClick().assert(hasText(java.time.Instant.ofEpochMilli(end).atZone(zone).format(format), substring = true))
		rule.onNodeWithText("42").assertIsDisplayed()
	}

	@Test fun `source Retry preserves the sibling measurement and refreshes the actual Pager`() {
		val loads = AtomicInteger()
		val saved = HistoryPagingItem.Row(wifi("kept", 1_700_000_000_000L, "Kept Wi-Fi", null), snapshot)
		val problem = HistoryPagingItem.PageProblems(setOf(HistoryProblem(HistorySource.CELL,
			HistoryProblemReason.STORAGE_UNAVAILABLE)), snapshot, null)
		val flow = Pager(PagingConfig(pageSize = 20), pagingSourceFactory = {
			object : PagingSource<HistoryContinuation, HistoryPagingItem>() {
				override suspend fun load(params: LoadParams<HistoryContinuation>): LoadResult<HistoryContinuation, HistoryPagingItem> {
					val count = loads.incrementAndGet()
					return LoadResult.Page(if (count == 1) listOf(saved, problem) else listOf(saved), null, null)
				}
				override fun getRefreshKey(state: PagingState<HistoryContinuation, HistoryPagingItem>): HistoryContinuation? = null
			}
		}).flow
		show(flow)
		rule.waitUntil(5_000L) { loads.get() == 1 }
		rule.onNodeWithText("Kept Wi-Fi").assertIsDisplayed()
		rule.onNodeWithText(context.getString(R.string.action_retry)).performClick()
		rule.waitUntil(5_000L) { loads.get() >= 2 }
		rule.onNodeWithText("Kept Wi-Fi").assertIsDisplayed()
		rule.onNodeWithText(context.getString(R.string.action_retry)).assertDoesNotExist()
	}

	@Test fun `ordinary saved Location needs no optional accuracy to remain visible`() {
		val row = UnifiedHistoryRow(HistoryPosition(1_700_000_000_000L, "legacy:location", "location:one"), null,
			setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN, sections = emptyList(), measurement =
			HistoryMeasurement.Location(HistoryLocationPoint(1_700_000_000_000L, 500_000_000,
				140_000_000, null, null, 1L), HistoryLocationCoverage.LEGACY_UNVERIFIABLE))
		show(listOf(HistoryPagingItem.Row(row, snapshot)))
		rule.onNodeWithTag("history_measurement_location:one").assertIsDisplayed().performClick()
		rule.onNodeWithText("null").assertDoesNotExist()
	}

    @Test fun `legacy Activity renders its saved observation without a duration`() {
        val row = UnifiedHistoryRow(HistoryPosition(1_700_000_000_000L, "legacy:activity_legacy", "legacy:activity"), null,
            setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN, sections = emptyList(),
            measurement = HistoryMeasurement.LegacyActivity(99, 65, true))
        show(listOf(HistoryPagingItem.Row(row, snapshot)))
        val card = rule.onNodeWithTag("history_measurement_legacy:activity")
        card.assertIsDisplayed().performClick()
        card.assert(hasText(context.getString(com.adsamcik.tracker.shared.base.R.string.activity_unknown)))
        val locale = context.resources.configuration.locales[0]
        card.assert(hasText(java.text.NumberFormat.getPercentInstance(locale).format(0.65)))
        card.assert(hasText("\u2192", substring = true).not())
    }

    @Test fun `legacy Pressure expansion keeps its mean and range without an invented duration`() {
        val row = UnifiedHistoryRow(HistoryPosition(1_700_000_000_000L, "legacy:pressure_legacy", "legacy:pressure"), null,
            setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN, sections = emptyList(), measurement =
            HistoryMeasurement.LegacyPressure(1_010f, 4, 1_000f, 1_020f, 2f, 12f, 10L, 20L))
        show(listOf(HistoryPagingItem.Row(row, snapshot)))
        val card = rule.onNodeWithTag("history_measurement_legacy:pressure")
        card.assertIsDisplayed().performClick()
        val locale = context.resources.configuration.locales[0]
        card.assert(hasText(String.format(locale, "%.1f hPa", 1_010f)))
        card.assert(hasText(String.format(locale, "%.1f\u2013%.1f hPa", 1_000f, 1_020f)))
        card.assert(hasText("\u2192", substring = true).not())
    }

    @Test fun `Activity clock discontinuity renders both original wall times without normalization`() {
        val start = LocalDate.of(2026, 10, 8).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val end = start - 3_600_000L
        val band = com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment.Band("UTC",
            com.adsamcik.tracker.stats.api.value.EpochMs(start), com.adsamcik.tracker.stats.api.value.EpochMs(end), 0L, 0L,
            com.adsamcik.tracker.stats.api.repository.ActivityHistoryType.WALKING,
            com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism.TRANSITION, null,
            com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence.TransitionSignal,
            com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity.DISCONTINUITY_DETECTED, 500L)
        val row = UnifiedHistoryRow(HistoryPosition(start, "local:activity", "clock-change"), end,
            setOf(HistoryOrigin.LOCAL), HistoryPurpose.CAPTURE, sections = emptyList(), measurement = HistoryMeasurement.Activity(band))
        show(listOf(HistoryPagingItem.Row(row, snapshot)))
        val locale = context.resources.configuration.locales[0]
        val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val card = rule.onNodeWithTag("history_measurement_clock-change")
        card.assert(hasText(java.time.Instant.ofEpochMilli(start).atZone(zone).format(format), substring = true))
        card.assert(hasText(java.time.Instant.ofEpochMilli(end).atZone(zone).format(format), substring = true))
        card.performClick()
        val expanded = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        card.assert(hasText(java.time.Instant.ofEpochMilli(end).atZone(zone).format(expanded), substring = true))
    }

	private fun show(items: List<HistoryPagingItem>) = show(flowOf(PagingData.from(items)))
	private fun show(flow: Flow<PagingData<HistoryPagingItem>>) {
		rule.setContent { MaterialTheme(colorScheme = lightColorScheme()) {
			MeasuredHistoryContent(flow.collectAsLazyPagingItems(), zone)
		} }
	}
    @Test fun `repeated source problems share one current notice while valid sibling readings remain`() {
        val problem = HistoryProblem(HistorySource.CELL, HistoryProblemReason.STORAGE_UNAVAILABLE)
        show(listOf(
            HistoryPagingItem.Row(wifi("kept-one", 1_700_000_000_000L, "First kept", null), snapshot),
            HistoryPagingItem.PageProblems(setOf(problem), snapshot, null),
            HistoryPagingItem.Row(wifi("kept-two", 1_699_999_999_000L, "Second kept", null), snapshot),
            HistoryPagingItem.PageProblems(setOf(problem), snapshot, null),
            HistoryPagingItem.PageProblems(setOf(problem), snapshot, null),
        ))
        rule.onNodeWithText("First kept").assertIsDisplayed()
        rule.onNodeWithText("Second kept").assertIsDisplayed()
        rule.onAllNodesWithText(context.getString(R.string.action_retry)).assertCountEquals(1)
    }

    @Test fun `different source problems each retain their own recovery action`() {
        show(listOf(
            HistoryPagingItem.PageProblems(setOf(HistoryProblem(HistorySource.CELL,
                HistoryProblemReason.STORAGE_UNAVAILABLE)), snapshot, null),
            HistoryPagingItem.PageProblems(setOf(HistoryProblem(HistorySource.WIFI,
                HistoryProblemReason.INTEGRITY_UNAVAILABLE)), snapshot, null),
        ))
        rule.onAllNodesWithText(context.getString(R.string.action_retry)).assertCountEquals(2)
    }

    @Test fun `different reasons for the same source still use one visible recovery action`() {
        show(listOf(
            HistoryPagingItem.Row(wifi("kept", 1_700_000_000_000L, "Kept reading", null), snapshot),
            HistoryPagingItem.PageProblems(setOf(
                HistoryProblem(HistorySource.CELL, HistoryProblemReason.STORAGE_UNAVAILABLE),
                HistoryProblem(HistorySource.CELL, HistoryProblemReason.INTEGRITY_UNAVAILABLE),
            ), snapshot, null),
            HistoryPagingItem.PageProblems(setOf(
                HistoryProblem(HistorySource.CELL, HistoryProblemReason.DEPENDENCY_OVERFLOW),
                HistoryProblem(HistorySource.WIFI, HistoryProblemReason.STORAGE_UNAVAILABLE),
            ), snapshot, null),
        ))
        rule.onNodeWithText("Kept reading").assertIsDisplayed()
        rule.onAllNodesWithText(context.getString(R.string.action_retry)).assertCountEquals(2)
    }

    @Test fun `an empty incomplete frontier stays pending instead of claiming no saved readings`() {
        show(flowOf(PagingData.from(emptyList<HistoryPagingItem>(), sourceLoadStates = LoadStates(
            refresh = LoadState.NotLoading(false), prepend = LoadState.NotLoading(true), append = LoadState.NotLoading(false)))))
        rule.onNodeWithText(context.getString(R.string.history_measurements_empty)).assertDoesNotExist()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
    }

    @Test fun `a clean exhausted empty history shows its genuine empty state`() {
        show(flowOf(PagingData.from(emptyList<HistoryPagingItem>(), sourceLoadStates = LoadStates(
            refresh = LoadState.NotLoading(false), prepend = LoadState.NotLoading(true), append = LoadState.NotLoading(true)))))
        rule.onNodeWithText(context.getString(R.string.history_measurements_empty)).assertIsDisplayed()
    }

    @Test fun `an empty append error retains Retry without an empty claim`() {
        show(flowOf(PagingData.from(emptyList<HistoryPagingItem>(), sourceLoadStates = LoadStates(
            refresh = LoadState.NotLoading(false), prepend = LoadState.NotLoading(true),
            append = LoadState.Error(IllegalStateException("Fixture storage failure"))))))
        rule.onNodeWithText(context.getString(R.string.history_measurements_empty)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed()
    }

    @Test fun `empty nonterminal pages advance to an older valid reading without a scroll target`() {
        val loads = AtomicInteger()
        val range = HistoryRange(0L, Long.MAX_VALUE, zone.id, mode = HistoryBrowseMode.OBSERVATIONS)
        val frontier = (1..3).map { index -> HistoryContinuation(range,
            HistoryPosition(1_700_000_000_000L - index, "local:ambient_wifi", "rejected:$index")) }
        val saved = HistoryPagingItem.Row(wifi("older-kept", 1_699_999_999_000L,
            "Older kept Wi-Fi", null), snapshot)
        val flow = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false), pagingSourceFactory = {
            object : PagingSource<HistoryContinuation, HistoryPagingItem>() {
                override suspend fun load(params: LoadParams<HistoryContinuation>): LoadResult<HistoryContinuation, HistoryPagingItem> {
                    loads.incrementAndGet()
                    return when (params.key) {
                        null -> LoadResult.Page(emptyList(), null, frontier[0])
                        frontier[0] -> LoadResult.Page(emptyList(), null, frontier[1])
                        frontier[1] -> LoadResult.Page(emptyList(), null, frontier[2])
                        frontier[2] -> LoadResult.Page(listOf(saved), null, null)
                        else -> error("Unexpected measured History continuation")
                    }
                }
                override fun getRefreshKey(state: PagingState<HistoryContinuation, HistoryPagingItem>): HistoryContinuation? = null
            }
        }).flow
        show(flow)
        // No visible item exists to scroll to on the first three pages. The real Pager must advance.
        rule.waitUntil(5_000L) { loads.get() >= 4 }
        rule.onNodeWithTag("history_measurement_older-kept").assertIsDisplayed()
        rule.onNodeWithText("Older kept Wi-Fi").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.history_measurements_empty)).assertDoesNotExist()
    }

    @Test fun `an original count-only scan displays its saved number without invented network details`() {
        val row = UnifiedHistoryRow(HistoryPosition(1_700_000_000_000L, "legacy:legacy_wifi_count", "1:7"), null,
            setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN, sections = emptyList(),
            measurement = HistoryMeasurement.LegacyWifiCount(12, 50.1, 14.2, 350.0))
        show(listOf(HistoryPagingItem.Row(row, snapshot)))
        rule.onNodeWithText(context.getString(R.string.radio_detail_networks)).assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithTag("history_measurement_1:7").performClick()
        rule.onNodeWithText(context.getString(R.string.radio_detail_signal_dbm, 0)).assertDoesNotExist()
        rule.onNodeWithText("50.1").assertDoesNotExist()
    }

    @Test fun `an invalid original count is unavailable rather than an invented zero`() {
        val row = UnifiedHistoryRow(HistoryPosition(1_700_000_000_000L, "legacy:legacy_wifi_count", "1:8"), null,
            setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN, sections = emptyList(),
            measurement = HistoryMeasurement.LegacyWifiCount(-1, 50.1, 14.2, null))
        show(listOf(HistoryPagingItem.Row(row, snapshot)))
        rule.onNodeWithText(context.getString(R.string.history_value_unavailable)).assertIsDisplayed()
        rule.onNodeWithText("-1").assertDoesNotExist()
        rule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun `overlapping sessions appear as two compact names only in one expanded observation`() {
        val row = wifi("same", 1_700_000_000_000L, "Cafe", null).copy(sessionNames = listOf("Morning walk", "Commute"))
        show(listOf(HistoryPagingItem.Row(row, snapshot)))
        rule.onNodeWithText("Morning walk").assertDoesNotExist()
        rule.onNodeWithText("Commute").assertDoesNotExist()
        val card = rule.onNodeWithTag("history_measurement_same")
        card.performClick().assert(hasText("Morning walk")).assert(hasText("Commute"))
        rule.onAllNodesWithText("Cafe").assertCountEquals(1)
        card.performClick()
        rule.onNodeWithText("Morning walk").assertDoesNotExist()
        rule.onNodeWithText("Commute").assertDoesNotExist()
    }

    @Test fun `an unorganized reading expands without an empty session label`() {
        show(listOf(HistoryPagingItem.Row(wifi("free", 1_700_000_000_000L, "Free reading", null), snapshot)))
        rule.onNodeWithTag("history_measurement_free").performClick()
            .assert(hasText("Free reading"))
        rule.onNodeWithText("null").assertDoesNotExist()
    }

	private fun wifi(id: String, time: Long, name: String?, bssid: String?) = UnifiedHistoryRow(
		HistoryPosition(time, "local:ambient_wifi", id), null, setOf(HistoryOrigin.LOCAL),
		HistoryPurpose.BACKGROUND, sections = emptyList(), measurement = HistoryMeasurement.Wifi(
			WifiObservedNetwork(time, 0L, bssid, name, 2412, -45)))
}
