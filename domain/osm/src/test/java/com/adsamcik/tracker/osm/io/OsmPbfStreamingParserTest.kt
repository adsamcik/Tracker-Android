package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.logging.api.ErrorReporter
import com.adsamcik.tracker.logging.api.ReporterFacade
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Behavioural guard tests for [OsmPbfStreamingParser].
 *
 * Round-6 perf review (R2 round-6) requested explicit verification that the
 * parser refuses to load arbitrarily large PBF files into memory in a single
 * gulp. Round-7 then required the per-instance referenced-node cap to scale
 * with the JVM heap so 256 MB devices no longer hit OOM mid-import. These
 * tests pin both contracts so a future refactor can't silently lift the
 * ceiling or break adaptive scaling.
 */
@DisplayName("OsmPbfStreamingParser")
class OsmPbfStreamingParserTest {

	@AfterEach
	fun clearReporterDelegate() {
		// ReporterFacade.delegate is a process-wide static; reset so tests
		// don't bleed log expectations into each other (or into sibling
		// modules running in the same VM).
		val field = ReporterFacade::class.java.getDeclaredField("delegate")
		field.isAccessible = true
		field.set(ReporterFacade, null)
	}

	@Test
	fun `rejects files larger than the 100 MB streaming cap before opening the stream`() = runTest {
		val parser = OsmPbfStreamingParser()
		var openCount = 0
		val oversize = OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES + 1

		val ex = shouldThrow<OsmParseException.FileTooLarge> {
			parser.parse(
				fileSizeBytes = oversize,
				openInputStream = {
					// The guard must trip BEFORE we touch the input stream — any
					// attempt to open it indicates the size check has regressed
					// into the streaming loop, which means an attacker-controlled
					// .osm.pbf could pull arbitrary bytes into the parser.
					openCount++
					ByteArrayInputStream(ByteArray(0))
				},
				onWayBatch = { /* never invoked */ },
			)
		}

		ex.sizeBytes shouldBe oversize
		ex.limitBytes shouldBe OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES
		openCount shouldBe 0
	}

	@Test
	fun `streaming cap is the documented 100 MB ceiling`() {
		// Pin the numeric value so any change is loud — the cap doubles as a
		// memory budget (worst-case dense-node tables sized off file bytes) and
		// as an attacker-bounded guarantee for arbitrary .osm.pbf imports.
		val oneHundredMb = 100L * 1024L * 1024L
		OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES shouldBe oneHundredMb
		OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES.shouldBeGreaterThan(0L)
	}

	@Test
	fun `referenced-node ceiling is the documented heap-bound upper bound`() {
		// Pin MAX_REFERENCED_NODES_CEILING so any change is loud. Combined
		// with the per-entry cost of a boxed-Long HashSet/HashMap entry on
		// the Android runtime (~70-80 bytes), this ceiling bounds the
		// parser's two intermediate maps at ~140 MB and ~160 MB respectively
		// even when the JVM has plenty of heap — see the class KDoc
		// "Heap envelope per phase" section. R2 round-6 round-2 lowered this
		// from 8 000 000 to 2 000 000 because the previous cap admitted a
		// worst-case ~560 MB transient allocation realistic .osm.pbf payloads
		// never need but adversarial inputs could weaponize. R2 round-7 kept
		// the ceiling but made the runtime cap adaptive, so most devices now
		// see a much tighter effective cap.
		OsmPbfStreamingParser.MAX_REFERENCED_NODES_CEILING shouldBe 2_000_000
		@Suppress("DEPRECATION")
		OsmPbfStreamingParser.MAX_REFERENCED_NODES shouldBe 2_000_000
	}

	@Test
	fun `referenced-node floor protects very small heaps`() {
		// Floor must stay high enough that single-town imports still succeed
		// on devices with tightly-constrained heaps, but low enough that the
		// floor's own peak (floor × PEAK_BYTES_PER_NODE_REF) fits well inside
		// a 256 MB heap. 256 000 × 200 B = ~50 MB transient peak.
		OsmPbfStreamingParser.MIN_REFERENCED_NODES_FLOOR shouldBe 256_000
	}

	@Nested
	@DisplayName("adaptive maxReferencedNodes cap")
	inner class AdaptiveCap {

		@Test
		fun `256 MB heap produces a sub-500k cap (well under the 2M ceiling)`() {
			val cap = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = 256L * BYTES_PER_MB,
			)

			// 256 MB × 25% / 200 B = 335 544 → coerced unchanged.
			cap shouldBe 335_544
			cap shouldBeLessThanOrEqual OsmPbfStreamingParser.MAX_REFERENCED_NODES_CEILING
			cap shouldBeGreaterThan 0
		}

		@Test
		fun `384 MB heap produces ~500k cap`() {
			val cap = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = 384L * BYTES_PER_MB,
			)

