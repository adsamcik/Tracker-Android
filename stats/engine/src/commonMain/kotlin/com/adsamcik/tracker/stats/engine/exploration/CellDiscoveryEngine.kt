package com.adsamcik.tracker.stats.engine.exploration

import com.adsamcik.tracker.stats.api.DiscoveryQuality

/**
 * Configuration for cell discovery behavior.
 *
 * @property cellLevel S2 cell level for exploration tracking (14 = ~0.8 km2).
 * @property minAccuracyM Maximum acceptable horizontal accuracy in metres.
 *   Locations with worse accuracy are silently dropped.
 * @property briefVisitMs Time threshold for [DiscoveryQuality.CYCLED_THROUGH] (ms).
 * @property visitMs Time threshold for [DiscoveryQuality.TRAVERSED_ON_FOOT] (ms).
 * @property exploredMs Time threshold for [DiscoveryQuality.EXPLORED] (ms).
 * @property thoroughMs Time threshold for [DiscoveryQuality.THOROUGHLY_EXPLORED] (ms).
 */
data class CellDiscoveryConfig(
	val cellLevel: Int = 14,
	val minAccuracyM: Float = 100f,
	val briefVisitMs: Long = 30_000L,
	val visitMs: Long = 120_000L,
	val exploredMs: Long = 300_000L,
	val thoroughMs: Long = 600_000L,
)

/**
 * Represents a discovered or revisited S2 cell.
 *
 * Emitted by [CellDiscoveryEngine] on cell entry (with initial quality) and
 * on cell exit / finalize (with accumulated quality).
 *
 * @property token S2 cell token (hex string, trailing zeros stripped).
 * @property level S2 cell level.
 * @property quality Discovery quality based on time spent in cell.
 * @property isNew Whether this is the first time this cell was discovered
 *   (not present in [CellDiscoveryEngine.knownTokens] or previously emitted).
 * @property centerLatE7 Cell center latitude in E7 format (degrees * 1e7).
 * @property centerLonE7 Cell center longitude in E7 format (degrees * 1e7).
 * @property seasonBit Bitmask for current season (1=spring, 2=summer, 4=autumn, 8=winter).
 */
data class CellDiscovery(
	val token: String,
	val level: Int,
	val quality: DiscoveryQuality,
	val isNew: Boolean,
	val centerLatE7: Int,
	val centerLonE7: Int,
	val seasonBit: Int,
)

/**
 * Engine that processes location signals and discovers S2 cells.
 *
 * Tracks one "current cell" at a time. When the user enters a new cell,
 * the engine emits a [CellDiscovery] for the new cell. When the user leaves
 * that cell (enters another) or tracking stops ([finalize]), the engine emits
 * a [CellDiscovery] for the vacated cell with accumulated quality if the
 * quality exceeds [DiscoveryQuality.PASSED_THROUGH].
 *
 * Thread-safety: NOT thread-safe. Call only from the tracking pipeline's
 * single-threaded context.
 *
 * @param config Discovery configuration.
 * @param clock Injectable clock returning epoch millis, for testability.
 * @param knownTokens Tokens already discovered (loaded from DB at init).
 *   Prevents re-emitting [CellDiscovery.isNew] = true for known cells.
 */
