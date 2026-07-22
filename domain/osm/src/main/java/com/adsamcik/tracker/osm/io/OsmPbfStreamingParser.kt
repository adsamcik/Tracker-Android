package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.osm.OsmRoadClass
import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7
import com.adsamcik.tracker.shared.model.geo.CircularLongitudeInterval
import crosby.binary.BinaryParser
import crosby.binary.Osmformat
import crosby.binary.file.BlockInputStream
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Legacy two-pass OpenStreetMap PBF parser retained only behind the offline-PBF
 * release gate for focused characterization.
 *
 * It must not be connected to an untrusted user-selected file. The unmodified
 * [BlockInputStream] creates generated protobuf object graphs before Tracker
 * receives callbacks, and the pass-one [BufferedWay] / reference collections
 * are not bounded by [maxReferencedNodes]. The library also trusts compressed
 * block metadata while decoding. Therefore [MAX_FILE_SIZE_BYTES] and the
 * distinct-node cap are legacy product/characterization limits, not an RSS
 * bound and not a security contract for attacker-controlled `.osm.pbf` input.
 *
 * A future safe intake must use a private actual-byte-counted snapshot, strict
 * framing and exact decompression, validated headers/primitive structure, and
 * a combined allocation/disk/work ledger before re-enabling this parser path.
 *
 * Cancellation is cooperative. A single [parse] invocation is single-threaded;
 * concurrent invocations on one instance are not supported.
 */
