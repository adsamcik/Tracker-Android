package com.adsamcik.tracker.impexp.exporter

import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class CapturedRadioDateSelectionTest {
	private val dates = SourceExportDates(86_400_000L, 172_800_000L)

	@Test fun `calendar days use their actual length across daylight saving changes`() {
		val zone = ZoneId.of("Europe/Prague")
		val spring = SourceExportDates.of(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29), zone)
		val autumn = SourceExportDates.of(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), zone)
		(spring.toExclusiveMs - spring.fromInclusiveMs) shouldBe 23L * 60L * 60L * 1_000L
		(autumn.toExclusiveMs - autumn.fromInclusiveMs) shouldBe 25L * 60L * 60L * 1_000L
		val epochDay = SourceExportDates.of(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 1), zone)
		(epochDay.fromInclusiveMs < 0L) shouldBe true
		epochDay.contains(0L) shouldBe true
	}

	@Test fun `a crossing entry needs a scan or recorded gap on the selected day`() {
		val previousDayOnly = decision(
			observations = listOf(RadioObservationTime(80_000_000L, 80_000_000L, 80_000_000L)),
		)
		previousDayOnly.include shouldBe false
		previousDayOnly.excludedAcrossDates shouldBe false

		val scanOnDay = decision(
			observations = listOf(
				RadioObservationTime(80_000_000L, 80_000_000L, 80_000_000L),
				RadioObservationTime(100_000_000L, 100_000_000L, 100_000_000L),
			),
		)
		scanOnDay.include shouldBe true
		scanOnDay.observationsOutsideDates shouldBe 1

		decision(recordedGaps = listOf(90_000_000L to 100_000_000L)).include shouldBe true
		decision(recordedGaps = listOf(70_000_000L to 80_000_000L)).include shouldBe false
	}

	@Test fun `turning off complete sessions keeps in-date readings from an overnight entry`() {
		val excluded = decision(
			observations = listOf(RadioObservationTime(100_000_000L, 100_000_000L, 100_000_000L)),
			includeSessionsAcrossDates = false,
		)
		excluded.include shouldBe true
		excluded.excludedAcrossDates shouldBe false
		excluded.observationsOutsideDates shouldBe 0

		val contained = decision(
			entryStartMs = 90_000_000L,
			entryEndMs = 120_000_000L,
			observations = listOf(RadioObservationTime(100_000_000L, 100_000_000L, 100_000_000L)),
			includeSessionsAcrossDates = false,
		)
		contained.include shouldBe true
		decision(
			entryStartMs = 90_000_000L,
			entryEndMs = 172_800_000L,
			observations = listOf(RadioObservationTime(100_000_000L, 100_000_000L, 100_000_000L)),
			includeSessionsAcrossDates = false,
		).include shouldBe true
	}

	@Test fun `uncertain scan timing can overlap but its saved timestamp remains outside`() {
		val selected = decision(
			observations = listOf(RadioObservationTime(86_399_000L, 86_399_000L, 86_401_000L)),
		)
		selected.include shouldBe true
		selected.observationsOutsideDates shouldBe 1
		decision(
			entryStartMs = 90_000_000L,
			entryEndMs = 100_000_000L,
			observations = listOf(RadioObservationTime(86_399_000L, 90_000_000L, 90_000_000L)),
			includeSessionsAcrossDates = false,
		).excludedAcrossDates shouldBe false
	}

	@Test fun `end of selected day is exclusive and zero-length gap is a point`() {
		dates.contains(172_799_999L) shouldBe true
		dates.contains(172_800_000L) shouldBe false
		dates.overlaps(172_800_000L, 172_800_000L) shouldBe false
		dates.overlaps(86_400_000L, 86_400_000L) shouldBe true
		dates.containsWholeInterval(90_000_000L, 172_800_000L) shouldBe true
		dates.containsWholePossibleTime(90_000_000L, 172_800_000L) shouldBe false
		decision(recordedGaps = listOf(172_800_000L to 172_800_000L)).include shouldBe false
	}

	@Test fun `absence of scans without a recorded gap does not select a crossing session`() {
		decision().include shouldBe false
	}

	@Test fun `all history keeps an entry without inventing selected-date counts`() {
		decideCapturedRadioEntry(
			entryStartMs = 1L,
			entryEndMs = 2L,
			dates = null,
			includeSessionsAcrossDates = false,
			observations = emptyList(),
			recordedGaps = emptyList(),
		) shouldBe CapturedRadioDateDecision(true, 0, false)
	}

	private fun decision(
		entryStartMs: Long = 80_000_000L,
		entryEndMs: Long = 110_000_000L,
		observations: List<RadioObservationTime> = emptyList(),
		recordedGaps: List<Pair<Long, Long>> = emptyList(),
		includeSessionsAcrossDates: Boolean = true,
	): CapturedRadioDateDecision = decideCapturedRadioEntry(
		entryStartMs = entryStartMs,
		entryEndMs = entryEndMs,
		dates = dates,
		includeSessionsAcrossDates = includeSessionsAcrossDates,
		observations = observations,
		recordedGaps = recordedGaps,
	)
}
