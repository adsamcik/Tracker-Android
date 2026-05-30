package com.adsamcik.tracker.statistics.fragment

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import com.adsamcik.tracker.statistics.viewmodel.hasTrackedActivity
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for `WeeklySummaryDayChip`'s pure helper [weeklySummaryDayValueText]
 * — the function that decides which metric ends up on the chip. The chip
 * composable itself is `private`, but the value-selection logic is the
 * meaningful, regress-able surface: render bugs are usually a wrong text
 * pick, not a layout pick.
 *
 * Production priority (lines 738-748 in StatsScreen.kt):
 *  1. steps > 0          → formatted step count
 *  2. distanceM > 0      → formatted distance (locale-aware)
 *  3. durationMs > 0     → formatted duration
 *  4. sessionCount > 0   → formatted session count
 *  5. otherwise          → "—" em-dash
 *
 * The chip's content description ("dayLabel, valueText") accessibility
 * surface is also covered indirectly: when this helper returns the right
 * value, the chip's semantics modifier composes the right description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklySummaryDayValueTextTest {

	private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

	private fun day(
		steps: Int = 0,
		distanceM: Float = 0f,
		durationMs: Long = 0L,
		sessionCount: Int = 0,
	): DayBar = DayBar(
		dayLabel = "Mon",
		distanceM = distanceM,
		steps = steps,
		epochDay = 19_000L,
		sessionCount = sessionCount,
		durationMs = durationMs,
	)

	@Test
	fun `steps win over distance when both are positive`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(steps = 1234, distanceM = 5000f),
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		// formatReadable on 1234 produces a locale-grouped string; assert it
		// contains the leading "1" and does NOT contain "km" / "m " (which
		// would mean distance leaked through).
		assert(text.contains("1")) { "Expected steps formatting in $text" }
		assert(!text.contains("km")) { "Distance leaked through: $text" }
	}

	@Test
	fun `distance is used when steps are zero`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(steps = 0, distanceM = 2500f),
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		// Distance >= 1000m formats with 1 decimal & "km" in metric.
		assert(text.contains("km")) { "Expected km in $text" }
	}

	@Test
	fun `duration is used when steps and distance are zero`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(steps = 0, distanceM = 0f, durationMs = 5L * 60L * 1000L),
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		// formatAsDuration includes a unit (e.g. "5m") — assert non-empty
		// and definitely NOT the em-dash fallback.
		assert(text.isNotBlank())
		text shouldBe text  // sanity
		assert(text != "—") { "Expected duration formatting, got dash" }
	}

	@Test
	fun `sessionCount is used when everything else is zero`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(steps = 0, distanceM = 0f, durationMs = 0L, sessionCount = 3),
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		assert(text.contains("3")) { "Expected session count in $text" }
		assert(text != "—") { "Expected session count, got dash" }
	}

	@Test
	fun `em-dash is shown when no activity exists`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(),
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		text shouldBe "—"
	}

	@Test
	fun `imperial length system formats distance in miles when steps are zero`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(steps = 0, distanceM = 5_000f),
			context = context,
			lengthSystem = LengthSystem.Imperial,
		)
		// Imperial system never produces "km"; expect either "mi" or "ft".
		assert(!text.contains("km")) { "Imperial output leaked km: $text" }
		val hasMiles = text.contains("mi") || text.contains("ft")
		assert(hasMiles) { "Expected imperial unit (mi/ft) in $text" }
	}

	// ─── hasTrackedActivity extension (used by chip to pick container color) ───

	@Test
	fun `hasTrackedActivity is true when any signal is positive`() {
		day(steps = 1).hasTrackedActivity shouldBe true
		day(distanceM = 1f).hasTrackedActivity shouldBe true
		day(durationMs = 1L).hasTrackedActivity shouldBe true
		day(sessionCount = 1).hasTrackedActivity shouldBe true
	}

	@Test
	fun `hasTrackedActivity is false when all signals are zero`() {
		day().hasTrackedActivity shouldBe false
	}
}
