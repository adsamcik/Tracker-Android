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
	}

	@Test fun `a crossing entry needs a scan or recorded gap on the selected day`() {
		val previousDayOnly = decision(
			observations = listOf(RadioObservationTime(80_000L, 80_000L, 80_000L)),
		)
		previousDayOnly.include shouldBe false
		previousDayOnly.excludedAcrossDates shouldBe false

		val scanOnDay = decision(
			observations = listOf(
				RadioObservationTime(80_000L, 80_000L, 80_000L),
				RadioObservationTime(100_000L, 100_000L, 100_000L),
			),
		)
		scanOnDay.include shouldBe true
		scanOnDay.observationsOutsideDates shouldBe 1

		decision(recordedGaps = listOf(90_000L to 100_000L)).include shouldBe true
		decision(recordedGaps = listOf(70_000L to 80_000L)).include shouldBe false
	}

	@Test fun `turning off cross-date sessions excludes the whole entry`() {
		val excluded = decision(
			observations = listOf(RadioObservationTime(100_000L, 100_000L, 100_000L)),
			includeSessionsAcrossDates = false,
		)
		excluded.include shouldBe false
		excluded.excludedAcrossDates shouldBe true
		excluded.observationsOutsideDates shouldBe 0

		val contained = decision(
			entryStartMs = 90_000L,
			entryEndMs = 120_000L,
			observations = listOf(RadioObservationTime(100_000L, 100_000L, 100_000L)),
			includeSessionsAcrossDates = false,
		)
		contained.include shouldBe true
	}

	@Test fun `uncertain scan timing can overlap but its saved timestamp remains outside`() {
		val selected = decision(
			observations = listOf(RadioObservationTime(86_399_000L, 86_399_000L, 86_401_000L)),
		)
		selected.include shouldBe true
		selected.observationsOutsideDates shouldBe 1
		decision(
			entryStartMs = 90_000L,
			entryEndMs = 100_000L,
			observations = listOf(RadioObservationTime(86_399_000L, 90_000L, 90_000L)),
			includeSessionsAcrossDates = false,
		).excludedAcrossDates shouldBe true
	}

	@Test fun `end of selected day is exclusive and zero-length gap is a point`() {
		dates.contains(172_799_999L) shouldBe true
		dates.contains(172_800_000L) shouldBe false
		dates.overlaps(172_800_000L, 172_800_000L) shouldBe false
		dates.overlaps(86_400_000L, 86_400_000L) shouldBe true
		decision(recordedGaps = listOf(172_800_000L to 172_800_000L)).include shouldBe false
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
		entryStartMs: Long = 80_000L,
		entryEndMs: Long = 110_000L,
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
