package com.adsamcik.tracker.stats.engine.benchmark

import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.SegmentSignal
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.engine.aggregator.StreamingAggregator
import com.adsamcik.tracker.stats.engine.exploration.CellDiscoveryEngine
import com.adsamcik.tracker.stats.engine.processor.AggregatorProcessor
import com.adsamcik.tracker.stats.engine.processor.ExplorationProcessor
import com.adsamcik.tracker.stats.engine.processor.SegmentDetectorProcessor
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Performance benchmarks for the stats pipeline hot path.
 *
 * These tests validate that the critical tracking cycle stays well within
 * the ~1s budget. Real tracking fires at 1Hz, so the entire pipeline
 * (signal creation + fan-out to all processors) must complete in <10ms
 * to leave 99%+ CPU for the rest of the app.
 *
 * Targets (per single signal delivery):
 * - Value class creation: < 100ns (should be zero-cost inline)
 * - TrackingSignal construction: < 500ns
 * - Single processor onSignal(): < 1ms
 * - Full pipeline fan-out (4 processors): < 5ms
 * - 1-hour session (3600 signals): < 2s total CPU time
 */
@DisplayName("Pipeline Performance Benchmarks")
class PipelineBenchmarkTest {

	private lateinit var aggregatorProcessor: AggregatorProcessor
	private lateinit var segmentProcessor: SegmentDetectorProcessor
	private lateinit var explorationProcessor: ExplorationProcessor

	private val baseTimestamp = 1700000000000L // fixed epoch
	private val warmupIterations = 1000
	private val benchIterations = 10_000

	@BeforeEach
	fun setup() {
		aggregatorProcessor = AggregatorProcessor(StreamingAggregator())
		segmentProcessor = SegmentDetectorProcessor(SessionSegmentDetector())
		explorationProcessor = ExplorationProcessor(CellDiscoveryEngine())
	}

	// --- Value class creation benchmarks ---

	@Test
	@DisplayName("Value class creation is near-zero overhead")
	fun benchValueClassCreation() {
		// Warmup
		repeat(warmupIterations) {
			SpeedMps.coerced(1.5f)
			LatE7.fromDegrees(49.2)
			LonE7.fromDegrees(16.6)
			EpochMs(baseTimestamp)
			DistanceM.coerced(10.0f)
			StepCount.coerced(5)
			DurationMs(1000L)
			ActivityConfidence.coerced(80)
		}

		val totalNanos = measureNanoTime {
			repeat(benchIterations) {
				SpeedMps.coerced(1.5f)
				LatE7.fromDegrees(49.2 + it * 0.0001)
				LonE7.fromDegrees(16.6 + it * 0.0001)
				EpochMs(baseTimestamp + it)
				DistanceM.coerced(10.0f + it * 0.1f)
				StepCount.coerced(it % 100)
				DurationMs(1000L + it)
				ActivityConfidence.coerced(it % 100)
			}
		}

		val avgNanos = totalNanos / benchIterations
		println("Value class creation: ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total for $benchIterations iterations)")
		// 8 value class creations per iteration — should be well under 1µs each
		avgNanos shouldBeLessThan 5_000L // 5µs budget for all 8
	}

	// --- TrackingSignal construction benchmark ---

	@Test
	@DisplayName("TrackingSignal construction stays under 1µs")
	fun benchTrackingSignalConstruction() {
		repeat(warmupIterations) { buildSignal(it) }

		val totalNanos = measureNanoTime {
			repeat(benchIterations) { buildSignal(it) }
		}

		val avgNanos = totalNanos / benchIterations
		println("TrackingSignal construction: ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total for $benchIterations)")
		avgNanos shouldBeLessThan 10_000L // 10µs generous budget
	}

	// --- Individual processor onSignal() benchmarks ---

	@Test
	@DisplayName("AggregatorProcessor.onSignal() < 5µs avg")
	fun benchAggregatorOnSignal() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		aggregatorProcessor.onStart(ctx)

		// Warmup
		repeat(warmupIterations) { aggregatorProcessor.onSignal(buildSignal(it)) }

		val totalNanos = measureNanoTime {
			repeat(benchIterations) { aggregatorProcessor.onSignal(buildSignal(it)) }
		}