class CellDiscoveryEngine(
	private val config: CellDiscoveryConfig = CellDiscoveryConfig(),
	private val clock: () -> Long = System::currentTimeMillis,
	knownTokens: Set<String> = emptySet(),
) {
	private val discovered: MutableSet<String> = knownTokens.toMutableSet()

	// Current cell tracking state
	private var currentCellId: Long? = null
	private var currentToken: String? = null
	private var cellEntryTimeMs: Long = 0L
	private var cellAccumulatedMs: Long = 0L

	/** Number of unique cells discovered (includes pre-loaded known tokens). */
	val discoveredCount: Int get() = discovered.size

	/**
	 * Process a location signal.
	 *
	 * Returns a list of [CellDiscovery] events:
	 * - At most one exit event for the previous cell (quality upgrade).
	 * - At most one entry event for the new cell.
	 * - Empty list if the location is filtered out or the user stays in the same cell.
	 *
	 * @param latDeg Latitude in degrees.
	 * @param lngDeg Longitude in degrees.
	 * @param accuracyM Horizontal accuracy in metres (null = unknown, accepted).
	 * @param timestampMs Signal timestamp in epoch millis.
	 * @return List of cell discovery events (0, 1, or 2 elements).
	 */
	fun onLocation(
		latDeg: Double,
		lngDeg: Double,
		accuracyM: Float?,
		timestampMs: Long,
	): CellDiscovery? {
		if (accuracyM != null && accuracyM > config.minAccuracyM) return null

		val cellId = S2CellId.fromLatLng(latDeg, lngDeg, config.cellLevel)
		val token = S2CellId.toToken(cellId)

		if (token == currentToken) {
			// Still in same cell - accumulate time
			cellAccumulatedMs += (timestampMs - cellEntryTimeMs).coerceAtLeast(0L)
			cellEntryTimeMs = timestampMs
			return null
		}

		// Cell changed - start tracking the new cell
		currentCellId = cellId
		currentToken = token
		cellEntryTimeMs = timestampMs
		cellAccumulatedMs = 0L

		val isNew = token !in discovered
		if (isNew) {
			discovered.add(token)
		}

		val (centerLat, centerLng) = S2CellId.toLatLng(cellId)
		return CellDiscovery(
			token = token,
			level = config.cellLevel,
			quality = DiscoveryQuality.PASSED_THROUGH,
			isNew = isNew,
			centerLatE7 = (centerLat * 1e7).toInt(),
			centerLonE7 = (centerLng * 1e7).toInt(),
			seasonBit = seasonBit(timestampMs),
		)
	}

	/**
	 * Finalize the current cell, e.g. when tracking stops.
	 *
	 * Returns a [CellDiscovery] with accumulated quality for the current cell,
	 * or null if there is no active cell or the quality is [DiscoveryQuality.PASSED_THROUGH]
	 * (which was already emitted on entry).
	 *
	 * After this call, the current cell is cleared.
	 *
	 * @param timestampMs Current timestamp in epoch millis.
	 */
	fun finalize(timestampMs: Long): CellDiscovery? {
		val token = currentToken ?: return null
		val cellId = currentCellId ?: return null

		cellAccumulatedMs += (timestampMs - cellEntryTimeMs).coerceAtLeast(0L)
		val quality = computeQuality(cellAccumulatedMs)

		// Clear current cell state
		currentCellId = null
		currentToken = null
		cellEntryTimeMs = 0L
		cellAccumulatedMs = 0L

		// PASSED_THROUGH was already emitted on entry - only emit upgrades
		if (quality == DiscoveryQuality.PASSED_THROUGH) return null

		val (centerLat, centerLng) = S2CellId.toLatLng(cellId)
		return CellDiscovery(
			token = token,
			level = config.cellLevel,
			quality = quality,
			isNew = false, // Already emitted as new (or not) on entry
			centerLatE7 = (centerLat * 1e7).toInt(),
			centerLonE7 = (centerLng * 1e7).toInt(),
			seasonBit = seasonBit(timestampMs),
		)
	}

	/**
	 * Reset the engine state, e.g. on tracking restart.
	 * Does not clear the discovered set.
	 */
	fun reset() {
		currentCellId = null
		currentToken = null
		cellEntryTimeMs = 0L
		cellAccumulatedMs = 0L
	}

	/**
	 * Map accumulated time in a cell to a [DiscoveryQuality] tier.
	 *
	 * Quality tiers use the existing enum values as time-based labels:
	 * - PASSED_THROUGH: < briefVisitMs
	 * - CYCLED_THROUGH: briefVisitMs .. visitMs
	 * - TRAVERSED_ON_FOOT: visitMs .. exploredMs
	 * - EXPLORED: exploredMs .. thoroughMs
	 * - THOROUGHLY_EXPLORED: >= thoroughMs
	 */
	internal fun computeQuality(timeInCellMs: Long): DiscoveryQuality = when {
		timeInCellMs >= config.thoroughMs -> DiscoveryQuality.THOROUGHLY_EXPLORED
		timeInCellMs >= config.exploredMs -> DiscoveryQuality.EXPLORED
		timeInCellMs >= config.visitMs -> DiscoveryQuality.TRAVERSED_ON_FOOT
		timeInCellMs >= config.briefVisitMs -> DiscoveryQuality.CYCLED_THROUGH
		else -> DiscoveryQuality.PASSED_THROUGH
	}

	/** Serialize all mutable state for crash-recovery checkpointing. */
	fun serialize(): ByteArray {
		val baos = java.io.ByteArrayOutputStream()
		val dos = java.io.DataOutputStream(baos)
		dos.writeInt(discovered.size)
		for (token in discovered) { dos.writeUTF(token) }
		dos.writeBoolean(currentCellId != null)
		if (currentCellId != null) {
			dos.writeLong(currentCellId!!)
			dos.writeUTF(currentToken!!)
		}
		dos.writeLong(cellEntryTimeMs)
		dos.writeLong(cellAccumulatedMs)
		dos.flush()
		return baos.toByteArray()
	}

	/** Restore mutable state from a checkpoint produced by [serialize]. */
	fun deserialize(data: ByteArray) {
		val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
		discovered.clear()
		val size = dis.readInt()
		repeat(size) { discovered.add(dis.readUTF()) }
		if (dis.readBoolean()) {
			currentCellId = dis.readLong()
			currentToken = dis.readUTF()
		} else {
			currentCellId = null
			currentToken = null
		}
		cellEntryTimeMs = dis.readLong()
		cellAccumulatedMs = dis.readLong()
	}

	companion object {
		/**
		 * Determine the season bitmask bit for a given timestamp.
		 *
		 * Uses calendar months (Northern Hemisphere convention):
		 * - Spring (Mar-May): 1
		 * - Summer (Jun-Aug): 2
		 * - Autumn (Sep-Nov): 4
		 * - Winter (Dec-Feb): 8
		 */
		fun seasonBit(timestampMs: Long): Int {
			// Calculate month from epoch using simple arithmetic (UTC).
			// Days since epoch / approximate days to get month.
			val daysSinceEpoch = timestampMs / 86_400_000L
			// Estimate year and month using Gregorian calendar math
			val totalDays = daysSinceEpoch + 719_468 // days from year 0 to epoch
			val era = (if (totalDays >= 0) totalDays else totalDays - 146_096) / 146_097
			val doe = totalDays - era * 146_097
			val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
			val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
			val mp = (5 * doy + 2) / 153
			val month = (if (mp < 10) mp + 3 else mp - 9).toInt() // 1-12

			return when (month) {
				3, 4, 5 -> 1       // Spring
				6, 7, 8 -> 2       // Summer
				9, 10, 11 -> 4     // Autumn
				else -> 8           // Winter (Dec=12, Jan=1, Feb=2)
			}
		}
	}
}
