package com.adsamcik.tracker.stats.api.repository

import kotlinx.serialization.Serializable

/**
 * Distinguishes on-device readings from portable imported data without changing their measured
 * times.
 */
@Serializable
enum class AmbientWifiOrigin { LOCAL_DEVICE, PORTABLE_IMPORT }
/**
 * Current source availability presented separately from retained history and coverage.
 */
enum class AmbientWifiAvailability {
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_LIMITED,
	AVAILABLE,
}
/**
 * Whether the saved Wi-Fi fact can verify its represented coverage.
 */
@Serializable
enum class AmbientWifiCoverage { COMPLETE, UNVERIFIABLE }

/**
 * Half-open UTC millisecond selection, nonempty origin filter and fact limit from 1 to 4096.
 */
data class AmbientWifiReadRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val origins: Set<AmbientWifiOrigin>,
	val limit: Int,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(origins.isNotEmpty())
		require(limit in 1..MAX_AMBIENT_WIFI_FACTS)
	}
}

/**
 * Exact saved local day/zone selection with a nonempty origin filter and fact limit from 1 to
 * 4096.
 */
data class AmbientWifiDayReadRequest(
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val origins: Set<AmbientWifiOrigin>,
	val limit: Int,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(origins.isNotEmpty())
		require(limit in 1..MAX_AMBIENT_WIFI_FACTS)
	}
}

/**
 * One all-day Wi-Fi summary revision with UTC millisecond uncertainty bounds, local day/zone, dBm
 * signal and available network details.
 */
@Suppress("LongParameterList")
data class AmbientWifiFact(
	val identity: String,
	val origin: AmbientWifiOrigin,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val coverage: AmbientWifiCoverage,
	val observationCount: Int,
	val twoPointFourGhzCount: Int,
	val fiveGhzCount: Int,
	val sixGhzCount: Int,
	val otherBandCount: Int,
	val strongestSignalDbm: Int,
	val weakestSignalDbm: Int,
	val meanSignalDbm: Double,
	val semanticRevision: Long = 1L,
	val supersedesSemanticRevision: Long? = null,
	val networks: List<WifiObservedNetwork> = emptyList(),
) {
	init {
		require(identity.isNotBlank())
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(latestPossibleTimeMs >= observedTimeMs)
		require(storedZoneId.isNotBlank())
		require(observationCount > 0)
		require(twoPointFourGhzCount + fiveGhzCount + sixGhzCount + otherBandCount ==
			observationCount)
		require(strongestSignalDbm >= weakestSignalDbm)
		require(meanSignalDbm.isFinite() &&
			meanSignalDbm in weakestSignalDbm.toDouble()..strongestSignalDbm.toDouble())
		require(semanticRevision in 1L..65_536L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require(networks.size <= observationCount)
	}
}

/**
 * Saved Wi-Fi coverage gap with original identity, UTC millisecond bounds, local day/zone and
 * reason.
 */
data class AmbientWifiGap(
	val identity: String,
	val origin: AmbientWifiOrigin,
	val structuralEpochDay: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val storedZoneId: String,
	val reason: String,
) {
	init {
		require(identity.isNotBlank())
		require(startTimeMs >= 0L && endTimeMs > startTimeMs)
		require(storedZoneId.isNotBlank() && reason.isNotBlank())
	}
}

/**
 * Retained Wi-Fi facts and gaps with current availability, or explicit dependency/storage failure.
 */
sealed interface AmbientWifiReadResult {
	/**
	 * Saved facts and explicit timed gaps with the separately reported current source availability.
	 */
	data class Snapshot(
		val facts: List<AmbientWifiFact>,
		val gaps: List<AmbientWifiGap>,
		val availability: AmbientWifiAvailability,
	) : AmbientWifiReadResult

	/**
	 * A required saved-data dependency exceeds the bounded read budget; a truncated prefix is not a
	 * complete result.
	 */
	data object DependencyOverflow : AmbientWifiReadResult
	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientWifiReadResult
}

/**
 * Read-only all-day Wi-Fi access independent of sessions and optional Location enrichment.
 */
interface AmbientWifiRepository {
	/**
	 * Reads the bounded UTC millisecond range for the selected Wi-Fi origins, preserving saved gaps
	 * and availability.
	 */
	suspend fun read(request: AmbientWifiReadRequest): AmbientWifiReadResult
	/**
	 * Reads one exact saved Wi-Fi local day/zone with the selected origins and finite fact bound.
	 */
	suspend fun readDay(request: AmbientWifiDayReadRequest): AmbientWifiReadResult
}

private const val MAX_AMBIENT_WIFI_FACTS = 4_096
