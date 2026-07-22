package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.logging.api.ErrorReporter
import com.adsamcik.tracker.logging.api.ReporterFacade
import com.google.protobuf.ByteString
import crosby.binary.Osmformat
import crosby.binary.file.BlockOutputStream
import crosby.binary.file.FileBlock
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
import java.io.ByteArrayOutputStream

/**
 * Characterization tests for the legacy [OsmPbfStreamingParser]. The parser
 * remains release-gated because its source cap and distinct-node heuristic do
 * not bound generated protobuf blocks, retained ways/references, or output
 * allocations for hostile input. These tests preserve historical behavior for
 * isolated analysis; they do not certify safe PBF intake.
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
	fun `rejects files larger than the legacy 100 MB cap before opening the stream`() = runTest {
		val parser = OsmPbfStreamingParser()
		var openCount = 0
		val oversize = OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES + 1

		val ex = shouldThrow<OsmParseException.FileTooLarge> {
			parser.parse(
				fileSizeBytes = oversize,
				openInputStream = {
					// Preserve the legacy ordering contract for characterization;
					// it is not a substitute for actual-byte-counted safe intake.
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
	fun `legacy source cap remains 100 MB for characterization`() {
		// Pin the historical product limit without treating it as a memory or
		// attacker-safety bound.
		val oneHundredMb = 100L * 1024L * 1024L
		OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES shouldBe oneHundredMb
		OsmPbfStreamingParser.MAX_FILE_SIZE_BYTES.shouldBeGreaterThan(0L)
	}

	@Test
	fun `referenced-node ceiling remains a legacy characterization heuristic`() {
		// This pins a legacy heuristic only. It says nothing about the total
		// parser footprint because retained ways, references, generated blocks,
		// and output work are independently unbounded in this implementation.
		OsmPbfStreamingParser.MAX_REFERENCED_NODES_CEILING shouldBe 2_000_000
		@Suppress("DEPRECATION")
		OsmPbfStreamingParser.MAX_REFERENCED_NODES shouldBe 2_000_000
	}

	@Test
	fun `referenced-node floor remains pinned for legacy characterization`() {
		// This is historical behavior, not an affordability or safety claim.
		OsmPbfStreamingParser.MIN_REFERENCED_NODES_FLOOR shouldBe 256_000
	}

	@Test
	fun `positive fixture has a standard header and homogeneous primitive groups`() {
		val fixture = validPbfWithWay(
			wayNodeIds = longArrayOf(1L, 2L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 0, lonE7 = 0),
				TestNode(id = 2L, latE7 = 0, lonE7 = 1),
			),
		)

		fixture.header?.requiredFeaturesList shouldBe listOf("OsmSchema-V0.6", "DenseNodes")
		fixture.primitiveBlock.primitivegroupList.forEach { group ->
			val populatedKinds = listOf(
				group.nodesCount,
				group.waysCount,
				group.relationsCount,
			).count { it > 0 }
			populatedKinds shouldBe 1
		}
	}

	@Test
	fun `headerless and mixed-group fixtures remain explicitly nonconformant`() {
		val headerless = headerlessOsmDataNegativeFixture(
			wayNodeIds = longArrayOf(1L, 2L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 0, lonE7 = 0),
				TestNode(id = 2L, latE7 = 0, lonE7 = 1),
			),
		)
		val mixed = mixedPrimitiveGroupNegativeFixture(
			wayNodeIds = longArrayOf(1L, 2L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 0, lonE7 = 0),
				TestNode(id = 2L, latE7 = 0, lonE7 = 1),
			),
		)

		headerless.header shouldBe null
		mixed.header?.requiredFeaturesList shouldBe listOf("OsmSchema-V0.6", "DenseNodes")
		mixed.primitiveBlock.primitivegroupList.single().nodesCount shouldBe 2
		mixed.primitiveBlock.primitivegroupList.single().waysCount shouldBe 1
	}

	@Test
	fun `way with a missing middle node is rejected instead of stitched`() = runTest {
		val pbf = pbfWithWay(
			wayNodeIds = longArrayOf(1L, 2L, 3L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 0, lonE7 = 0),
				TestNode(id = 3L, latE7 = 0, lonE7 = 200_000),
			),
		)
		val emitted = mutableListOf<ParsedOsmWay>()

		val stats = OsmPbfStreamingParser().parse(
			fileSizeBytes = pbf.size.toLong(),
			openInputStream = { ByteArrayInputStream(pbf) },
			onWayBatch = { emitted.addAll(it) },
		)

		emitted.shouldHaveSize(0)
		stats.wayCount shouldBe 0L
	}

	@Test
	fun `antimeridian way persists the same directed interval used for cells`() = runTest {
		val pbf = pbfWithWay(
			wayNodeIds = longArrayOf(1L, 2L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 0, lonE7 = 1_799_000_000),
				TestNode(id = 2L, latE7 = 0, lonE7 = -1_799_000_000),
			),
		)
		val emitted = mutableListOf<ParsedOsmWay>()

		val stats = OsmPbfStreamingParser().parse(
			fileSizeBytes = pbf.size.toLong(),
			openInputStream = { ByteArrayInputStream(pbf) },
			onWayBatch = { emitted.addAll(it) },
		)

		emitted.shouldHaveSize(1)
		emitted.single().bboxMinLonE7 shouldBe 1_799_000_000
		emitted.single().bboxMaxLonE7 shouldBe -1_799_000_000
		emitted.single().cellKeys.shouldHaveSize(21)
		stats.wayCount shouldBe 1L
		stats.diagnosticMinLongitudeE7 shouldBe -1_799_000_000
		stats.diagnosticMaxLongitudeE7 shouldBe 1_799_000_000
	}

	@Test
	fun `invalid target node is rejected before E7 narrowing with an aggregate reason`() = runTest {
		val pbf = pbfWithWay(
			wayNodeIds = longArrayOf(1L, 2L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 900_000_001, lonE7 = 0),
				TestNode(id = 2L, latE7 = 0, lonE7 = 0),
			),
		)
		val emitted = mutableListOf<ParsedOsmWay>()

		val stats = OsmPbfStreamingParser().parse(
			fileSizeBytes = pbf.size.toLong(),
			openInputStream = { ByteArrayInputStream(pbf) },
			onWayBatch = { emitted.addAll(it) },
		)

		emitted.shouldHaveSize(0)
		stats.rejectedInvalidCoordinateNodes shouldBe 1L
	}

	@Test
	fun `way whose continuous longitude coverage exceeds half the world is rejected`() = runTest {
		val pbf = pbfWithWay(
			wayNodeIds = longArrayOf(1L, 2L, 3L),
			nodes = listOf(
				TestNode(id = 1L, latE7 = 0, lonE7 = -1_700_000_000),
				TestNode(id = 2L, latE7 = 0, lonE7 = 0),
				TestNode(id = 3L, latE7 = 0, lonE7 = 1_700_000_000),
			),
		)
		val emitted = mutableListOf<ParsedOsmWay>()

		val stats = OsmPbfStreamingParser().parse(
			fileSizeBytes = pbf.size.toLong(),
			openInputStream = { ByteArrayInputStream(pbf) },
			onWayBatch = { emitted.addAll(it) },
		)

		emitted.shouldHaveSize(0)
		stats.wayCount shouldBe 0L
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

	private data class TestNode(
		val id: Long,
		val latE7: Int,
		val lonE7: Int,
	)

	private fun pbfWithWay(
		wayNodeIds: LongArray,
		nodes: List<TestNode>,
	): ByteArray = validPbfWithWay(wayNodeIds, nodes).bytes

	private fun validPbfWithWay(
		wayNodeIds: LongArray,
		nodes: List<TestNode>,
	): TestPbfFixture = pbfFixture(
		wayNodeIds = wayNodeIds,
		nodes = nodes,
		includeHeader = true,
		homogeneousGroups = true,
	)

	/** Negative fixture retained for future strict-header intake tests. */
	private fun headerlessOsmDataNegativeFixture(
		wayNodeIds: LongArray,
		nodes: List<TestNode>,
	): TestPbfFixture = pbfFixture(
		wayNodeIds = wayNodeIds,
		nodes = nodes,
		includeHeader = false,
		homogeneousGroups = true,
	)

	/** Negative fixture retained for future strict-primitive-group intake tests. */
	private fun mixedPrimitiveGroupNegativeFixture(
		wayNodeIds: LongArray,
		nodes: List<TestNode>,
	): TestPbfFixture = pbfFixture(
		wayNodeIds = wayNodeIds,
		nodes = nodes,
		includeHeader = true,
		homogeneousGroups = false,
	)

	private fun pbfFixture(
		wayNodeIds: LongArray,
		nodes: List<TestNode>,
		includeHeader: Boolean,
		homogeneousGroups: Boolean,
	): TestPbfFixture {
		val stringTable = Osmformat.StringTable.newBuilder()
			.addS(ByteString.copyFromUtf8(""))
			.addS(ByteString.copyFromUtf8("highway"))
			.addS(ByteString.copyFromUtf8("residential"))
			.build()
		val way = Osmformat.Way.newBuilder()
			.setId(100L)
			.addKeys(1)
			.addVals(2)
		var previousId = 0L
		for (id in wayNodeIds) {
			way.addRefs(id - previousId)
			previousId = id
		}
		val nodeGroup = Osmformat.PrimitiveGroup.newBuilder()
		for (node in nodes) {
			nodeGroup.addNodes(
				Osmformat.Node.newBuilder()
					.setId(node.id)
					.setLat(node.latE7.toLong())
					.setLon(node.lonE7.toLong()),
			)
		}
		val blockBuilder = Osmformat.PrimitiveBlock.newBuilder()
			.setStringtable(stringTable)
		if (homogeneousGroups) {
			blockBuilder
				.addPrimitivegroup(nodeGroup)
				.addPrimitivegroup(Osmformat.PrimitiveGroup.newBuilder().addWays(way))
		} else {
			blockBuilder.addPrimitivegroup(nodeGroup.addWays(way))
		}
		val block = blockBuilder.build()
		val header = if (includeHeader) {
			Osmformat.HeaderBlock.newBuilder()
				.addRequiredFeatures("OsmSchema-V0.6")
				.addRequiredFeatures("DenseNodes")
				.build()
		} else {
			null
		}
		val bytes = ByteArrayOutputStream()
		BlockOutputStream(bytes).use { output ->
			if (header != null) {
				output.write(FileBlock.newInstance("OSMHeader", header.toByteString(), null))
			}
			output.write(FileBlock.newInstance("OSMData", block.toByteString(), null))
		}
		return TestPbfFixture(bytes.toByteArray(), header, block)
	}

	private data class TestPbfFixture(
		val bytes: ByteArray,
		val header: Osmformat.HeaderBlock?,
		val primitiveBlock: Osmformat.PrimitiveBlock,
	)

	companion object {
		private const val BYTES_PER_MB: Long = 1024L * 1024L
	}
}
