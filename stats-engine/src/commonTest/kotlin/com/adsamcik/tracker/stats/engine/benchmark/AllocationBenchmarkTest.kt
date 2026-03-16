package com.adsamcik.tracker.stats.engine.benchmark

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
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
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * Allocation-focused benchmarks for the stats pipeline.
 *
 * Validates that the per-cycle object creation stays low enough to avoid
 * GC pressure on Android (especially important at 1Hz sustained).
 *
 * Key concerns:
 * - Nullable value classes (SpeedMps?, DistanceM?) cause boxing
 * - Intermediate data classes (AggregatorSignal, SegmentSignal) per cycle
 * - CoordinateE7 is data class (2-field, can't be value class)
 * - GC pauses during tracking could cause dropped samples
 */
@DisplayName("Allocation Benchmarks")
class AllocationBenchmarkTest {

	private val baseTimestamp = 1700000000000L

	private lateinit var aggregatorProcessor: AggregatorProcessor
	private lateinit var segmentProcessor: SegmentDetectorProcessor
	private lateinit var explorationProcessor: ExplorationProcessor

	@BeforeEach
	fun setup() {
		aggregatorProcessor = AggregatorProcessor(StreamingAggregator())
		segmentProcessor = SegmentDetectorProcessor(SessionSegmentDetector())
		explorationProcessor = ExplorationProcessor(CellDiscoveryEngine())
	}

	// ────────── Allocation measurement helper ──────────

	/**
	 * Uses ThreadMXBean to get exact thread-allocated bytes (HotSpot JVM).
	 * Returns null if the JVM doesn't support it.
	 */
	private fun getThreadAllocatedBytes(): Long? {
		return try {
			val bean = ManagementFactory.getThreadMXBean()
			val method = bean.javaClass.getMethod("getThreadAllocatedBytes", Long::class.java)
			method.invoke(bean, Thread.currentThread().id) as Long
		} catch (_: Exception) {
			null
		}
	}

	/**
	 * Measures bytes allocated by [block] using ThreadMXBean.
	 * Falls back to GC + freeMemory delta if not available.
	 */
	private inline fun measureAllocations(warmup: Int = 5, iterations: Int, block: () -> Unit): AllocationResult {
		// Warmup — JIT compile, class loading, etc.
		repeat(warmup) { block() }

		val threadAlloc = getThreadAllocatedBytes()
		return if (threadAlloc != null) {
			measureWithThreadBean(iterations, block)
		} else {
			measureWithGcDelta(iterations, block)
		}
	}

	private inline fun measureWithThreadBean(iterations: Int, block: () -> Unit): AllocationResult {
		val bean = ManagementFactory.getThreadMXBean()
		val method = bean.javaClass.getMethod("getThreadAllocatedBytes", Long::class.java)
		val tid = Thread.currentThread().id

		val before = method.invoke(bean, tid) as Long
		repeat(iterations) { block() }
		val after = method.invoke(bean, tid) as Long

		val total = after - before
		return AllocationResult(
			totalBytes = total,
			perIterationBytes = total / iterations,
			iterations = iterations,
			method = "ThreadMXBean",
		)
	}

	private inline fun measureWithGcDelta(iterations: Int, block: () -> Unit): AllocationResult {
		val runtime = Runtime.getRuntime()
		// Force GC to get a clean baseline
		repeat(3) { System.gc(); Thread.sleep(50) }

		val beforeUsed = runtime.totalMemory() - runtime.freeMemory()
		repeat(iterations) { block() }
		val afterUsed = runtime.totalMemory() - runtime.freeMemory()

		val total = (afterUsed - beforeUsed).coerceAtLeast(0)
		return AllocationResult(
			totalBytes = total,
			perIterationBytes = total / iterations,
			iterations = iterations,
			method = "GC+freeMemory (approximate)",
		)
	}

	data class AllocationResult(
		val totalBytes: Long,
		val perIterationBytes: Long,
		val iterations: Int,
		val method: String,
	) {
		val totalKB get() = totalBytes / 1024
		fun report(label: String) {
			println("$label: ${perIterationBytes}B/iter (${totalKB}KB total, $iterations iters, via $method)")
		}
	}

	// ────────── Value class boxing tests ──────────

	@Test
	@DisplayName("Non-nullable value classes inline (zero allocation)")
	fun benchNonNullableValueClassInlining() {
		val result = measureAllocations(iterations = 100_000) {
			// These should inline to raw primitives — zero heap allocation
			val speed = SpeedMps.coerced(1.5f)
			val lat = LatE7.fromDegrees(49.2)
			val lon = LonE7.fromDegrees(16.6)
			val ts = EpochMs(baseTimestamp)
			val steps = StepCount.coerced(5)
			val conf = ActivityConfidence.coerced(80)
			// Use the values to prevent dead-code elimination
			check(speed.raw.toDouble() + lat.raw + lon.raw + ts.raw + steps.raw + conf.raw != 0.0)
		}
		result.report("Non-nullable value classes (6 types)")
		// Should be very close to 0 bytes per iteration
		result.perIterationBytes shouldBeLessThan 32L
	}

	@Test
	@DisplayName("Nullable value classes cause boxing (~16 bytes each)")
	fun benchNullableValueClassBoxing() {
		// When stored as nullable, value classes get boxed to heap objects
		val result = measureAllocations(iterations = 100_000) {
			val speed: SpeedMps? = SpeedMps.coerced(1.5f) // BOXED
			val dist: DistanceM? = DistanceM.coerced(10.0f) // BOXED
			val alt: Float? = 200f // BOXED
			// Use the values
			check((speed?.raw ?: 0f) + (dist?.raw ?: 0f) + (alt ?: 0f) != -1f)
		}
		result.report("Nullable value classes (3 boxed)")
		// Each nullable box is ~16 bytes on 64-bit JVM (object header + field)
		// 3 boxes = ~48 bytes + allocation overhead
		println("  → Expected: ~48-96 bytes/iter from boxing (SpeedMps?, DistanceM?, Float?)")
	}

	// ────────── Per-component allocation breakdown ──────────

	@Test
	@DisplayName("TrackingSignal construction allocation breakdown")
	fun benchTrackingSignalAllocations() {
		val result = measureAllocations(iterations = 100_000) {
			buildFullSignal(0)
		}
		result.report("TrackingSignal (full, with all sub-signals)")
		// Expected allocations per signal:
		// - TrackingSignal: ~32B (header + 4 fields)
		// - LocationSignal: ~40B (header + 5 fields including boxed nullables)
		// - CoordinateE7: ~24B (data class, 2 int fields)
		// - ActivitySignal: ~24B (header + 2 fields, value classes inline here)
		// - StepSignal: ~24B (header + int + long)
		// - SpeedMps? box: ~16B
		// - DistanceM? box: ~16B
		// - Float? box for altitudeM: ~16B
		// Total: ~192 bytes expected
		println("  → Budget: <256 bytes/signal (7 objects + 3 boxed nullable value classes)")
		result.perIterationBytes shouldBeLessThan 512L // generous for JVM overhead
	}

	@Test
	@DisplayName("TrackingSignal without location (minimal allocation)")
	fun benchMinimalSignalAllocations() {
		val result = measureAllocations(iterations = 100_000) {
			TrackingSignal(timestampMs = EpochMs(baseTimestamp))
		}
		result.report("TrackingSignal (empty, no sub-signals)")
		// Just one data class with 3 null fields + 1 inlined EpochMs
		println("  → Budget: <48 bytes/signal (1 object, no boxing)")
		result.perIterationBytes shouldBeLessThan 96L
	}

	@Test
	@DisplayName("AggregatorProcessor.onSignal() allocation (intermediate AggregatorSignal)")
	fun benchAggregatorOnSignalAllocations() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		aggregatorProcessor.onStart(ctx)

		// Warmup
		repeat(1000) { aggregatorProcessor.onSignal(buildFullSignal(it)) }

		val result = measureAllocations(iterations = 50_000) {
			aggregatorProcessor.onSignal(buildFullSignal(0))
		}
		result.report("AggregatorProcessor.onSignal()")
		// Creates AggregatorSignal (data class) with nullable Float? boxes
		// Plus internal aggregator state updates
		println("  → AggregatorSignal: ~48B + Float? boxing for distanceDeltaM, speedMps")
		result.perIterationBytes shouldBeLessThan 384L
	}

	@Test
	@DisplayName("SegmentDetectorProcessor.onSignal() allocation (intermediate SegmentSignal)")
	fun benchSegmentDetectorOnSignalAllocations() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		segmentProcessor.onStart(ctx)

		repeat(1000) { segmentProcessor.onSignal(buildFullSignal(it)) }

		val result = measureAllocations(iterations = 50_000) {
			segmentProcessor.onSignal(buildFullSignal(0))
		}
		result.report("SegmentDetectorProcessor.onSignal()")
		// Creates SegmentSignal with many nullable primitives (Int?, Float?)
		println("  → SegmentSignal: ~80B + boxing for 6 nullable fields")
		result.perIterationBytes shouldBeLessThan 384L
	}

	@Test
	@DisplayName("ExplorationProcessor.onSignal() allocation (no intermediate object)")
	fun benchExplorationOnSignalAllocations() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		explorationProcessor.onStart(ctx)

		repeat(1000) { explorationProcessor.onSignal(buildFullSignal(it)) }

		val result = measureAllocations(iterations = 50_000) {
			explorationProcessor.onSignal(buildFullSignal(0))
		}
		result.report("ExplorationProcessor.onSignal()")
		// Mostly pass-through, but still pays for CellDiscovered event allocation plus
		// a small amount of CellDiscoveryEngine internal state churn (~285B observed on JVM).
		result.perIterationBytes shouldBeLessThan 320L
	}

	// ────────── Full pipeline allocation per cycle ──────────

	@Test
	@DisplayName("Full pipeline: total allocation per signal cycle")
	fun benchFullCycleAllocations() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		// Warmup
		repeat(1000) { i ->
			val signal = buildFullSignal(i)
			processors.forEach { it.onSignal(signal) }
		}

		val result = measureAllocations(iterations = 50_000) {
			val signal = buildFullSignal(0)
			processors.forEach { it.onSignal(signal) }
		}
		result.report("Full pipeline cycle (signal + 3 processors)")
		// Signal creation: ~200B
		// AggregatorProcessor: ~100B (AggregatorSignal + boxes)
		// SegmentDetectorProcessor: ~150B (SegmentSignal + boxes)
		// ExplorationProcessor: ~50B (minimal, pass-through)
		// Total: ~500B expected
		println("  → Budget: <1KB/cycle. At 1Hz = <1KB/s = <3.6MB/hour")
		println("  → Android typical young gen: 2-4MB. GC every ${4_000_000L / result.perIterationBytes.coerceAtLeast(1)}s")
		result.perIterationBytes shouldBeLessThan 1536L // 1.5KB generous
	}

	// ────────── GC pressure simulation ──────────

	@Test
	@DisplayName("1-hour sustained tracking: GC pressure is manageable")
	fun benchGcPressureOneHour() = runTest {
		val signalCount = 3600 // 1Hz for 1 hour
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))

		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		// Measure total allocation for full session
		val result = measureAllocations(warmup = 0, iterations = 1) {
			for (i in 0 until signalCount) {
				val signal = buildWalkSignal(i)
				processors.forEach { it.onSignal(signal) }
				if (i > 0 && i % 30 == 0) {
					processors.forEach { it.onFlush() }
				}
			}
			processors.forEach { it.onStop() }
		}
		val totalMB = result.totalBytes / (1024.0 * 1024.0)
		val perSecondKB = result.totalBytes / (1024.0 * signalCount)
		println("1-hour session allocation: ${"%.2f".format(totalMB)}MB total, ${"%.2f".format(perSecondKB)}KB/s")
		println("  → Android young-gen (2-4MB) fills every ${"%.0f".format(4_000_000.0 / perSecondKB / 1024)}s")
		println("  → Minor GC cost: ~2-5ms on modern phones (negligible at 1Hz)")
		// 1 hour should be under 10MB total allocation
		result.totalBytes shouldBeLessThan 10L * 1024 * 1024
	}

	@Test
	@DisplayName("Flush cycle allocation (domain event lists)")
	fun benchFlushAllocations() = runTest {
		val ctx = ProcessorContext(startTimestamp = EpochMs(baseTimestamp))
		aggregatorProcessor.onStart(ctx)
		segmentProcessor.onStart(ctx)
		explorationProcessor.onStart(ctx)

		// Feed signals to build state
		repeat(100) { i ->
			val signal = buildFullSignal(i)
			aggregatorProcessor.onSignal(signal)
			segmentProcessor.onSignal(signal)
			explorationProcessor.onSignal(signal)
		}

		val processors = listOf(aggregatorProcessor, segmentProcessor, explorationProcessor)

		val result = measureAllocations(iterations = 10_000) {
			processors.forEach { it.onFlush() }
		}
		result.report("Flush cycle (3 processors)")
		// Flush creates DomainEvent objects + List wrappers
		// At 30s intervals, this is infrequent enough to not matter
		result.perIterationBytes shouldBeLessThan 2048L // 2KB per flush
	}

	// ────────── Helpers ──────────

	private fun buildFullSignal(i: Int): TrackingSignal = TrackingSignal(
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

	private fun buildWalkSignal(i: Int): TrackingSignal {
		val speed = 1.2f + (i % 10) * 0.04f
		return TrackingSignal(
			timestampMs = EpochMs(baseTimestamp + i * 1000L),
			location = LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(49.2 + i * 0.000012),
					lon = LonE7.fromDegrees(16.6 + i * 0.000018),
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