class OsmPbfStreamingParser(
	/**
	 * Legacy heap input used to derive a characterization-only node cap. This
	 * does not reserve process memory or bound other parser/output allocations.
	 * Tests may inject a fixed value to exercise the historical calculation.
	 */
	private val maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
) {

	/**
	 * Adaptive cap on distinct referenced node ids retained across both
	 * passes — derived once at construction from [maxMemoryBytes] via
	 * [computeMaxReferencedNodes]. It is not a complete resource limit.
	 */
	val maxReferencedNodes: Int = computeMaxReferencedNodes(maxMemoryBytes)

	/**
	 * Parses [openInputStream] (called twice — once per pass) and emits
	 * [ParsedOsmWay] batches of size [wayBatchSize] via [onWayBatch].
	 *
	 * The `openInputStream` factory MUST return a fresh, seekable-from-start
	 * [InputStream] on every call (use `FileInputStream`, not a cached
	 * `ByteArrayInputStream`) — each pass walks the file from the beginning
	 * via [BlockInputStream]. **Do NOT pre-read the file into a `ByteArray` and
	 * hand back a `ByteArrayInputStream`**: that would defeat the streaming
	 * guarantee and risk OOM for files near [MAX_FILE_SIZE_BYTES].
	 *
	 * Progress callbacks fire at phase transitions and per emitted batch.
	 * Intra-pass progress is intentionally coarse because the underlying
	 * [BlockInputStream.process] loop is synchronous and cannot await.
	 *
	 * @return aggregate counts and the overall bounding box of every emitted
	 *   way's geometry.
	 *
	 * @throws OsmParseException when the file is too large, has too many
	 *   referenced nodes, or is structurally invalid. The size check runs
	 *   BEFORE [openInputStream] is invoked, so oversize files never touch the
	 *   parser's IO.
	 */
	suspend fun parse(
		fileSizeBytes: Long,
		openInputStream: () -> InputStream,
		wayBatchSize: Int = DEFAULT_WAY_BATCH_SIZE,
		onProgress: (suspend (OsmParseProgress) -> Unit)? = null,
		onWayBatch: suspend (List<ParsedOsmWay>) -> Unit,
	): OsmParseStats {
		require(wayBatchSize > 0) { "wayBatchSize must be positive (was $wayBatchSize)" }
		if (fileSizeBytes > MAX_FILE_SIZE_BYTES) {
			throw OsmParseException.FileTooLarge(fileSizeBytes, MAX_FILE_SIZE_BYTES)
		}

		// Log the chosen legacy cap for characterization diagnostics. Goes through the
		// :logging-api facade so this stays a no-op when no delegate is wired
		// (e.g. unit tests, instrumentation harnesses).
		ReporterFacade.log(
			"OsmPbfStreamingParser: maxReferencedNodes=$maxReferencedNodes " +
				"(heap=${maxMemoryBytes / BYTES_PER_MEGABYTE} MB, " +
				"budget=$HEAP_BUDGET_NUMERATOR/$HEAP_BUDGET_DENOMINATOR, " +
				"perRef=${PEAK_BYTES_PER_NODE_REF}B, " +
				"floor=$MIN_REFERENCED_NODES_FLOOR, ceiling=$MAX_REFERENCED_NODES_CEILING)",
		)

		val cancellation = CancellationCheck()

		// --- Pass 1: scan ways ---
		val nodeIds = HashSet<Long>(INITIAL_NODE_ID_CAPACITY)
		val wayBuffer = ArrayList<BufferedWay>(INITIAL_WAY_BUFFER_CAPACITY)
		val pass1Counter = LongCounter()
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_WAYS, 0L))
		runPass {
			openInputStream().use { input ->
				val parser = WayScanParser(
					cancellation = cancellation,
					nodeIds = nodeIds,
					wayBuffer = wayBuffer,
					counter = pass1Counter,
					maxReferencedNodes = maxReferencedNodes,
				)
				BlockInputStream(input, parser).process()
			}
		}
		coroutineContext.ensureActive()
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_WAYS, pass1Counter.value))

		if (wayBuffer.isEmpty()) {
			return OsmParseStats(
				nodeCount = 0L,
				wayCount = 0L,
				diagnosticMinLatitudeE7 = 0,
				diagnosticMaxLatitudeE7 = 0,
				diagnosticMinLongitudeE7 = 0,
				diagnosticMaxLongitudeE7 = 0,
			)
		}

		val rejectionCounts = WayRejectionCounts()

		// --- Pass 2: scan nodes (both dense and regular) ---
		val nodePositions = HashMap<Long, Long>(nodeIds.size)
		val pass2Counter = LongCounter()
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_NODES, 0L))
		runPass {
			openInputStream().use { input ->
				val parser = NodeScanParser(
					cancellation = cancellation,
					targetIds = nodeIds,
					positions = nodePositions,
					counter = pass2Counter,
					rejectionCounts = rejectionCounts,
				)
				BlockInputStream(input, parser).process()
			}
		}
		coroutineContext.ensureActive()
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_NODES, pass2Counter.value))

		// `nodeIds` is no longer needed once `nodePositions` has been built —
		// the emit phase only looks ids up in `nodePositions`. Releasing it
		// reduces retained legacy-parser state, but does not make total parsing
		// allocations safe for untrusted input.
		nodeIds.clear()

		// --- Emit ---
		var globalMinLat = Int.MAX_VALUE
		var globalMaxLat = Int.MIN_VALUE
		var globalMinLon = Int.MAX_VALUE
		var globalMaxLon = Int.MIN_VALUE
		var emittedWays = 0L
		val batch = ArrayList<ParsedOsmWay>(wayBatchSize)
		for (buffered in wayBuffer) {
			val parsed = buildParsedWay(buffered, nodePositions, rejectionCounts) ?: continue
			if (parsed.bboxMinLatE7 < globalMinLat) globalMinLat = parsed.bboxMinLatE7
			if (parsed.bboxMaxLatE7 > globalMaxLat) globalMaxLat = parsed.bboxMaxLatE7
			// Parse statistics retain their historical ordered numeric extrema;
			// persisted bbox fields below use explicit directed interval endpoints.
			val (_, geometryLongitudes) = PolylineE7Codec.decode(parsed.geomPolylineE7)
			val orderedMinLon = geometryLongitudes.minOrNull() ?: parsed.bboxMinLonE7
			val orderedMaxLon = geometryLongitudes.maxOrNull() ?: parsed.bboxMaxLonE7
			if (orderedMinLon < globalMinLon) globalMinLon = orderedMinLon
			if (orderedMaxLon > globalMaxLon) globalMaxLon = orderedMaxLon

			batch.add(parsed)
			emittedWays++
			if (batch.size >= wayBatchSize) {
				coroutineContext.ensureActive()
				onWayBatch(batch.toList())
				batch.clear()
				onProgress?.invoke(OsmParseProgress(OsmParsePhase.EMIT_WAYS, emittedWays))
			}
		}
		if (batch.isNotEmpty()) {
			coroutineContext.ensureActive()
			onWayBatch(batch.toList())
		}
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.EMIT_WAYS, emittedWays))
		if (rejectionCounts.invalidCoordinateNodes > 0L ||
			rejectionCounts.missingNodeWays > 0L ||
			rejectionCounts.invalidLongitudeCoverageWays > 0L ||
			rejectionCounts.excessiveCellCoverageWays > 0L
		) {
			ReporterFacade.log(
				"OsmPbfStreamingParser: rejectedInvalidCoordinateNodes=${rejectionCounts.invalidCoordinateNodes}, " +
					"rejectedMissingNodeWays=${rejectionCounts.missingNodeWays}, " +
					"rejectedInvalidLongitudeCoverageWays=${rejectionCounts.invalidLongitudeCoverageWays}, " +
					"rejectedExcessiveCellCoverageWays=${rejectionCounts.excessiveCellCoverageWays}",
			)
		}

		return if (emittedWays == 0L) {
			OsmParseStats(
				nodeCount = pass2Counter.value,
				wayCount = 0L,
				diagnosticMinLatitudeE7 = 0,
				diagnosticMaxLatitudeE7 = 0,
				diagnosticMinLongitudeE7 = 0,
				diagnosticMaxLongitudeE7 = 0,
				rejectedInvalidCoordinateNodes = rejectionCounts.invalidCoordinateNodes,
				rejectedInvalidLongitudeCoverageWays = rejectionCounts.invalidLongitudeCoverageWays,
				rejectedExcessiveCellCoverageWays = rejectionCounts.excessiveCellCoverageWays,
			)
		} else {
			OsmParseStats(
				nodeCount = pass2Counter.value,
				wayCount = emittedWays,
				diagnosticMinLatitudeE7 = globalMinLat,
				diagnosticMaxLatitudeE7 = globalMaxLat,
				diagnosticMinLongitudeE7 = globalMinLon,
				diagnosticMaxLongitudeE7 = globalMaxLon,
				rejectedInvalidCoordinateNodes = rejectionCounts.invalidCoordinateNodes,
				rejectedInvalidLongitudeCoverageWays = rejectionCounts.invalidLongitudeCoverageWays,
				rejectedExcessiveCellCoverageWays = rejectionCounts.excessiveCellCoverageWays,
			)
		}
	}

	/**
	 * Runs [block] and converts a [ParseCancelledMarker] thrown from inside
	 * the synchronous reader into the canonical CancellationException via
	 * [ensureActive]. Other exceptions propagate unchanged.
	 */
	private suspend inline fun runPass(block: () -> Unit) {
		try {
			block()
		} catch (cancelled: ParseCancelledMarker) {
			// Force the coroutine to honour cancellation; if the context is
			// somehow still active (shouldn't happen) we surface the marker
			// to make the bug visible.
			coroutineContext.ensureActive()
			throw cancelled
		}
	}

	private fun buildParsedWay(
		buffered: BufferedWay,
		nodePositions: Map<Long, Long>,
		rejectionCounts: WayRejectionCounts,
	): ParsedOsmWay? {
		val lats = IntArray(buffered.nodeIds.size)
		val lons = IntArray(buffered.nodeIds.size)
		for (i in buffered.nodeIds.indices) {
			val packed = nodePositions[buffered.nodeIds[i]]
			if (packed == null) {
				rejectionCounts.missingNodeWays++
				return null
			}
			lats[i] = unpackLat(packed)
			lons[i] = unpackLon(packed)
		}

		var minLat = Int.MAX_VALUE
		var maxLat = Int.MIN_VALUE
		for (i in lats.indices) {
			val la = lats[i]
			if (la < minLat) minLat = la
			if (la > maxLat) maxLat = la
		}
		val longitudeInterval = CircularLongitudeInterval.fromShortestEdgePolyline(lons)
		if (longitudeInterval === CircularLongitudeInterval.Full) {
			rejectionCounts.invalidLongitudeCoverageWays++
			return null
		}

		val blob = PolylineE7Codec.encode(lats, lons)
		val cells = when (
			val coverage = OsmGridIndex.cellCoverageForBounds(minLat, maxLat, longitudeInterval)
		) {
			is OsmCellCoverage.Available -> coverage.cellKeys
			is OsmCellCoverage.TooLarge -> {
				rejectionCounts.excessiveCellCoverageWays++
				return null
			}
		}
		val intervalEnd = checkNotNull(longitudeInterval.endE7)
		return ParsedOsmWay(
			osmId = buffered.osmId,
			name = buffered.name,
			roadClass = buffered.roadClass,
			maxspeedKmh = buffered.maxspeedKmh,
			maxspeedExplicit = buffered.maxspeedExplicit,
			isOneway = buffered.isOneway,
			geomPolylineE7 = blob,
			bboxMinLatE7 = minLat,
			bboxMaxLatE7 = maxLat,
			bboxMinLonE7 = longitudeInterval.startE7.toInt(),
			bboxMaxLonE7 = intervalEnd.toInt(),
			cellKeys = cells,
		)
	}

	/**
	 * Pass 1 inner parser — scans every Way, keeps driveable ones and records
	 * referenced node ids in [nodeIds] for the second pass.
	 */
	private inner class WayScanParser(
		private val cancellation: CancellationCheck,
		private val nodeIds: HashSet<Long>,
		private val wayBuffer: ArrayList<BufferedWay>,
		private val counter: LongCounter,
		private val maxReferencedNodes: Int,
	) : BinaryParser() {

		override fun parseNodes(nodes: MutableList<Osmformat.Node>?) {
			checkCancellation()
		}

		override fun parseDense(nodes: Osmformat.DenseNodes?) {
			checkCancellation()
		}

		override fun parseRelations(rels: MutableList<Osmformat.Relation>?) {
			checkCancellation()
		}

		override fun parse(header: Osmformat.HeaderBlock?) {
			// Header is currently ignored — bbox is recomputed from observed ways.
		}

		override fun complete() = Unit

		override fun parseWays(ways: MutableList<Osmformat.Way>?) {
			if (ways == null) return
			for (way in ways) {
				if (cancellation.isCancelled) throw ParseCancelledMarker
				val tags = collectTags(way.keysCount, way::getKeys, way::getVals)
				val roadClass = OsmRoadClass.fromOsmValue(tags["highway"]) ?: continue

				val refCount = way.refsCount
				if (refCount < MIN_NODES_PER_WAY) continue
				val refs = LongArray(refCount)
				var acc = 0L
				for (i in 0 until refCount) {
					acc += way.getRefs(i)
					refs[i] = acc
					nodeIds.add(acc)
					if (nodeIds.size > maxReferencedNodes) {
						throw OsmParseException.TooManyNodes(
							nodeIds.size.toLong(),
							maxReferencedNodes.toLong(),
						)
					}
				}

				val explicitKmh = OsmMaxspeedParser.parseKmh(tags["maxspeed"])
				wayBuffer.add(
					BufferedWay(
						osmId = way.id,
						name = tags["name"],
						roadClass = roadClass,
						maxspeedKmh = explicitKmh ?: roadClass.defaultMaxspeedKmh,
						maxspeedExplicit = explicitKmh != null,
						isOneway = parseOneway(tags["oneway"], roadClass),
						nodeIds = refs,
					),
				)
				counter.value++
			}
		}

		private fun collectTags(
			count: Int,
			keyAt: (Int) -> Int,
			valAt: (Int) -> Int,
		): Map<String, String> {
			if (count == 0) return emptyMap()
			val out = HashMap<String, String>(count)
			for (i in 0 until count) {
				val k = getStringById(keyAt(i)) ?: continue
				val v = getStringById(valAt(i)) ?: continue
				out[k] = v
			}
			return out
		}

		private fun checkCancellation() {
			if (cancellation.isCancelled) throw ParseCancelledMarker
		}
	}

	/**
	 * Pass 2 inner parser — scans every Node and DenseNodes block, keeping
	 * only those whose id appears in [targetIds]. Coordinates are converted
	 * to degrees via [parseLat]/[parseLon] (which apply the block-level
	 * granularity / offset) and then to E7 packed into a single Long.
	 */
	private inner class NodeScanParser(
		private val cancellation: CancellationCheck,
		private val targetIds: Set<Long>,
		private val positions: HashMap<Long, Long>,
		private val counter: LongCounter,
		private val rejectionCounts: WayRejectionCounts,
	) : BinaryParser() {

		override fun parseWays(ways: MutableList<Osmformat.Way>?) {
			checkCancellation()
		}

		override fun parseRelations(rels: MutableList<Osmformat.Relation>?) {
			checkCancellation()
		}

		override fun parse(header: Osmformat.HeaderBlock?) = Unit

		override fun complete() = Unit

		override fun parseNodes(nodes: MutableList<Osmformat.Node>?) {
			if (nodes == null) return
			for (n in nodes) {
				if (cancellation.isCancelled) throw ParseCancelledMarker
				counter.value++
				if (!targetIds.contains(n.id)) continue
				val coordinate = CheckedCoordinateE7.fromDegreesOrNull(parseLat(n.lat), parseLon(n.lon))
				if (coordinate == null) {
					rejectionCounts.invalidCoordinateNodes++
					continue
				}
				positions[n.id] = packLatLon(coordinate.latitudeE7, coordinate.longitudeE7)
			}
		}

		override fun parseDense(nodes: Osmformat.DenseNodes?) {
			if (nodes == null) return
			val count = nodes.idCount
			var idAcc = 0L
			var latAcc = 0L
			var lonAcc = 0L
			for (i in 0 until count) {
				idAcc = checkedDenseAdd(idAcc, nodes.getId(i), "id")
				latAcc = checkedDenseAdd(latAcc, nodes.getLat(i), "latitude")
				lonAcc = checkedDenseAdd(lonAcc, nodes.getLon(i), "longitude")
				counter.value++
				if (counter.value % CANCEL_POLL_INTERVAL == 0L &&
					cancellation.isCancelled
				) {
					throw ParseCancelledMarker
				}
				if (!targetIds.contains(idAcc)) continue
				val coordinate = CheckedCoordinateE7.fromDegreesOrNull(parseLat(latAcc), parseLon(lonAcc))
				if (coordinate == null) {
					rejectionCounts.invalidCoordinateNodes++
					continue
				}
				positions[idAcc] = packLatLon(coordinate.latitudeE7, coordinate.longitudeE7)
			}
		}

		private fun checkCancellation() {
			if (cancellation.isCancelled) throw ParseCancelledMarker
		}

		private fun checkedDenseAdd(current: Long, delta: Long, field: String): Long = try {
			Math.addExact(current, delta)
		} catch (_: ArithmeticException) {
			throw OsmParseException.DenseDeltaOverflow(field)
		}
	}

	private fun parseOneway(raw: String?, roadClass: OsmRoadClass): Boolean {
		if (raw == null) {
			// Motorways and motorway links default to oneway per OSM convention.
			return roadClass == OsmRoadClass.MOTORWAY || roadClass == OsmRoadClass.MOTORWAY_LINK
		}
		return when (raw.trim().lowercase()) {
			"yes", "true", "1" -> true
			"-1", "reverse" -> true
			else -> false
		}
	}

	private data class BufferedWay(
		val osmId: Long,
		val name: String?,
		val roadClass: OsmRoadClass,
		val maxspeedKmh: Int,
		val maxspeedExplicit: Boolean,
		val isOneway: Boolean,
		val nodeIds: LongArray,
	)

	/** Mutable long counter passed across inner-parser boundaries. */
	private class LongCounter {
		var value: Long = 0L
	}

	private class WayRejectionCounts {
		var invalidCoordinateNodes: Long = 0L
		var missingNodeWays: Long = 0L
		var invalidLongitudeCoverageWays: Long = 0L
		var excessiveCellCoverageWays: Long = 0L
	}

	/** Cross-thread cancellation flag set by the outer suspend [parse]. */
	private class CancellationCheck {
		@Volatile
		var isCancelled: Boolean = false
	}

	companion object {
		const val MAX_FILE_SIZE_BYTES: Long = 100L * 1024L * 1024L

		/**
		 * Historical ceiling for the legacy distinct-node heuristic. It constrains
		 * only that one collection and must not be interpreted as a total-memory
		 * or hostile-input safety bound.
		 */
		const val MAX_REFERENCED_NODES_CEILING: Int = 2_000_000

		/**
		 * Backwards-compatible alias kept so existing call sites and pinned
		 * regression tests continue to reference the historical name. New
		 * code should refer to [MAX_REFERENCED_NODES_CEILING] (or, for the
		 * per-instance value actually enforced at runtime, [maxReferencedNodes]).
		 */
		@Deprecated(
			"Use MAX_REFERENCED_NODES_CEILING for the static ceiling, or the " +
				"per-instance maxReferencedNodes for the adaptive runtime cap.",
			ReplaceWith("MAX_REFERENCED_NODES_CEILING"),
		)
		const val MAX_REFERENCED_NODES: Int = MAX_REFERENCED_NODES_CEILING

		/** Floor on the historical characterization heuristic. */
		const val MIN_REFERENCED_NODES_FLOOR: Int = 256_000

		/**
		 * Legacy per-node estimate used only by [computeMaxReferencedNodes]. It
		 * excludes ways, references, generated protobuf objects, output batches,
		 * and all coexistence costs, so it is not a memory-safety reservation.
		 */
		const val PEAK_BYTES_PER_NODE_REF: Long = 200L

		/** Numerator of the heap fraction the parser is allowed to allocate. */
		const val HEAP_BUDGET_NUMERATOR: Int = 1

		/** Denominator of the heap fraction the parser is allowed to allocate. */
		const val HEAP_BUDGET_DENOMINATOR: Int = 4

		private const val BYTES_PER_MEGABYTE: Long = 1024L * 1024L
		private const val WORLD_E7 = 3_600_000_000L
		private const val HALF_WORLD_E7 = WORLD_E7 / 2

		const val DEFAULT_WAY_BATCH_SIZE: Int = 1_000
		private const val MIN_NODES_PER_WAY = 2
		private const val INITIAL_NODE_ID_CAPACITY = 1 shl 17
		private const val INITIAL_WAY_BUFFER_CAPACITY = 1 shl 14

		/** Poll the cancellation flag every N processed dense-node entries. */
		private const val CANCEL_POLL_INTERVAL: Long = 16_384L

		/**
		 * Compute the adaptive [maxReferencedNodes] cap from the JVM heap size.
		 *
		 * Formula: `(maxMemoryBytes * NUMERATOR / DENOMINATOR) /
		 * PEAK_BYTES_PER_NODE_REF`, coerced into
		 * [[MIN_REFERENCED_NODES_FLOOR], [MAX_REFERENCED_NODES_CEILING]].
		 *
		 * Pure function — exposed so tests can pin the cap for every device
		 * class without spinning up a real OS process.
		 *
		 * @param maxMemoryBytes total JVM heap available, e.g. as returned by
		 *   [Runtime.maxMemory]. Negative or zero values are treated as 0 and
		 *   produce the floor cap.
		 */
		fun computeMaxReferencedNodes(maxMemoryBytes: Long): Int {
			val safeHeap = maxMemoryBytes.coerceAtLeast(0L)
			// Use Long division throughout to avoid 32-bit overflow on
			// multi-GB heaps. `safeHeap * NUMERATOR` is bounded by Long.MAX
			// well above any realistic Android heap.
			val budgetBytes = safeHeap * HEAP_BUDGET_NUMERATOR / HEAP_BUDGET_DENOMINATOR
			val rawCap = budgetBytes / PEAK_BYTES_PER_NODE_REF
			return rawCap
				.coerceIn(
					MIN_REFERENCED_NODES_FLOOR.toLong(),
					MAX_REFERENCED_NODES_CEILING.toLong(),
				)
				.toInt()
		}

		internal fun packLatLon(latE7: Int, lonE7: Int): Long =
			(latE7.toLong() shl 32) or (lonE7.toLong() and 0xFFFFFFFFL)

		internal fun unpackLat(packed: Long): Int = (packed shr 32).toInt()
		internal fun unpackLon(packed: Long): Int = packed.toInt()
	}
}

