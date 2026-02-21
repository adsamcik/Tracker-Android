package com.adsamcik.tracker.statistics

import android.content.Context
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale

@DisplayName("StatsFormat")
class StatsFormatTest {

	// =====================================================================
	// formatRange
	// =====================================================================

	@Nested
	@DisplayName("formatRange")
	inner class FormatRange {

		private fun calendar(
			year: Int,
			month: Int,
			day: Int,
			hour: Int = 0,
			minute: Int = 0,
		): Calendar = Calendar.getInstance(Locale.US).apply {
			set(Calendar.YEAR, year)
			set(Calendar.MONTH, month - 1)
			set(Calendar.DAY_OF_MONTH, day)
			set(Calendar.HOUR_OF_DAY, hour)
			set(Calendar.MINUTE, minute)
			set(Calendar.SECOND, 0)
			set(Calendar.MILLISECOND, 0)
		}

		@Test
		fun `same day range contains dash separator`() {
			val start = calendar(2024, 6, 15, 10, 0)
			val end = calendar(2024, 6, 15, 14, 30)

			val result = StatsFormat.formatRange(start, end)

			result.shouldNotBeBlank()
			result shouldContain " - "
		}

		@Test
		fun `different day range produces non-blank result with separator`() {
			val start = calendar(2024, 6, 15, 10, 0)
			val end = calendar(2024, 6, 18, 14, 30)

			val result = StatsFormat.formatRange(start, end)

			result.shouldNotBeBlank()
			result shouldContain " - "
		}

		@Test
		fun `different year range includes year in output`() {
			val start = calendar(2023, 12, 30, 10, 0)
			val end = calendar(2024, 1, 2, 14, 30)

			val result = StatsFormat.formatRange(start, end)

			result.shouldNotBeBlank()
			result shouldContain "2023"
		}

		@Test
		fun `same year same day formats without error`() {
			val now = Calendar.getInstance()
			val start = now.clone() as Calendar
			val end = now.clone() as Calendar
			start.set(Calendar.HOUR_OF_DAY, 8)
			end.set(Calendar.HOUR_OF_DAY, 12)

			val result = StatsFormat.formatRange(start, end)

			result.shouldNotBeBlank()
		}
	}

	// =====================================================================
	// createTitle
	// =====================================================================

	@Nested
	@DisplayName("createTitle")
	inner class CreateTitle {

		private val context: Context = mockk(relaxed = true)
		private val sunSetRise: SunSetRise = mockk()

		private fun setupMocks() {
			every { sunSetRise.sunriseFor(any()) } returns ZonedDateTime.now().withHour(7)
			every { sunSetRise.sunsetFor(any()) } returns ZonedDateTime.now().withHour(21)

			// Single-arg getString for time-of-day labels
			every { context.getString(R.string.stats_format_unknown_activity) } returns "Unknown"
			every { context.getString(R.string.stats_midnight) } returns "Midnight"
			every { context.getString(R.string.stats_night) } returns "Night"
			every { context.getString(R.string.stats_morning) } returns "Morning"
			every { context.getString(R.string.stats_lunch) } returns "Lunch"
			every { context.getString(R.string.stats_afternoon) } returns "Afternoon"
			every { context.getString(R.string.stats_evening) } returns "Evening"

			// Varargs getString for title templates
			every { context.getString(R.string.stats_generic_title_text, *anyVararg()) } answers {
				val varargs = args.drop(1).flatMap {
					when (it) {
						is Array<*> -> it.map { e -> e.toString() }
						else -> listOf(it.toString())
					}
				}
				"${varargs.getOrElse(0) { "" }} ${varargs.getOrElse(1) { "" }}"
			}
			every { context.getString(R.string.stats_title_text, *anyVararg()) } answers {
				val varargs = args.drop(1).flatMap {
					when (it) {
						is Array<*> -> it.map { e -> e.toString() }
						else -> listOf(it.toString())
					}
				}
				"${varargs.getOrElse(0) { "" }} ${varargs.getOrElse(1) { "" }} ${varargs.getOrElse(2) { "" }}"
			}
		}

		private fun epochMillisAt(hour: Int, minute: Int = 0, dayOffset: Int = 0): Long {
			return ZonedDateTime.now(ZoneId.systemDefault())
				.plusDays(dayOffset.toLong())
				.withHour(hour)
				.withMinute(minute)
				.withSecond(0)
				.withNano(0)
				.toInstant()
				.toEpochMilli()
		}

		@Test
		fun `morning session returns title with morning label`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Walking")

			val start = epochMillisAt(7, 0)
			val end = epochMillisAt(9, 30)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Morning"
			result shouldContain "Walking"
		}

		@Test
		fun `lunch session returns title with lunch label`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Walking")

			val start = epochMillisAt(12, 0)
			val end = epochMillisAt(13, 0)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Lunch"
			result shouldContain "Walking"
		}

		@Test
		fun `afternoon session returns title with afternoon label`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Running")

			val start = epochMillisAt(13, 0)
			val end = epochMillisAt(17, 0)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Afternoon"
			result shouldContain "Running"
		}

		@Test
		fun `evening session returns title with evening label`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Running")

			// End must be > 20 to avoid matching Afternoon (endHour <= 20)
			val start = epochMillisAt(18, 0)
			val end = epochMillisAt(21, 0)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Evening"
			result shouldContain "Running"
		}

		@Test
		fun `night session returns title with night label`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Walking")

			// Both start and end in 0..6 range → Night
			val start = epochMillisAt(1, 0)
			val end = epochMillisAt(5, 0)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Night"
			result shouldContain "Walking"
		}

		@Test
		fun `midnight session returns title with midnight label`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Walking")

			// Start >= 22, end <= 2 (next day) → Midnight
			val start = epochMillisAt(23, 0)
			val end = epochMillisAt(1, 0, dayOffset = 1)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Midnight"
			result shouldContain "Walking"
		}

		@Test
		fun `blank activity name uses unknown fallback`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "")

			val start = epochMillisAt(7, 0)
			val end = epochMillisAt(9, 0)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result shouldContain "Unknown"
		}

		@Test
		fun `multi-day span produces generic title without time-of-day`() {
			setupMocks()
			val activity = SessionActivity(id = 1, name = "Cycling")

			val start = epochMillisAt(10, 0)
			val end = epochMillisAt(10, 0, dayOffset = 3)

			val result = StatsFormat.createTitle(context, start, end, activity, sunSetRise)

			result.shouldNotBeBlank()
			result shouldContain "Cycling"
		}
	}
}
