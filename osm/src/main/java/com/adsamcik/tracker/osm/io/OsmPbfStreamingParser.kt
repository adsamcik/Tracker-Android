package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.osm.OsmRoadClass
import de.topobyte.osm4j.core.model.iface.EntityType
import de.topobyte.osm4j.core.model.iface.OsmNode
import de.topobyte.osm4j.core.model.iface.OsmWay
import de.topobyte.osm4j.pbf.seq.PbfIterator
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Streaming two-pass parser over an OpenStreetMap PBF file.
 *
 * Pass 1 walks every way and buffers driveable ones (per [OsmRoadClass]) plus
 * the set of node ids they reference. Pass 2 walks every node and keeps lat/lon
 * (in E7 packed into a single Long) for the referenced ids only. The buffered
 * ways are then resolved into [ParsedOsmWay] batches and handed to
 * [onWayBatch] for persistence.
 *
 * Strict offline contract: the input stream factory MUST point at a local file
 * — the parser never opens a URL.
 *
 * Memory guardrails (Phase 2a):
 *  - PBF file size must not exceed [MAX_FILE_SIZE_BYTES] (~100 MB).
 *  - Referenced node count must not exceed [MAX_REFERENCED_NODES].
 *
 * Cancellation: cooperative — the parser polls [coroutineContext] periodically.
 *
 * Thread safety: a single [parse] invocation is single-threaded; concurrent
 * invocations on one instance are NOT supported.
 */
class OsmPbfStreamingParser {

	/**
	 * Parses [openInputStream] (called twice — once per pass) and emits
	 * [ParsedOsmWay] batches of size [wayBatchSize] via [onWayBatch].
	 *
	 * Progress callbacks are coarse-grained — one per [PROGRESS_INTERVAL]
	 * entities — so callers can drive a "parsing X of Y" notification.
	 *
	 * @return aggregate counts and the overall bounding box of every emitted
	 *   way's geometry.
	 *
	 * @throws OsmParseException when the file is too large, has too many
	 *   referenced nodes, or is structurally invalid.
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

		// --- Pass 1: scan ways ---
		val nodeIds = HashSet<Long>(INITIAL_NODE_ID_CAPACITY)
		val wayBuffer = ArrayList<BufferedWay>(INITIAL_WAY_BUFFER_CAPACITY)
		var pass1Count = 0L
		openInputStream().use { input ->
			val iter = PbfIterator(input, false)
			while (iter.hasNext()) {
				val container = iter.next()
				if (container.type != EntityType.Way) continue
				val way = container.entity as OsmWay
				val tags = collectTags(way::getNumberOfTags, way::getTag)
				val roadClass = OsmRoadClass.fromOsmValue(tags["highway"]) ?: continue

				val nodeCount = way.numberOfNodes
				if (nodeCount < MIN_NODES_PER_WAY) continue
				val nodes = LongArray(nodeCount)
				for (i in 0 until nodeCount) {
					val id = way.getNodeId(i)
					nodes[i] = id
					nodeIds.add(id)
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
						nodeIds = nodes,
					),
				)

				pass1Count++
				if (pass1Count % PROGRESS_INTERVAL == 0L) {
					coroutineContext.ensureActive()
					onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_WAYS, pass1Count))
				}
			}
		}
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_WAYS, pass1Count))

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

		// --- Pass 2: scan nodes ---
		val nodePositions = HashMap<Long, Long>(nodeIds.size)
		var pass2Count = 0L
		openInputStream().use { input ->
			val iter = PbfIterator(input, false)
			while (iter.hasNext()) {
				val container = iter.next()
				if (container.type != EntityType.Node) continue
				val node = container.entity as OsmNode
				if (!nodeIds.contains(node.id)) continue
				val latE7 = toE7(node.latitude)
				val lonE7 = toE7(node.longitude)
				nodePositions[node.id] = packLatLon(latE7, lonE7)

				pass2Count++
				if (pass2Count % PROGRESS_INTERVAL == 0L) {
					coroutineContext.ensureActive()
					onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_NODES, pass2Count))
				}
			}
		}
		onProgress?.invoke(OsmParseProgress(OsmParsePhase.SCAN_NODES, pass2Count))

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
				nodeCount = pass2Count,
				wayCount = 0L,
				minLatE7 = 0,
				maxLatE7 = 0,
				minLonE7 = 0,
				maxLonE7 = 0,
			)
		} else {
			OsmParseStats(
				nodeCount = pass2Count,
				wayCount = emittedWays,
				minLatE7 = globalMinLat,
				maxLatE7 = globalMaxLat,
				minLonE7 = globalMinLon,
				maxLonE7 = globalMaxLon,
			)
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

	private fun collectTags(
		count: () -> Int,
		getter: (Int) -> de.topobyte.osm4j.core.model.iface.OsmTag,
	): Map<String, String> {
		val n = count()
		if (n == 0) return emptyMap()
		val out = HashMap<String, String>(n)
		for (i in 0 until n) {
			val tag = getter(i)
			out[tag.key] = tag.value
		}
		return out
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

	companion object {
		const val MAX_FILE_SIZE_BYTES: Long = 100L * 1024L * 1024L
		const val MAX_REFERENCED_NODES: Int = 8_000_000
		const val DEFAULT_WAY_BATCH_SIZE: Int = 1_000
		private const val MIN_NODES_PER_WAY = 2
		private const val PROGRESS_INTERVAL: Long = 50_000L
		private const val INITIAL_NODE_ID_CAPACITY = 1 shl 17
		private const val INITIAL_WAY_BUFFER_CAPACITY = 1 shl 14

		internal fun toE7(degrees: Double): Int =
			kotlin.math.round(degrees * 1e7).toInt()

		internal fun packLatLon(latE7: Int, lonE7: Int): Long =
			(latE7.toLong() shl 32) or (lonE7.toLong() and 0xFFFFFFFFL)

		internal fun unpackLat(packed: Long): Int = (packed shr 32).toInt()
		internal fun unpackLon(packed: Long): Int = packed.toInt()
	}
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
