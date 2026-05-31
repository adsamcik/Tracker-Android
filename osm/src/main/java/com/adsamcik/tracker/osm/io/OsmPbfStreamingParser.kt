package com.adsamcik.tracker.osm.io

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
 * **Heap envelope per phase (R2 round-6, round-2):** the parser allocates two
 * heavyweight intermediate maps and one buffer. Because both maps key on
 * boxed [Long]s, the per-entry footprint is dominated by the JVM's
 * `HashMap.Node` (~48 B) plus the boxed [Long] (~24 B) — roughly **72 bytes
 * per node id** in `nodeIds` and **80 bytes per (id → packed lat/lon)
 * entry** in `nodePositions`. With [MAX_REFERENCED_NODES] capped at
 * **2 000 000**, that gives:
 *
 *  - After pass 1: `nodeIds` ≤ ~140 MB; `wayBuffer` typically ≤ 5 MB
 *    (refs are stored as primitive `LongArray`s — no boxing).
 *  - After pass 2: `nodeIds` is no longer needed and is explicitly
 *    [HashSet.clear]ed before the emit loop begins, freeing ~140 MB.
 *  - During emit: only `nodePositions` (~160 MB) and `wayBuffer` remain
 *    resident, plus the in-flight [ParsedOsmWay] batch (≤
 *    [DEFAULT_WAY_BATCH_SIZE] entries × small bbox header). Downstream
 *    [OsmImportWorker] persists each batch in 5 000-way DB chunks, so peak
 *    transient allocations outside the parser stay bounded too.
 *
 * Together with the [MAX_FILE_SIZE_BYTES] guard (100 MB) this keeps the worst
 * realistic OSM import — a fully driveable, urban .osm.pbf — well under the
 * Android `dalvik.vm.heapgrowthlimit` envelope on common phones (256 MB on
 * mid-range, 512 MB on high-end).
 *
 * Memory guardrails (Phase 2a + R2 round-6 round-2):
 *  - PBF file size must not exceed [MAX_FILE_SIZE_BYTES] (~100 MB).
 *  - Referenced node count must not exceed [MAX_REFERENCED_NODES] (2 000 000).
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
class OsmPbfStreamingParser {

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
		// the boxed-Long HashSet (~70 bytes per entry × up to MAX_REFERENCED_NODES)
		// before the emit loop allocates per-way [ParsedOsmWay] objects. This
		// matches the per-phase heap envelope documented on the class KDoc.
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
					if (nodeIds.size > MAX_REFERENCED_NODES) {
						throw OsmParseException.TooManyNodes(
							nodeIds.size.toLong(),
							MAX_REFERENCED_NODES.toLong(),
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
		 * Upper bound on distinct referenced node ids retained across both
		 * passes. Sized so that with the ~70 bytes/entry cost of a
		 * boxed-`Long` HashSet entry on the Android runtime, the pass-1
		 * `nodeIds` set stays under ~140 MB even at the cap, and the pass-2
		 * `nodePositions` map (~80 bytes/entry — Node + boxed-Long key + boxed-
		 * Long packed value) stays under ~160 MB. Both combined with the
		 * [MAX_FILE_SIZE_BYTES] file cap keep peak parser RSS well inside the
		 * Android `dalvik.vm.heapgrowthlimit` envelope for mid-range phones.
		 *
		 * Lowered from 8 000 000 to 2 000 000 in R2 round-6, round-2 — the
		 * higher cap admitted a worst-case ~560 MB transient allocation that
		 * realistic .osm.pbf payloads never need but adversarial inputs could
		 * weaponize. See the class KDoc "Heap envelope" section.
		 */
		const val MAX_REFERENCED_NODES: Int = 2_000_000
		const val DEFAULT_WAY_BATCH_SIZE: Int = 1_000
		private const val MIN_NODES_PER_WAY = 2
		private const val INITIAL_NODE_ID_CAPACITY = 1 shl 17
		private const val INITIAL_WAY_BUFFER_CAPACITY = 1 shl 14

		/** Poll the cancellation flag every N processed dense-node entries. */
		private const val CANCEL_POLL_INTERVAL: Long = 16_384L

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
