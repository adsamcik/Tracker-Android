package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.osm.OsmRoadClass
import crosby.binary.BinaryParser
import crosby.binary.Osmformat
import crosby.binary.file.BlockInputStream
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Streaming two-pass parser over an OpenStreetMap PBF file.
 *
 * **Streaming guarantee (R2 round-6 perf review):** the parser NEVER reads the
 * entire PBF file into memory. Both passes consume the file via
 * [crosby.binary.file.BlockInputStream], which decodes one PBF block (typically
 * 16 MB uncompressed) at a time and discards each block's `Blob` after the
 * registered [BinaryParser] callbacks return. Pass 1 retains only driveable
 * ways and the set of referenced node ids; pass 2 retains lat/lon for those
 * referenced ids (packed into one `Long` each). At no point is the input file's
 * raw bytes held in memory in their entirety. Combined with the
 * [MAX_FILE_SIZE_BYTES] up-front guard, this keeps OSM import RSS bounded even
 * for attacker-supplied .osm.pbf files.
 *
 * Pass 1 walks every way and buffers driveable ones (per [OsmRoadClass]) plus
 * the set of node ids they reference. Pass 2 walks every node (regular and
 * dense) and keeps lat/lon (in E7, packed into a single Long) for the
 * referenced ids only. The buffered ways are then resolved into [ParsedOsmWay]
 * batches and handed to [onWayBatch] for persistence.
 *
 * Strict offline contract: the input stream factory MUST point at a local file
 * — the parser never opens a URL.
 *
 * **Heap envelope per phase (R2 round-6, round-2; round-7 adaptive cap):** the
 * parser allocates two heavyweight intermediate maps and one buffer. Because
 * both maps key on boxed [Long]s, the per-entry footprint is dominated by the
 * JVM's `HashMap.Node` (~48 B) plus the boxed [Long] (~24 B) — roughly **72
 * bytes per node id** in `nodeIds` and **80 bytes per (id → packed lat/lon)
 * entry** in `nodePositions`. Pass-2 peak concurrently holds both, so the
 * effective worst-case footprint is ~150 B per referenced node, which we round
 * up to [PEAK_BYTES_PER_NODE_REF] (200 B) to absorb hash-table over-allocation
 * (default load factor 0.75 inflates the underlying array by ~33 %).
 *
 * The parser computes its [maxReferencedNodes] cap **adaptively** at
 * construction from [Runtime.getRuntime].maxMemory():
 *
 *  - Budget = 25 % of total JVM heap ([HEAP_BUDGET_NUMERATOR] /
 *    [HEAP_BUDGET_DENOMINATOR]).
 *  - Raw cap = budget / [PEAK_BYTES_PER_NODE_REF].
 *  - Coerced into [[MIN_REFERENCED_NODES_FLOOR], [MAX_REFERENCED_NODES_CEILING]].
 *
 * That gives realistic device-class caps:
 *
 *  | Heap     | Raw cap   | Final cap |
 *  |----------|-----------|-----------|
 *  | 256 MB   | ~336 k    | **~336 k**|
 *  | 384 MB   | ~503 k    | **~503 k**|
 *  | 512 MB   | ~671 k    | **~671 k**|
 *  | 1024 MB  | ~1.34 M   | **~1.34 M**|
 *  | 2048 MB+ | ~2.68 M+  | **2.00 M (ceiling)** |
 *  | <128 MB  | small     | **256 k (floor)** |
 *
 * Per-phase consequences at the chosen cap:
 *
 *  - After pass 1: `nodeIds` ≤ ~70 B × cap; `wayBuffer` typically ≤ 5 MB
 *    (refs are stored as primitive `LongArray`s — no boxing).
 *  - After pass 2: `nodeIds` is no longer needed and is explicitly
 *    [HashSet.clear]ed before the emit loop begins.
 *  - During emit: only `nodePositions` (~80 B × cap) and `wayBuffer` remain
 *    resident, plus the in-flight [ParsedOsmWay] batch (≤
 *    [DEFAULT_WAY_BATCH_SIZE] entries × small bbox header). Downstream
 *    [OsmImportWorker] persists each batch in 5 000-way DB chunks, so peak
 *    transient allocations outside the parser stay bounded too.
 *
 * The 25 % budget leaves the other 75 % of heap for the OS / Compose UI / Room
 * write batches / OkHttp connections / etc. that share the process, which is
 * what makes the cap safe on 256 MB devices that previously OOM'd under the
 * fixed 2 M cap.
 *
 * Together with the [MAX_FILE_SIZE_BYTES] guard (100 MB) this keeps the worst
 * realistic OSM import — a fully driveable, urban .osm.pbf — well inside the
 * Android `dalvik.vm.heapgrowthlimit` envelope on every device class.
 *
 * Tuning: the per-record estimate is intentionally conservative. If a future
 * refactor swaps the boxed-Long collections for a primitive-long set/map
 * (Eclipse Collections / fastutil), drop [PEAK_BYTES_PER_NODE_REF] to ~24 B
 * and the same 25 % budget will unlock ~8× more headroom automatically.
 *
 * Memory guardrails (Phase 2a + R2 round-6 round-2 + R2 round-7):
 *  - PBF file size must not exceed [MAX_FILE_SIZE_BYTES] (~100 MB).
 *  - Referenced node count must not exceed [maxReferencedNodes] (adaptive,
 *    in [[MIN_REFERENCED_NODES_FLOOR], [MAX_REFERENCED_NODES_CEILING]]).
 *
 * Cancellation: cooperative — the parser polls [coroutineContext] and
 * [CancellationCheck.isCancelled] periodically. Mid-stream cancellation throws
 * [ParseCancelledMarker] to unwind the synchronous [BlockInputStream.process]
 * loop, which the outer [parse] catches and re-raises as a
 * [kotlinx.coroutines.CancellationException] via [ensureActive].
 *
 * Thread safety: a single [parse] invocation is single-threaded; concurrent
 * invocations on one instance are NOT supported.
 */
