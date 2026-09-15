package com.adsamcik.tracker.tracker.source.summary

import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsNumericHistoryDay
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.source.deletion.StepsDayNumericComposition
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class StepsNumericDayWindowAccumulatorTest {
	@Test
	fun `exact Long count survives integer cache overflow without clamping`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val startMs = startOfDayMs(day, zone) + HOUR_MS
		val endMs = startMs + HOUR_MS
		val contribution = contribution(zone, startMs, endMs, setOf(1L))
		val accumulator = requireNotNull(StepsNumericDayWindowAccumulator.create(mapOf(day to zone)))
		val count = Int.MAX_VALUE.toLong() + 42L
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(fact(1L, startMs, endMs, steps = count)) shouldBe true
		accumulator.finishRun() shouldBe true
		val result = requireNotNull(accumulator.results()).single()
		result.steps shouldBe null
		result.exactSteps shouldBe count
		result.hasCompleteStepsCapture shouldBe true
		result.hasPartialStepsCapture shouldBe false
	}

	@Test
	fun `source-local partial flag follows covered facts outside presentation envelope`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val startMs = startOfDayMs(day, zone) + HOUR_MS
		val endMs = startMs + HOUR_MS
		val contribution = contribution(zone, startMs + 24L * HOUR_MS, endMs + 24L * HOUR_MS, setOf(1L))
			.copy(hasPartialStepsEvidence = true)
		val accumulator = requireNotNull(StepsNumericDayWindowAccumulator.create(mapOf(day to zone)))
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(fact(1L, startMs, endMs, steps = 0L)) shouldBe true
		accumulator.finishRun() shouldBe true
		val result = requireNotNull(accumulator.results()).single()
		result.hasPartialStepsCapture shouldBe true
		result.hasNonStepsCapture shouldBe false
	}

	@Test
	fun `empty product request stays invalid while deletion validation can stream without results`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val startMs = startOfDayMs(day, zone) + HOUR_MS
		val endMs = startMs + HOUR_MS
		val contribution = contribution(zone, startMs, endMs, setOf(1L))

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
		val intervals = zoneByDay.keys.mapIndexed { index, epochDay ->
			FactInterval(
				manifestRevision = index.toLong() + 1L,
				startMs = startOfDayMs(epochDay, zone),
				endMs = startOfDayMs(epochDay + 1L, zone),
			)
		}
		val contribution = contribution(
			zone = zone,
			startMs = intervals.first().startMs,
			endMs = intervals.last().endMs,
			stepsManifestRevisions = intervals.mapTo(linkedSetOf(), FactInterval::manifestRevision),
		)
		val accumulator = requireNotNull(StepsNumericDayWindowAccumulator.create(zoneByDay))
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true

		var consumedFacts = 0
		for (interval in intervals) {
			val durationMs = interval.endMs - interval.startMs
			repeat(FACTS_PER_DAY) { index ->
				val startMs = interval.startMs + durationMs * index / FACTS_PER_DAY
				val endMs = interval.startMs + durationMs * (index + 1L) / FACTS_PER_DAY
				accumulator.consumeCoveredFact(
					fact(
						manifestRevision = interval.manifestRevision,
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
		results.sumOf { day -> requireNotNull(day.steps).toLong() } shouldBe consumedFacts.toLong()
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
		val startMs = midnight - HOUR_MS
		val endMs = midnight + HOUR_MS
		val contribution = contribution(
			zone = zone,
			startMs = startMs,
			endMs = endMs,
			stepsManifestRevisions = setOf(1L),
		)
		val accumulator = requireNotNull(
			StepsNumericDayWindowAccumulator.create(
				mapOf(firstDay to zone, firstDay + 1L to zone),
			),
		)
		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(
			fact(1L, startMs, endMs, steps = 0L),
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
		val exactContribution = contribution(zone, firstStart, secondEnd, setOf(1L, 2L))
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

		val crossingStartMs = midnight - HOUR_MS
		val crossingEndMs = midnight + HOUR_MS
		val crossingContribution = contribution(
			zone,
			crossingStartMs,
			crossingEndMs,
			setOf(1L),
		)
		val crossing = requireNotNull(
			StepsNumericDayWindowAccumulator.create(
				mapOf(firstDay to zone, firstDay + 1L to zone),
			),
		)
		crossing.addLogicalGroup(listOf(crossingContribution)) shouldBe true
		crossing.startRun(crossingContribution) shouldBe true
		crossing.consumeCoveredFact(
			fact(1L, crossingStartMs, crossingEndMs, steps = 5L),
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
		val contribution = contribution(zone, startMs, endMs, setOf(1L))
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

	@Test
	fun `fact wall time outside presentation still supplies its own civil authority`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val presentationStartMs = startOfDayMs(day, zone) + HOUR_MS
		val factStartMs = presentationStartMs + 4L * HOUR_MS
		val contribution = contribution(
			zone = zone,
			startMs = presentationStartMs,
			endMs = presentationStartMs + HOUR_MS,
			stepsManifestRevisions = setOf(1L),
		)
		val accumulator = requireNotNull(
			StepsNumericDayWindowAccumulator.create(mapOf(day to zone)),
		)

		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(
			fact(1L, factStartMs, factStartMs + HOUR_MS, steps = 7L),
		) shouldBe true
		accumulator.finishRun() shouldBe true

		val result = requireNotNull(accumulator.results()).single()
		result.exactSteps shouldBe 7L
		result.hasCompleteStepsCapture shouldBe true
		result.hasPartialStepsCapture shouldBe false
	}

	@Test
	fun `one exact run cannot mask a second captured run without an in-day fact`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStartMs = startOfDayMs(day, zone)
		val first = contribution(
			zone = zone,
			startMs = dayStartMs + HOUR_MS,
			endMs = dayStartMs + 2L * HOUR_MS,
			stepsManifestRevisions = setOf(1L),
		).copy(serviceRunId = "run-a")
		val second = contribution(
			zone = zone,
			startMs = dayStartMs + 3L * HOUR_MS,
			endMs = dayStartMs + 4L * HOUR_MS,
			stepsManifestRevisions = setOf(2L),
		).copy(
			serviceRunId = "run-b",
			logicalStartedAtMs = first.logicalStartedAtMs,
		)
		val accumulator = requireNotNull(
			StepsNumericDayWindowAccumulator.create(mapOf(day to zone)),
		)

		accumulator.addLogicalGroup(listOf(first, second)) shouldBe true
		accumulator.startRun(first) shouldBe true
		accumulator.consumeCoveredFact(
			fact(1L, first.segmentStartMs, first.segmentEndMs, steps = 7L).copy(
				serviceRunId = first.serviceRunId,
			),
		) shouldBe true
		accumulator.finishRun() shouldBe true
		accumulator.startRun(second) shouldBe true
		accumulator.consumeCoveredFact(
			fact(
				manifestRevision = 2L,
				startMs = dayStartMs + 25L * HOUR_MS,
				endMs = dayStartMs + 26L * HOUR_MS,
				steps = 11L,
			).copy(serviceRunId = second.serviceRunId),
		) shouldBe true
		accumulator.finishRun() shouldBe true

		val result = requireNotNull(accumulator.results()).single()
		result.exactSteps shouldBe 7L
		result.hasCompleteStepsCapture shouldBe true
		result.hasPartialStepsCapture shouldBe true
	}

	@Test
	fun `mixed manifest capture remains partial without inventing a wall boundary`() {
		val zone = ZoneId.of("UTC")
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val startMs = startOfDayMs(day, zone) + HOUR_MS
		val contribution = contribution(
			zone = zone,
			startMs = startMs,
			endMs = startMs + HOUR_MS,
			stepsManifestRevisions = setOf(2L),
			hasManifestWithoutStepsCapture = true,
		)
		val accumulator = requireNotNull(
			StepsNumericDayWindowAccumulator.create(mapOf(day to zone)),
		)

		accumulator.addLogicalGroup(listOf(contribution)) shouldBe true
		accumulator.startRun(contribution) shouldBe true
		accumulator.consumeCoveredFact(
			fact(2L, startMs, startMs + HOUR_MS, steps = 7L),
		) shouldBe true
		accumulator.finishRun() shouldBe true

		val result = requireNotNull(accumulator.results()).single()
		result.exactSteps shouldBe 7L
		result.hasCompleteStepsCapture shouldBe true
		result.hasNonStepsCapture shouldBe true
	}

	@Test
	fun `authoritative ambient total replaces rather than adds session total`() {
		val base = baseComposition(10L to StepsDayNumericComposition.Complete(5L))

		mergeAmbientNumericDays(base, listOf(ambientDay(10L, AmbientStepsHistoryValue.Exact(12L)))) shouldBe
			StepsNumericSummary.Ready(listOf(StepsNumericDay(10L, 12L)))
	}

	@Test
	fun `ambient absence keeps qualified session value`() {
		val base = baseComposition(10L to StepsDayNumericComposition.Complete(5L))
		val missing = AmbientStepsHistoryValue.Unavailable(
			setOf(AmbientStepsHistoryCause.NO_EVIDENCE),
		)

		mergeAmbientNumericDays(
			base,
			listOf(ambientDay(10L, missing)),
		) shouldBe StepsNumericSummary.Ready(listOf(StepsNumericDay(10L, 5L)))
	}

	@Test
	fun `mixed ambient and session days preserve one value per structural day`() {
		val base = baseComposition(
			10L to StepsDayNumericComposition.NotCaptured,
			11L to StepsDayNumericComposition.Complete(7L),
		)
		val missing = AmbientStepsHistoryValue.Unavailable(
			setOf(AmbientStepsHistoryCause.NO_EVIDENCE),
		)

		mergeAmbientNumericDays(
			base,
			listOf(
				ambientDay(10L, AmbientStepsHistoryValue.Exact(12L)),
				ambientDay(11L, missing),
			),
		) shouldBe StepsNumericSummary.Ready(
			listOf(StepsNumericDay(10L, 12L), StepsNumericDay(11L, 7L)),
		)
	}

	@Test
	fun `partial and materializing ambient authority never fall back to session zero`() {
		val base = baseComposition(10L to StepsDayNumericComposition.Complete(0L))

		mergeAmbientNumericDays(
			base,
			listOf(
				ambientDay(
					10L,
					AmbientStepsHistoryValue.Partial(
						0L,
						setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE),
					),
				),
			),
		) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
		)
		mergeAmbientNumericDays(
			base,
			listOf(
				ambientDay(
					10L,
					AmbientStepsHistoryValue.Unavailable(
						setOf(AmbientStepsHistoryCause.MATERIALIZING),
					),
				),
			),
		) shouldBe StepsNumericSummary.Materializing
	}

	@Test
	fun `ambient range overflow is unverifiable instead of wrapped`() {
		val base = baseComposition(
			10L to StepsDayNumericComposition.NotCaptured,
			11L to StepsDayNumericComposition.NotCaptured,
		)

		mergeAmbientNumericDays(
			base,
			listOf(
				ambientDay(10L, AmbientStepsHistoryValue.Exact(Long.MAX_VALUE)),
				ambientDay(11L, AmbientStepsHistoryValue.Exact(1L)),
			),
		) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
	}

	private fun contribution(
		zone: ZoneId,
		startMs: Long,
		endMs: Long,
		stepsManifestRevisions: Set<Long>,
		hasManifestWithoutStepsCapture: Boolean = false,
	) = StepsNumericRunContribution(
		serviceRunId = RUN_ID,
		logicalTrackingId = "logical",
		logicalStartedAtMs = startMs,
		capturedZoneId = zone,
		segmentStartMs = startMs,
		segmentEndMs = endMs,
		distanceM = 0f,
		stepsManifestRevisions = stepsManifestRevisions,
		hasManifestWithoutStepsCapture = hasManifestWithoutStepsCapture,
	)

	private data class FactInterval(
		val manifestRevision: Long,
		val startMs: Long,
		val endMs: Long,
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

	private fun ambientDay(
		epochDay: Long,
		total: AmbientStepsHistoryValue,
	): AmbientStepsNumericHistoryDay =
		AmbientStepsNumericHistoryDay(
			day = AmbientStepsStructuralDay(epochDay, "UTC"),
			total = total,
		)

	private fun baseComposition(
		vararg values: Pair<Long, StepsDayNumericComposition>,
	): StepsDayRepairPreflight = StepsDayRepairPreflight.Ready(
		values.map { (epochDay, value) ->
			StepsDayRepairPlan(
				epochDay = epochDay,
				zoneId = ZoneId.of("UTC"),
				totals = null,
				numericSteps = value,
			)
		},
	)

	private companion object {
		const val RUN_ID = "run"
		const val FACTS_PER_DAY = 257
		const val MINUTE_MS = 60_000L
		const val HOUR_MS = 60L * MINUTE_MS
	}
}
