package com.adsamcik.tracker.stats.engine.heatmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.random.Random

class SpatiallySupportedTimeEstimatorTest {
	private val estimator = SpatiallySupportedTimeEstimator(
		config = SpatiallySupportedTimeConfig(
			freshnessBeforeMs = 60_000L,
			freshnessAfterMs = 60_000L,
		),
		kernelPolicy = SpatialKernelPolicy { evidence ->
			if (evidence.precision != LocationPrecision.PRECISE || evidence.reportedHorizontalAccuracyM == null) {
				null
			} else {
				NormalizedCompactKernel(
					centerLatitudeDegrees = evidence.latitudeDegrees,
					centerLongitudeDegrees = evidence.longitudeDegrees,
					supportRadiusM = evidence.reportedHorizontalAccuracyM,
					version = 1,
				)
			}
		},
	)

	@Test
	fun `canonical time is the exact union of overlapping active spans`() {
		val result = estimator.estimate(
			request = HeatmapInstantRange(0L, 1_000L),
			activeSpans = listOf(
				TrackerActiveSpan(100L, 600L, clockDomainId = 1L),
				TrackerActiveSpan(400L, 900L, clockDomainId = 1L),
			),
			evidence = emptyList(),
		)

		assertEquals(800L, result.canonicalTrackedMs)
		assertEquals(0L, result.supportedMs)
		assertEquals(800L, result.unresolvedMs)
		assertEquals(listOf(SpatialSupportInterval.Unresolved(100L, 900L)), result.intervals)
	}

	@Test
	fun `one fix cannot paint a long tracker run`() {
		val result = estimateRun(
			runEndMs = 3_600_000L,
			evidence = listOf(fix(id = 1L, timeMs = 1_800_000L)),
		)

		assertEquals(3_600_000L, result.canonicalTrackedMs)
		assertEquals(120_000L, result.supportedMs)
		assertEquals(3_480_000L, result.unresolvedMs)
		assertEquals(3_600_000L, result.supportedMs + result.unresolvedMs)
	}

	@Test
	fun `several-minute gap remains unresolved beyond bounded freshness`() {
		val result = estimateRun(
			runEndMs = 600_000L,
			evidence = listOf(fix(1L, 60_000L), fix(2L, 540_000L)),
		)

		assertEquals(240_000L, result.supportedMs)
		assertEquals(360_000L, result.unresolvedMs)
		val middle = result.intervals.single { it.startMs == 120_000L }
		assertEquals(480_000L, middle.endMsExclusive)
		assertIs<SpatialSupportInterval.Unresolved>(middle)
	}

	@Test
	fun `duplicate observations do not multiply supported time`() {
		val once = estimateRun(300_000L, listOf(fix(1L, 150_000L)))
		val duplicated = estimateRun(
			300_000L,
			listOf(fix(1L, 150_000L), fix(2L, 150_000L)),
		)

		assertEquals(once.supportedMs, duplicated.supportedMs)
		assertEquals(once.unresolvedMs, duplicated.unresolvedMs)
		assertEquals(once.intervals, duplicated.intervals)
	}

	@Test
	fun `informative observations move time from unresolved without changing tracked time`() {
		val sparse = estimateRun(300_000L, listOf(fix(1L, 60_000L)))
		val informative = estimateRun(
			300_000L,
			listOf(fix(1L, 60_000L), fix(2L, 240_000L)),
		)

		assertEquals(sparse.canonicalTrackedMs, informative.canonicalTrackedMs)
		assertEquals(300_000L, informative.supportedMs + informative.unresolvedMs)
		assertEquals(120_000L, sparse.supportedMs)
		assertEquals(240_000L, informative.supportedMs)
	}

	@Test
	fun `input ordering has no effect`() {
		val evidence = listOf(fix(3L, 240_000L), fix(1L, 60_000L), fix(2L, 150_000L))
		val spans = listOf(
			TrackerActiveSpan(100_000L, 300_000L, 7L),
			TrackerActiveSpan(0L, 180_000L, 7L),
		)
		val first = estimator.estimate(HeatmapInstantRange(0L, 300_000L), spans, evidence)
		val second = estimator.estimate(
			HeatmapInstantRange(0L, 300_000L),
			spans.reversed(),
			evidence.reversed(),
		)

		assertEquals(first, second)
	}

