package com.adsamcik.tracker.statistics.ui.compose

import android.R as AndroidR
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.adsamcik.tracker.statistics.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [StatsDateRangeDialog]. We do not refactor the
 * dialog (per task rules), so the tests interact with it through its
 * public-ish entry point and assert observable behaviour:
 *  - which preset chip is preselected based on the initial range
 *  - whether OK / Cancel / All time invoke the right callbacks
 *  - whether the "Clear filter" affordance appears for non-null initial
 *    ranges that aren't ALL_TIME.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsDateRangeDialogComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()
	private var originalTimeZone: TimeZone? = null

	@After
	fun restoreTimeZone() {
		originalTimeZone?.let(TimeZone::setDefault)
	}

	private fun useZone(id: String): ZoneId {
		originalTimeZone = TimeZone.getDefault()
		TimeZone.setDefault(TimeZone.getTimeZone(id))
		return ZoneId.of(id)
	}

	private fun str(resId: Int): String =
		RuntimeEnvironment.getApplication().getString(resId)

	private fun okText(): String = str(AndroidR.string.ok)
	private fun cancelText(): String = str(AndroidR.string.cancel)
	private fun clearFilterText(): String = str(R.string.stats_filter_clear)
	private fun allTimeText(): String = str(R.string.stats_date_preset_all_time)
	private fun titleText(): String = str(R.string.stats_date_dialog_title)

	private fun millisAtUtcStartOfDay(year: Int, month: Int, day: Int): Long {
		return java.time.LocalDate.of(year, month, day)
			.atStartOfDay(ZoneId.of("UTC"))
			.toInstant()
			.toEpochMilli()
	}

	@Test
	fun `custom dates round trip through real picker east of UTC`() {
		assertCalendarRoundTrip("Europe/Prague", LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 18))
	}

	@Test
	fun `custom dates round trip through real picker west of UTC`() {
		assertCalendarRoundTrip("America/Los_Angeles", LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 18))
	}

	@Test
	fun `spring clock change confirms a complete 23 hour local day`() {
		assertCalendarRoundTrip("Europe/Prague", LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29), 23L)
	}

	@Test
	fun `autumn clock change confirms a complete 25 hour local day`() {
		assertCalendarRoundTrip("Europe/Prague", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), 25L)
	}

	@Test
	fun `Today preset confirms local calendar bounds east of UTC`() {
		assertPresetRoundTrip("Europe/Prague", StatsDateRangePreset.TODAY)
	}

	@Test
	fun `Last month preset confirms local calendar bounds west of UTC`() {
		assertPresetRoundTrip("America/Los_Angeles", StatsDateRangePreset.LAST_MONTH)
	}

	@Test
	fun `choosing calendar dates after All time confirms the chosen range`() {
		val zone = useZone("Europe/Prague")
		val start = LocalDate.of(2026, 6, 15).atStartOfDay(zone).toInstant().toEpochMilli()
		val end = LocalDate.of(2026, 6, 17).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
		var confirmed: Pair<Long, Long>? = null
		var cleared = 0
		showRange(start, end, { first, last -> confirmed = first to last }, { cleared++ })
		composeTestRule.onNode(hasText(allTimeText()) and hasClickAction()).performScrollTo().performClick()
		composeTestRule.onAllNodesWithText("17").onFirst().performClick()
		composeTestRule.onAllNodesWithText("18").onFirst().performClick()
		composeTestRule.onNodeWithText(okText()).performClick()
		assertEquals(0, cleared)
		assertEquals(
			LocalDate.of(2026, 6, 17).atStartOfDay(zone).toInstant().toEpochMilli() to
				(LocalDate.of(2026, 6, 19).atStartOfDay(zone).toInstant().toEpochMilli() - 1L),
			confirmed,
		)
	}

	private fun assertCalendarRoundTrip(zoneId: String, first: LocalDate, last: LocalDate, hours: Long? = null) {
		val zone = useZone(zoneId)
		val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
		val end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
		var confirmed: Pair<Long, Long>? = null
		var cleared = 0
		showRange(start, end, { firstMs, lastMs -> confirmed = firstMs to lastMs }, { cleared++ })
		val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
		val caption = if (first == last) formatter.format(first) else
			"${formatter.format(first)} → ${formatter.format(last)}"
		composeTestRule.onNodeWithText(caption).assertIsDisplayed()
		composeTestRule.onNodeWithText(okText()).performClick()
		assertEquals(start to end, confirmed)
		assertEquals(0, cleared)
		hours?.let { assertEquals(it * 60L * 60L * 1_000L, end - start + 1L) }
	}

	private fun assertPresetRoundTrip(zoneId: String, preset: StatsDateRangePreset) {
		val zone = useZone(zoneId)
		val expected = statsDateRangePresetOptions(System.currentTimeMillis(), zone).single { it.preset == preset }
		var confirmed: Pair<Long, Long>? = null
		var cleared = 0
		showRange(null, null, { first, last -> confirmed = first to last }, { cleared++ })
		composeTestRule.onNode(hasText(str(preset.labelRes)) and hasClickAction()).performScrollTo().performClick()
		composeTestRule.onNodeWithText(okText()).performClick()
		assertEquals(expected.startMs to expected.endMs, confirmed)
		assertEquals(0, cleared)
	}

	private fun showRange(start: Long?, end: Long?, confirm: (Long, Long) -> Unit, clear: () -> Unit) {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(start, end, confirm, clear, {})
			}
		}
	}

	// ─── Chrome ──────────────────────────────────────────────────────────

	@Test
	fun `dialog renders title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(
					initialStartMs = null,
					initialEndMs = null,
					onConfirm = { _, _ -> },
					onClear = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithText(titleText()).assertIsDisplayed()
	}

	// ─── Cancel ──────────────────────────────────────────────────────────

	@Test
	fun `tapping Cancel invokes onDismiss`() {
		var dismissed = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(
					initialStartMs = null,
					initialEndMs = null,
					onConfirm = { _, _ -> },
					onClear = {},
					onDismiss = { dismissed++ },
				)
			}
		}
		composeTestRule.onNodeWithText(cancelText()).performClick()
		assert(dismissed == 1) { "Expected onDismiss to fire once, was $dismissed" }
	}

	// ─── ALL_TIME path ───────────────────────────────────────────────────

	@Test
	fun `null initial range preselects All time chip and OK calls onClear`() {
		var cleared = 0
		var confirmed = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(
					initialStartMs = null,
					initialEndMs = null,
					onConfirm = { _, _ -> confirmed++ },
					onClear = { cleared++ },
					onDismiss = {},
				)
			}
		}
		// "All time" appears in both the chip and the range-summary label
		// underneath. Asserting that at least one (the first match) is
		// displayed is enough — the OK click is the real behavioural
		// assertion below.
		composeTestRule.onAllNodesWithText(allTimeText()).onFirst().assertIsDisplayed()
		// OK button — with ALL_TIME selected the click should route to onClear.
		composeTestRule.onNodeWithText(okText()).performClick()
		assert(cleared == 1) {
			"Expected onClear to fire when ALL_TIME is the active preset (cleared=$cleared)"
		}
		assert(confirmed == 0) {
			"Did not expect onConfirm to fire for ALL_TIME (confirmed=$confirmed)"
		}
	}

	// ─── Clear-filter affordance ─────────────────────────────────────────

	@Test
	fun `non-null initial range that does not match ALL_TIME shows Clear filter`() {
		// Pick an arbitrary custom range that won't match any preset.
		val start = millisAtUtcStartOfDay(2024, 1, 5)
		val end = millisAtUtcStartOfDay(2024, 1, 20) - 1L
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(
					initialStartMs = start,
					initialEndMs = end,
					onConfirm = { _, _ -> },
					onClear = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithText(clearFilterText()).assertIsDisplayed()
	}

	@Test
	fun `null initial range does not show Clear filter button`() {
		// Clear-filter is only shown when there's an actual filter to clear.
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(
					initialStartMs = null,
					initialEndMs = null,
					onConfirm = { _, _ -> },
					onClear = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithText(clearFilterText()).assertDoesNotExist()
	}

	@Test
	fun `tapping Clear filter invokes onClear, not onDismiss`() {
		var cleared = 0
		var dismissed = 0
		val start = millisAtUtcStartOfDay(2024, 1, 5)
		val end = millisAtUtcStartOfDay(2024, 1, 20) - 1L
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsDateRangeDialog(
					initialStartMs = start,
					initialEndMs = end,
					onConfirm = { _, _ -> },
					onClear = { cleared++ },
					onDismiss = { dismissed++ },
				)
			}
		}
		composeTestRule.onNodeWithText(clearFilterText()).performClick()
		assert(cleared == 1) { "Expected onClear, was $cleared" }
		assert(dismissed == 0) { "Did not expect onDismiss, was $dismissed" }
	}
}