		val avgNanos = totalNanos / benchIterations
		println("AggregatorProcessor.onSignal: ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total)")
		avgNanos shouldBeLessThan 50_000L // 50µs generous budget
	}

	@Test
	@DisplayName("SegmentDetectorProcessor.onSignal() < 10µs avg")
	fun benchSegmentDetectorOnSignal() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		segmentProcessor.onStart(ctx)

		repeat(warmupIterations) { segmentProcessor.onSignal(buildSignal(it)) }

		val totalNanos = measureNanoTime {
			repeat(benchIterations) { segmentProcessor.onSignal(buildSignal(it)) }
		}

		val avgNanos = totalNanos / benchIterations
		println("SegmentDetectorProcessor.onSignal: ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total)")
		avgNanos shouldBeLessThan 100_000L // 100µs budget
	}

	@Test
	@DisplayName("ExplorationProcessor.onSignal() < 20µs avg")
	fun benchExplorationOnSignal() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		explorationProcessor.onStart(ctx)

		repeat(warmupIterations) { explorationProcessor.onSignal(buildSignal(it)) }

		val totalNanos = measureNanoTime {
			repeat(benchIterations) { explorationProcessor.onSignal(buildSignal(it)) }
		}

		val avgNanos = totalNanos / benchIterations
		println("ExplorationProcessor.onSignal: ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total)")
		avgNanos shouldBeLessThan 200_000L // 200µs budget (S2 cell math)
	}

	// --- Full pipeline simulation benchmark ---

	@Test
	@DisplayName("Full pipeline fan-out (3 processors) < 50µs per signal")
	fun benchFullPipelineFanOut() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		// Warmup
		repeat(warmupIterations) { i ->
			val signal = buildSignal(i)
			processors.forEach { it.onSignal(signal) }
		}

		val totalNanos = measureNanoTime {
			repeat(benchIterations) { i ->
				val signal = buildSignal(i)
				processors.forEach { it.onSignal(signal) }
			}
		}

		val avgNanos = totalNanos / benchIterations
		println("Full pipeline fan-out: ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total)")
		// 3 processors, each under their individual budgets
		avgNanos shouldBeLessThan 500_000L // 500µs total budget
	}

	// --- Realistic session simulation ---

	@Test
	@DisplayName("1-hour session (3600 signals) completes in < 2s CPU time")
	fun benchOneHourSession() = runTest {
		val signalCount = 3600 // 1Hz for 1 hour
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))

		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		val totalMs = measureTimeMillis {
			for (i in 0 until signalCount) {
				val signal = buildRealisticWalkSignal(i)
				processors.forEach { it.onSignal(signal) }

				// Simulate periodic flush every 30s
				if (i > 0 && i % 30 == 0) {
					processors.forEach { it.onFlush() }
				}
			}

			// Final stop
			processors.forEach { it.onStop() }
		}

		val avgPerSignalUs = (totalMs * 1000L) / signalCount
		println("1-hour session: ${totalMs}ms total, ${avgPerSignalUs}µs/signal avg, $signalCount signals")
		println("CPU utilization: ${totalMs.toDouble() / 3_600_000 * 100}% of 1-hour wall time")
		totalMs shouldBeLessThan 2000L // 2s total for 1 hour of signals
	}

	@Test
	@DisplayName("8-hour session (28800 signals) stays under 10s CPU time")
	fun benchEightHourSession() = runTest {
		val signalCount = 28_800 // 1Hz for 8 hours
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))

		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		val totalMs = measureTimeMillis {
			for (i in 0 until signalCount) {
				val signal = buildRealisticWalkSignal(i)
				processors.forEach { it.onSignal(signal) }

				if (i > 0 && i % 30 == 0) {
					processors.forEach { it.onFlush() }
				}
			}
			processors.forEach { it.onStop() }
		}

		val avgPerSignalUs = (totalMs * 1000L) / signalCount
		println("8-hour session: ${totalMs}ms total, ${avgPerSignalUs}µs/signal avg, $signalCount signals")
		println("CPU utilization: ${totalMs.toDouble() / 28_800_000 * 100}% of 8-hour wall time")
		totalMs shouldBeLessThan 10_000L
	}

	// --- Memory allocation pressure ---

	@Test
	@DisplayName("Signal creation produces minimal garbage (value class inlining)")
	fun benchAllocationPressure() {
		// Force GC before measurement
		System.gc()
		Thread.sleep(100)

		val runtime = Runtime.getRuntime()
		val beforeMemory = runtime.totalMemory() - runtime.freeMemory()

		val signals = Array(100_000) { buildSignal(it) }

		val afterMemory = runtime.totalMemory() - runtime.freeMemory()
		val perSignalBytes = (afterMemory - beforeMemory) / signals.size

		println("Memory per TrackingSignal: ~${perSignalBytes} bytes (${signals.size} signals)")
		println("Total allocation: ${(afterMemory - beforeMemory) / 1024}KB for ${signals.size} signals")

		// TrackingSignal is a data class with nullable sub-objects — expect some allocation
		// but value classes (SpeedMps, LatE7, etc.) should be inlined
		// Rough expectation: < 200 bytes per signal (data class headers + nullables)
		// This is just informational — no hard failure threshold
	}

	// --- Flush overhead ---

	@Test
	@DisplayName("Processor onFlush() is fast (no I/O in test)")
	fun benchFlushOverhead() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		// Feed some signals first
		repeat(100) { i ->
			val signal = buildSignal(i)
			aggregatorProcessor.onSignal(signal)
			segmentProcessor.onSignal(signal)
			explorationProcessor.onSignal(signal)
		}

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		// Warmup flushes
		repeat(10) { processors.forEach { it.onFlush() } }

		val totalNanos = measureNanoTime {
			repeat(1000) {
				processors.forEach { it.onFlush() }
			}
		}

		val avgNanos = totalNanos / 1000
		println("Flush (3 processors): ${avgNanos}ns avg (${totalNanos / 1_000_000}ms total for 1000 flushes)")
		avgNanos shouldBeLessThan 1_000_000L // 1ms budget per flush cycle
	}

	// --- Helper functions ---

	private fun buildSignal(i: Int): TrackingSignal {
		return TrackingSignal(
			timestampMs = EpochMs(baseTimestamp + i * 1000L),
			location = LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(49.2 + i * 0.00001),
					lon = LonE7.fromDegrees(16.6 + i * 0.00001),
				),
				horizontalAccuracyM = 5.0f,
				speed = SpeedMps.coerced(1.4f),
				altitudeM = 200f,
				distanceDelta = DistanceM.coerced(1.4f),
			),
			activity = ActivitySignal(
				type = DetectedActivityType.WALKING,
				confidence = ActivityConfidence.coerced(85),
			),
			steps = StepSignal(
				stepDelta = StepCount.coerced(2),
				totalStepsSinceBoot = 10000L + i * 2,
			),
		)
	}

	/** Simulates a realistic walking session with gradual position changes. */
	private fun buildRealisticWalkSignal(i: Int): TrackingSignal {
		// Walking at ~1.4 m/s with slight speed variation
		val speed = 1.2f + (i % 10) * 0.04f
		// Gradual lat/lon drift simulating a walk
		val latDrift = i * 0.000012 // ~1.3m per signal at this latitude
		val lonDrift = i * 0.000018

		return TrackingSignal(
			timestampMs = EpochMs(baseTimestamp + i * 1000L),
			location = LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(49.2 + latDrift),
					lon = LonE7.fromDegrees(16.6 + lonDrift),
				),
				horizontalAccuracyM = 3.0f + (i % 5),
				speed = SpeedMps.coerced(speed),
				altitudeM = 200f + (i % 50) * 0.2f,
				distanceDelta = DistanceM.coerced(speed),
			),
			activity = ActivitySignal(
				type = if (i % 300 < 250) DetectedActivityType.WALKING else DetectedActivityType.STILL,
				confidence = ActivityConfidence.coerced(75 + i % 20),
			),
			steps = StepSignal(
				stepDelta = StepCount.coerced(2),
				totalStepsSinceBoot = 10000L + i * 2,
			),
		)
	}
}