/**
 * Sentinel exception thrown from inside the synchronous PBF reader callbacks
 * to unwind [crosby.binary.file.BlockInputStream.process] when the outer
 * coroutine is cancelled. The outer [OsmPbfStreamingParser.parse] catches it
 * and re-raises as [kotlinx.coroutines.CancellationException] via
 * [kotlinx.coroutines.ensureActive].
 */
internal object ParseCancelledMarker : RuntimeException("OSM parse cancelled") {
	private fun readResolve(): Any = ParseCancelledMarker

	@Suppress("UNUSED")
	override fun fillInStackTrace(): Throwable = this
}

/** Distinct failure modes for [OsmPbfStreamingParser.parse]. */
sealed class OsmParseException(message: String) : RuntimeException(message) {
	class FileTooLarge(val sizeBytes: Long, val limitBytes: Long) : OsmParseException(
		"PBF size ${sizeBytes / (1024 * 1024)} MB exceeds limit " +
			"${limitBytes / (1024 * 1024)} MB",
	)

	class TooManyNodes(val nodeCount: Long, val limit: Long) : OsmParseException(
		"Referenced node count $nodeCount exceeds limit $limit",
	)

	/** A dense-node delta cannot be accumulated without signed-Long wraparound. */
	class DenseDeltaOverflow(field: String) : OsmParseException(
		"PBF dense-node $field delta overflows the checked accumulator",
	)
}
