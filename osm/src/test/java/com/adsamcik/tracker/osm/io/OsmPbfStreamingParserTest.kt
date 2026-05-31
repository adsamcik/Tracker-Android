package com.adsamcik.tracker.osm.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Behavioural guard tests for [OsmPbfStreamingParser].
 *
 * Round-6 perf review (R2 round-6) requested explicit verification that the
 * parser refuses to load arbitrarily large PBF files into memory in a single
 * gulp. The streaming pipeline already two-passes via
 * [crosby.binary.file.BlockInputStream], but the user-visible contract is the
 * [OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES] cap. These tests pin that
 * contract so a future refactor can't silently lift the ceiling.
 */
@DisplayName("OsmPbfStreamingParser")
class OsmPbfStreamingParserTest {

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
	fun `referenced-node cap is the documented heap-bound ceiling`() {
		// Pin MAX_REFERENCED_NODES so any change is loud. Combined with the
		// per-entry cost of a boxed-Long HashSet/HashMap entry on the Android
		// runtime (~70-80 bytes), this cap bounds the parser's two intermediate
		// maps at ~140 MB and ~160 MB respectively — see the class KDoc
		// "Heap envelope per phase" section. R2 round-6, round-2 lowered this
		// from 8 000 000 to 2 000 000 because the previous cap admitted a
		// worst-case ~560 MB transient allocation realistic .osm.pbf payloads
		// never need but adversarial inputs could weaponize.
		OsmPbfStreamingParser.MAX_REFERENCED_NODES shouldBe 2_000_000
	}
}