	@Test
	fun `conflicting same-time locations are unresolved`() {
		val result = estimateRun(
			runEndMs = 300_000L,
			evidence = listOf(
				fix(1L, 150_000L, latitude = 50.0, longitude = 14.0),
				fix(2L, 150_000L, latitude = 51.0, longitude = 15.0),
			),
		)

		assertEquals(0L, result.supportedMs)
		assertEquals(300_000L, result.unresolvedMs)
	}

	@Test
	fun `freshness never crosses a clock-domain boundary`() {
		val result = estimator.estimate(
			request = HeatmapInstantRange(0L, 300_000L),
			activeSpans = listOf(
				TrackerActiveSpan(0L, 150_000L, clockDomainId = 1L),
				TrackerActiveSpan(150_000L, 300_000L, clockDomainId = 2L),
			),
			evidence = listOf(fix(1L, 140_000L, clockDomainId = 1L)),
		)

		assertEquals(70_000L, result.supportedMs)
		assertEquals(230_000L, result.unresolvedMs)
		assertIs<SpatialSupportInterval.Unresolved>(result.intervals.last())
		assertEquals(150_000L, result.intervals.last().startMs)
	}

	@Test
	fun `approximate evidence remains unresolved when policy has no calibrated broad model`() {
		val result = estimateRun(
			runEndMs = 120_000L,
			evidence = listOf(fix(1L, 60_000L, precision = LocationPrecision.APPROXIMATE)),
		)

		assertEquals(0L, result.supportedMs)
		assertEquals(120_000L, result.unresolvedMs)
	}

	@Test
	fun `generated traces conserve time and are invariant to order and redundant evidence`() {
		val random = Random(0x51A71A1)
		repeat(200) {
			val spans = List(random.nextInt(1, 7)) {
				val start = random.nextLong(0L, 900_000L)
				TrackerActiveSpan(
					startMs = start,
					endMsExclusive = start + random.nextLong(1L, 180_000L),
					clockDomainId = random.nextLong(1L, 4L),
				)
			}
			val evidence = List(random.nextInt(0, 20)) { index ->
				fix(
					id = index.toLong() + 1L,
					timeMs = random.nextLong(0L, 1_000_000L),
					latitude = 49.9 + random.nextDouble() * 0.2,
					longitude = 13.9 + random.nextDouble() * 0.2,
					clockDomainId = random.nextLong(1L, 4L),
				)
			}
			val request = HeatmapInstantRange(0L, 1_000_000L)
			val baseline = estimator.estimate(request, spans, evidence)
			val reordered = estimator.estimate(request, spans.shuffled(random), evidence.shuffled(random))
			val redundant = estimator.estimate(request, spans, evidence + evidence.shuffled(random))

			assertEquals(baseline.canonicalTrackedMs, baseline.supportedMs + baseline.unresolvedMs)
			assertEquals(baseline.canonicalTrackedMs, baseline.intervals.sumOf { it.durationMs })
			assertTrue(baseline.intervals.zipWithNext().all { (first, second) ->
				first.endMsExclusive <= second.startMs
			})
			assertEquals(baseline, reordered)
			assertEquals(baseline, redundant)
		}
	}

	private fun estimateRun(
		runEndMs: Long,
		evidence: List<LocationEvidence>,
	): SpatiallySupportedTimeResult = estimator.estimate(
		request = HeatmapInstantRange(0L, runEndMs),
		activeSpans = listOf(TrackerActiveSpan(0L, runEndMs, clockDomainId = 1L)),
		evidence = evidence,
	)

	private fun fix(
		id: Long,
		timeMs: Long,
		latitude: Double = 50.0,
		longitude: Double = 14.0,
		clockDomainId: Long = 1L,
		precision: LocationPrecision = LocationPrecision.PRECISE,
	): LocationEvidence = LocationEvidence(
		stableId = id,
		timeMs = timeMs,
		clockDomainId = clockDomainId,
		latitudeDegrees = latitude,
		longitudeDegrees = longitude,
		reportedHorizontalAccuracyM = 20.0,
		precision = precision,
	)
}