			// 384 MB × 25% / 200 B = 503 316.
			cap shouldBe 503_316
		}

		@Test
		fun `512 MB heap produces ~670k cap`() {
			val cap = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = 512L * BYTES_PER_MB,
			)

			// 512 MB × 25% / 200 B = 671 088.
			cap shouldBe 671_088
		}

		@Test
		fun `1024 MB heap produces ~1_34M cap (still under ceiling)`() {
			val cap = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = 1024L * BYTES_PER_MB,
			)

			// 1024 MB × 25% / 200 B = 1 342 177.
			cap shouldBe 1_342_177
			cap shouldBeLessThanOrEqual OsmPbfStreamingParser.MAX_REFERENCED_NODES_CEILING
		}

		@Test
		fun `2048 MB heap is clamped to the 2M ceiling`() {
			val cap = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = 2048L * BYTES_PER_MB,
			)

			// Raw would be 2 684 354 — coerced down to the ceiling.
			cap shouldBe OsmPbfStreamingParser.MAX_REFERENCED_NODES_CEILING
		}

		@Test
		fun `pathologically small heap is clamped to the floor, never zero`() {
			val cap = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = 32L * BYTES_PER_MB,
			)

			// Raw would be 41 943 — coerced up to the floor so single-town
			// imports remain possible on test JVMs / very old devices.
			cap shouldBe OsmPbfStreamingParser.MIN_REFERENCED_NODES_FLOOR
		}

		@Test
		fun `zero or negative heap defends with the floor (no division-by-zero, no negative cap)`() {
			OsmPbfStreamingParser.computeMaxReferencedNodes(maxMemoryBytes = 0L) shouldBe
				OsmPbfStreamingParser.MIN_REFERENCED_NODES_FLOOR
			OsmPbfStreamingParser.computeMaxReferencedNodes(maxMemoryBytes = -1L) shouldBe
				OsmPbfStreamingParser.MIN_REFERENCED_NODES_FLOOR
			OsmPbfStreamingParser.computeMaxReferencedNodes(maxMemoryBytes = Long.MIN_VALUE) shouldBe
				OsmPbfStreamingParser.MIN_REFERENCED_NODES_FLOOR
		}

		@Test
		fun `parser instance exposes the computed cap for downstream verification`() {
			val parser = OsmPbfStreamingParser(maxMemoryBytes = 256L * BYTES_PER_MB)

			parser.maxReferencedNodes shouldBe 335_544
		}

		@Test
		fun `default parser construction picks up the live Runtime maxMemory cap`() {
			// Sanity check: the no-arg constructor wires Runtime.maxMemory so
			// production callers (OsmImportWorker) get adaptive sizing without
			// having to plumb the value themselves.
			val parser = OsmPbfStreamingParser()
			val expected = OsmPbfStreamingParser.computeMaxReferencedNodes(
				maxMemoryBytes = Runtime.getRuntime().maxMemory(),
			)

			parser.maxReferencedNodes shouldBe expected
		}
	}

	@Nested
	@DisplayName("startup logging")
	inner class StartupLogging {

		@Test
		fun `parse start logs the chosen adaptive cap so field reports are debuggable`() = runTest {
			val recorder = RecordingReporter()
			ReporterFacade.setDelegate(recorder)
			val parser = OsmPbfStreamingParser(maxMemoryBytes = 256L * BYTES_PER_MB)

			// We don't care about the rest of the parse — feed an empty stream
			// so BlockInputStream returns immediately after the size guard
			// passes (the guard runs first, then logging runs, then pass-1
			// scans the empty stream and yields zero ways).
			runCatching {
				parser.parse(
					fileSizeBytes = 0L,
					openInputStream = { ByteArrayInputStream(ByteArray(0)) },
					onWayBatch = { /* never invoked */ },
				)
			}

			recorder.logs shouldHaveAtLeastSize 1
			val startupLog = recorder.logs.first()
			startupLog shouldContain "OsmPbfStreamingParser"
			startupLog shouldContain "maxReferencedNodes=335544"
			startupLog shouldContain "heap=256 MB"
			startupLog shouldContain "budget=1/4"
			startupLog shouldContain "perRef=200B"
			startupLog shouldContain "floor=256000"
			startupLog shouldContain "ceiling=2000000"
		}

		@Test
		fun `parse does not log when the file-size guard rejects the import early`() = runTest {
			// Failing the file-size guard should NOT emit the adaptive-cap log
			// line — that line is meant to document the cap actually used for
			// the parse, and a parse that never started has no cap "in effect".
			val recorder = RecordingReporter()
			ReporterFacade.setDelegate(recorder)
			val parser = OsmPbfStreamingParser(maxMemoryBytes = 256L * BYTES_PER_MB)

			shouldThrow<OsmParseException.FileTooLarge> {
				parser.parse(
					fileSizeBytes = OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES + 1,
					openInputStream = { ByteArrayInputStream(ByteArray(0)) },
					onWayBatch = { /* never invoked */ },
				)
			}

			recorder.logs shouldHaveSize 0
		}
	}

	private class RecordingReporter : ErrorReporter {
		private val _logs = mutableListOf<String>()
		val logs: List<String> get() = _logs.toList()

		override fun report(message: String) = Unit
		override fun report(exception: Throwable) = Unit
		override fun log(message: String) {
			_logs.add(message)
		}
	}

	companion object {
		private const val BYTES_PER_MB: Long = 1024L * 1024L
	}
}