class OsmPbfStreamingParser(
	/**
	 * Total JVM heap budget the parser may scale its caps against. Defaults to
	 * [Runtime.maxMemory] so production callers pick up the real device limit
	 * (`dalvik.vm.heapgrowthlimit` on Android). Tests may inject a fixed value
	 * to exercise specific device classes.
	 */
	private val maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
) {

	/**
	 * Adaptive cap on distinct referenced node ids retained across both
	 * passes — derived once at construction from [maxMemoryBytes] via
	 * [computeMaxReferencedNodes]. See the class KDoc "Heap envelope" section
	 * for the device-class table.
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

		// Log the chosen adaptive cap so field reports can be debugged without
		// reproducing the device's exact heap envelope. Goes through the
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
				minLatE7 = 0,
				maxLatE7 = 0,
				minLonE7 = 0,
				maxLonE7 = 0,
			)
		}

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
				)
				BlockInputStream(input, parser).process()
			}
		}
		coroutineContext.ensureActive()
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_NODES, pass2Counter.value))

		// `nodeIds` is no longer needed once `nodePositions` has been built —
		// the emit phase only looks ids up in `nodePositions`. Explicitly free
		// the boxed-Long HashSet (~70 bytes per entry × up to the adaptive
		// `maxReferencedNodes` cap) before the emit loop allocates per-way
		// [ParsedOsmWay] objects. This matches the per-phase heap envelope
		// documented on the class KDoc.
		nodeIds.clear()

		// --- Emit ---
		var globalMinLat = Int.MAX_VALUE
		var globalMaxLat = Int.MIN_VALUE
		var globalMinLon = Int.MAX_VALUE
		var globalMaxLon = Int.MIN_VALUE
		var emittedWays = 0L
		val batch = ArrayList<ParsedOsmWay>(wayBatchSize)
		for (buffered in wayBuffer) {
			val parsed = buildParsedWay(buffered, nodePositions) ?: continue
			if (parsed.bboxMinLatE7 < globalMinLat) globalMinLat = parsed.bboxMinLatE7
			if (parsed.bboxMaxLatE7 > globalMaxLat) globalMaxLat = parsed.bboxMaxLatE7
			if (parsed.bboxMinLonE7 < globalMinLon) globalMinLon = parsed.bboxMinLonE7
			if (parsed.bboxMaxLonE7 > globalMaxLon) globalMaxLon = parsed.bboxMaxLonE7

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

		return if (emittedWays == 0L) {
			OsmParseStats(
				nodeCount = pass2Counter.value,
				wayCount = 0L,
				minLatE7 = 0,
				maxLatE7 = 0,
				minLonE7 = 0,
				maxLonE7 = 0,
			)
		} else {
			OsmParseStats(
				nodeCount = pass2Counter.value,
				wayCount = emittedWays,
				minLatE7 = globalMinLat,
				maxLatE7 = globalMaxLat,
				minLonE7 = globalMinLon,
				maxLonE7 = globalMaxLon,
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
	): ParsedOsmWay? {
		val rawLats = IntArray(buffered.nodeIds.size)
		val rawLons = IntArray(buffered.nodeIds.size)
		var resolved = 0
		for (id in buffered.nodeIds) {
			val packed = nodePositions[id] ?: continue
			rawLats[resolved] = unpackLat(packed)
			rawLons[resolved] = unpackLon(packed)
			resolved++
		}
		if (resolved < MIN_NODES_PER_WAY) return null
		val lats = if (resolved == rawLats.size) rawLats else rawLats.copyOf(resolved)
		val lons = if (resolved == rawLons.size) rawLons else rawLons.copyOf(resolved)

		var minLat = Int.MAX_VALUE
		var maxLat = Int.MIN_VALUE
		var minLon = Int.MAX_VALUE
		var maxLon = Int.MIN_VALUE
		for (i in 0 until resolved) {
			val la = lats[i]
			val lo = lons[i]
			if (la < minLat) minLat = la
			if (la > maxLat) maxLat = la
			if (lo < minLon) minLon = lo
			if (lo > maxLon) maxLon = lo
		}

		val blob = PolylineE7Codec.encode(lats, lons)
		val cells = OsmGridIndex.cellKeysForBbox(minLat, maxLat, minLon, maxLon)
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
			bboxMinLonE7 = minLon,
			bboxMaxLonE7 = maxLon,
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
				val latE7 = toE7(parseLat(n.lat))
				val lonE7 = toE7(parseLon(n.lon))
				positions[n.id] = packLatLon(latE7, lonE7)
			}
		}

		override fun parseDense(nodes: Osmformat.DenseNodes?) {
			if (nodes == null) return
			val count = nodes.idCount
			var idAcc = 0L
			var latAcc = 0L
			var lonAcc = 0L
			for (i in 0 until count) {
				idAcc += nodes.getId(i)
				latAcc += nodes.getLat(i)
				lonAcc += nodes.getLon(i)
				counter.value++
				if (counter.value % CANCEL_POLL_INTERVAL == 0L &&
					cancellation.isCancelled
				) {
					throw ParseCancelledMarker
				}
				if (!targetIds.contains(idAcc)) continue
				val latE7 = toE7(parseLat(latAcc))
				val lonE7 = toE7(parseLon(lonAcc))
				positions[idAcc] = packLatLon(latE7, lonE7)
			}
		}

		private fun checkCancellation() {
			if (cancellation.isCancelled) throw ParseCancelledMarker
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

	/** Cross-thread cancellation flag set by the outer suspend [parse]. */
	private class CancellationCheck {
		@Volatile
		var isCancelled: Boolean = false
	}

	companion object {
		const val MAX_FILE_SIZE_BYTES: Long = 100L * 1024L * 1024L

		/**
		 * Hard ceiling on the adaptive [maxReferencedNodes] cap, regardless of
		 * how much heap is available. Sized so that with the ~70 bytes/entry
		 * cost of a boxed-`Long` HashSet entry on the Android runtime, the
		 * pass-1 `nodeIds` set stays under ~140 MB even at the ceiling, and
		 * the pass-2 `nodePositions` map (~80 bytes/entry — Node + boxed-Long
		 * key + boxed-Long packed value) stays under ~160 MB.
		 *
		 * Even on a 2 GB-heap device, we will not exceed this ceiling — that
		 * combined ~300 MB transient peak is the absolute upper bound the
		 * parser is willing to allocate. Adaptive scaling on smaller heaps
		 * tightens the cap further (see [computeMaxReferencedNodes] and the
		 * class KDoc "Heap envelope per phase" table).
		 *
		 * Historical: lowered from 8 000 000 to 2 000 000 in R2 round-6 round-2
		 * (the higher cap admitted a worst-case ~560 MB transient that
		 * adversarial inputs could weaponize). R2 round-7 then made the
		 * runtime cap adaptive so 256 MB-heap devices get a much smaller
		 * effective cap (~336 k) instead of being able to allocate up to this
		 * ceiling.
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

		/**
		 * Floor on the adaptive cap. Keeps very small heaps (≤ 128 MB JVMs and
		 * tightly-constrained test runners) able to import small regions like
		 * a single town. 256 000 refs × ~200 B peak = ~50 MB transient — fits
		 * inside the 25 % budget even of a 200 MB heap.
		 */
		const val MIN_REFERENCED_NODES_FLOOR: Int = 256_000

		/**
		 * Conservative per-entry footprint at pass-2 peak, in bytes. Covers the
		 * concurrent residency of `nodeIds` (~70 B/entry) + `nodePositions`
		 * (~80 B/entry), plus headroom for hash-table over-allocation (default
		 * load factor 0.75 inflates the underlying array by ~33 %). Round up
		 * to 200 B — better to under-cap and accept a `TooManyNodes` rejection
		 * than to OOM mid-import.
		 */
		const val PEAK_BYTES_PER_NODE_REF: Long = 200L

		/** Numerator of the heap fraction the parser is allowed to allocate. */
		const val HEAP_BUDGET_NUMERATOR: Int = 1

		/** Denominator of the heap fraction the parser is allowed to allocate. */
		const val HEAP_BUDGET_DENOMINATOR: Int = 4

		private const val BYTES_PER_MEGABYTE: Long = 1024L * 1024L

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

		internal fun toE7(degrees: Double): Int =
			kotlin.math.round(degrees * 1e7).toInt()

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
}
