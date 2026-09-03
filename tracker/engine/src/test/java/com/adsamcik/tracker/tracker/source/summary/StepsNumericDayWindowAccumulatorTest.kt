package com.adsamcik.tracker.tracker.source.summary

import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class StepsNumericDayWindowAccumulatorTest {
	@Test
	fun `empty product request stays invalid while deletion validation can stream without results`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val startMs = startOfDayMs(day, zone) + HOUR_MS
		val endMs = startMs + HOUR_MS
		val slice = StepsNumericCaptureSlice(1L, startMs, endMs, capturesSteps = true)
		val contribution = contribution(zone, startMs, endMs, listOf(slice))

		StepsNumericDayWindowAccumulator.create(emptyMap()) shouldBe null
		val validation = StepsNumericDayWindowAccumulator.createEmptyValidationOnly()
		validation.addLogicalGroup(listOf(contribution)) shouldBe true
		validation.startRun(contribution) shouldBe true
		validation.consumeCoveredFact(fact(1L, startMs, endMs, steps = 7L)) shouldBe true
		validation.finishRun() shouldBe true
		validation.results() shouldBe emptyList()
	}

	@Test
	fun `370 day cells consume about 95 thousand facts exactly once`() {
		val zone = ZoneId.of("UTC")
		val firstDay = LocalDate.of(2025, 1, 1).toEpochDay()
		val zoneByDay = (0 until StepsNumericSummaryRequest.MAX_DAY_COUNT).associate { offset ->
			(firstDay + offset) to zone
		}
		val slices = zoneByDay.keys.mapIndexed { index, epochDay ->
			StepsNumericCaptureSlice(
				manifestRevision = index.toLong() + 1L,
				startMs = startOfDayMs(epochDay, zone),
				endMs = startOfDayMs(epochDay + 1L, zone),
				capturesSteps = true,
			)
		}
		val contribution = contribution(
			zone = zone,
			startMs = slices.first().startMs,
			endMs = slices.last().endMs,
			slices = slices,
		)
		val accumulator = requireNotNull(StepsNumericDayWindowAccumulator.create(zoneByDay))
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true

		var consumedFacts = 0
		for (slice in slices) {
			val durationMs = slice.endMs - slice.startMs
			repeat(FACTS_PER_DAY) { index ->
				val startMs = slice.startMs + durationMs * index / FACTS_PER_DAY
				val endMs = slice.startMs + durationMs * (index + 1L) / FACTS_PER_DAY
				accumulator.consumeCoveredFact(
					fact(
						manifestRevision = slice.manifestRevision,
						startMs = startMs,
						endMs = endMs,
						steps = 1L,
					),
				) shouldBe true
				consumedFacts += 1
			}
		}
		accumulator.finishRun() shouldBe true

		val results = requireNotNull(accumulator.results())
		consumedFacts shouldBe 95_090
		results.size shouldBe StepsNumericSummaryRequest.MAX_DAY_COUNT
		results.sumOf(StepsNumericAccumulatedDay::exactSteps) shouldBe consumedFacts.toLong()
		results.sumOf { day -> day.steps.toLong() } shouldBe consumedFacts.toLong()
		results.all { day ->
			day.hasCompleteStepsCapture && !day.hasPartialStepsCapture &&
				day.exactSteps == FACTS_PER_DAY.toLong()
		} shouldBe true
	}

	@Test
	fun `covered zero crossing midnight remains exact in both clipped windows`() {
		val zone = ZoneId.of("UTC")
		val firstDay = LocalDate.of(2026, 4, 2).toEpochDay()
		val midnight = startOfDayMs(firstDay + 1L, zone)
		val slice = StepsNumericCaptureSlice(
			manifestRevision = 1L,
			startMs = midnight - HOUR_MS,
			endMs = midnight + HOUR_MS,
			capturesSteps = true,
		)
		val contribution = contribution(
			zone = zone,
			startMs = slice.startMs,
			endMs = slice.endMs,
			slices = listOf(slice),
		)
		val accumulator = requireNotNull(
			StepsNumericDayWindowAccumulator.create(
				mapOf(firstDay to zone, firstDay + 1L to zone),
			),
		)
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(
			fact(1L, slice.startMs, slice.endMs, steps = 0L),
		) shouldBe true
		accumulator.finishRun() shouldBe true

		requireNotNull(accumulator.results()).map { day ->
			Triple(day.exactSteps, day.hasCompleteStepsCapture, day.hasPartialStepsCapture)
		} shouldBe listOf(Triple(0L, true, false), Triple(0L, true, false))
	}

	@Test
	fun `day-local facts stay exact across a DST boundary while a positive crossing fact is partial`() {
		val zone = ZoneId.of("Europe/Prague")
		val firstDay = LocalDate.of(2026, 3, 28).toEpochDay()
		val firstStart = startOfDayMs(firstDay, zone)
		val midnight = startOfDayMs(firstDay + 1L, zone)
		val secondEnd = startOfDayMs(firstDay + 2L, zone)
		val exactSlices = listOf(
			StepsNumericCaptureSlice(1L, firstStart, midnight, capturesSteps = true),
			StepsNumericCaptureSlice(2L, midnight, secondEnd, capturesSteps = true),
		)
		val exactContribution = contribution(zone, firstStart, secondEnd, exactSlices)
		val exact = requireNotNull(
			StepsNumericDayWindowAccumulator.create(
				mapOf(firstDay to zone, firstDay + 1L to zone),
			),
		)
		exact.addLogicalGroup(listOf(exactContribution)) shouldBe true
		exact.startRun(exactContribution) shouldBe true
		exact.consumeCoveredFact(fact(1L, firstStart, midnight, steps = 7L)) shouldBe true
		exact.consumeCoveredFact(fact(2L, midnight, secondEnd, steps = 11L)) shouldBe true
		exact.finishRun() shouldBe true
		requireNotNull(exact.results()).map(StepsNumericAccumulatedDay::exactSteps) shouldBe
			listOf(7L, 11L)

		val crossingSlice = StepsNumericCaptureSlice(
			manifestRevision = 1L,
			startMs = midnight - HOUR_MS,
			endMs = midnight + HOUR_MS,
			capturesSteps = true,
		)
		val crossingContribution = contribution(
			zone,
			crossingSlice.startMs,
			crossingSlice.endMs,
			listOf(crossingSlice),
		)
		val crossing = requireNotNull(
			StepsNumericDayWindowAccumulator.create(
				mapOf(firstDay to zone, firstDay + 1L to zone),
			),
		)
		crossing.addLogicalGroup(listOf(crossingContribution)) shouldBe true
		crossing.startRun(crossingContribution) shouldBe true
		crossing.consumeCoveredFact(
			fact(1L, crossingSlice.startMs, crossingSlice.endMs, steps = 5L),
		) shouldBe true
		crossing.finishRun() shouldBe true
		requireNotNull(crossing.results()).map(StepsNumericAccumulatedDay::hasPartialStepsCapture) shouldBe
			listOf(true, true)
	}

	@Test
	fun `an internal covered gap remains partial instead of becoming zero`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val startMs = startOfDayMs(day, zone) + HOUR_MS
		val endMs = startMs + HOUR_MS
		val slice = StepsNumericCaptureSlice(1L, startMs, endMs, capturesSteps = true)
		val contribution = contribution(zone, startMs, endMs, listOf(slice))
		val accumulator = requireNotNull(
			StepsNumericDayWindowAccumulator.create(mapOf(day to zone)),
		)
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(
			fact(1L, startMs, startMs + 20L * MINUTE_MS, steps = 2L),
		) shouldBe true
		accumulator.consumeCoveredFact(
			fact(1L, startMs + 40L * MINUTE_MS, endMs, steps = 3L),
		) shouldBe true
		accumulator.finishRun() shouldBe true

		val result = requireNotNull(accumulator.results()).single()
		result.hasPartialStepsCapture shouldBe true
		result.hasCompleteStepsCapture shouldBe false
	}

	private fun contribution(
		zone: ZoneId,
		startMs: Long,
		endMs: Long,
		slices: List<StepsNumericCaptureSlice>,
	) = StepsNumericRunContribution(
		serviceRunId = RUN_ID,
		logicalTrackingId = "logical",
		logicalStartedAtMs = startMs,
		capturedZoneId = zone,
		segmentStartMs = startMs,
		segmentEndMs = endMs,
		distanceM = 0f,
		captureSlices = slices,
	)

	private fun fact(
		manifestRevision: Long,
		startMs: Long,
		endMs: Long,
		steps: Long,
	) = StepsNumericCoveredFact(
		serviceRunId = RUN_ID,
		manifestRevision = manifestRevision,
		startMs = startMs,
		endMs = endMs,
		steps = steps,
		wallTimeUncertaintyMs = 0L,
	)

	private fun startOfDayMs(epochDay: Long, zoneId: ZoneId): Long =
		LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()

	private companion object {
		const val RUN_ID = "run"
		const val FACTS_PER_DAY = 257
		const val MINUTE_MS = 60_000L
		const val HOUR_MS = 60L * MINUTE_MS
	}
}
