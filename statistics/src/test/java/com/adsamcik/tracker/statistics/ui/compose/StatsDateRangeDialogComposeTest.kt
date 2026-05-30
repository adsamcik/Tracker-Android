package com.adsamcik.tracker.statistics.ui.compose

import android.R as AndroidR
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.R
import java.time.ZoneId
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
