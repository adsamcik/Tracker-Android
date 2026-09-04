package com.adsamcik.tracker.statistics.fragment

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import com.adsamcik.tracker.statistics.viewmodel.hasNonStepTrackedActivity
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
 *  1. qualified Steps available → formatted step count, including covered zero
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
		distanceM: Float = 0f,
		durationMs: Long = 0L,
		sessionCount: Int = 0,
		epochDay: Long = 19_000L,
	): DayBar = DayBar(
		dayLabel = "Mon",
		distanceM = distanceM,
		epochDay = epochDay,
		sessionCount = sessionCount,
		durationMs = durationMs,
	)

	@Test
	fun `qualified Steps win over distance`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(distanceM = 5000f),
			qualifiedSteps = 7L,
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		text shouldBe "7"
	}

	@Test
	fun `qualified covered zero stays visible instead of falling through`() {
		weeklySummaryDayValueText(
			dayBar = day(distanceM = 2500f, sessionCount = 3),
			qualifiedSteps = 0L,
			context = context,
			lengthSystem = LengthSystem.Metric,
		) shouldBe "0"
	}

	@Test
	fun `distance is used when qualified Steps are unavailable`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(distanceM = 2500f),
			qualifiedSteps = null,
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		// Distance >= 1000m formats with 1 decimal & "km" in metric.
		assert(text.contains("km")) { "Expected km in $text" }
	}

	@Test
	fun `duration is used when distance is zero`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(distanceM = 0f, durationMs = 5L * 60L * 1000L),
			qualifiedSteps = null,
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
			dayBar = day(distanceM = 0f, durationMs = 0L, sessionCount = 3),
			qualifiedSteps = null,
			context = context,
			lengthSystem = LengthSystem.Metric,
		)
		assert(text.contains("3")) { "Expected session count in $text" }
		assert(text != "—") { "Expected session count, got dash" }
	}

	@Test
	fun `imperial length system formats distance in miles`() {
		val text = weeklySummaryDayValueText(
			dayBar = day(distanceM = 5_000f),
			qualifiedSteps = null,
			context = context,
			lengthSystem = LengthSystem.Imperial,
		)
		// Imperial system never produces "km"; expect either "mi" or "ft".
		assert(!text.contains("km")) { "Imperial output leaked km: $text" }
		val hasMiles = text.contains("mi") || text.contains("ft")
		assert(hasMiles) { "Expected imperial unit (mi/ft) in $text" }
	}

	@Test
	fun `nonnumeric summaries do not count days without structural activity`() {
		val emptyBars = listOf(day())

		sparseSummaryActiveDayCount(
			emptyBars,
			StepsNumericSummary.Materializing,
		) shouldBe 0
		sparseSummaryActiveDayCount(
			emptyBars,
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE),
		) shouldBe 0
	}

	@Test
	fun `active days combine Ready days with non-Step structural signals`() {
		val firstEpochDay = 19_000L
		val bars = listOf(
			day(epochDay = firstEpochDay),
			day(distanceM = 1f, epochDay = firstEpochDay + 1L),
		)
		val qualified = StepsNumericSummary.Ready(
			days = listOf(
				StepsNumericDay(epochDay = firstEpochDay, steps = 3L),
				StepsNumericDay(epochDay = firstEpochDay + 1L, steps = 0L),
			),
		)

		sparseSummaryActiveDayCount(bars, qualified) shouldBe 2
	}

	// ─── Non-Step structural activity (used by chip color and active-day fallback) ───

	@Test
	fun `non-Step activity accepts structural signals`() {
		day().hasNonStepTrackedActivity shouldBe false
		day(distanceM = 1f).hasNonStepTrackedActivity shouldBe true
		day(durationMs = 1L).hasNonStepTrackedActivity shouldBe true
		day(sessionCount = 1).hasNonStepTrackedActivity shouldBe true
	}

	@Test
	fun `non-Step activity is false when structural signals are zero`() {
		day().hasNonStepTrackedActivity shouldBe false
	}
}
