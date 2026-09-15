package com.adsamcik.tracker.stats.api.repository

enum class AmbientWifiOrigin { LOCAL_DEVICE, PORTABLE_IMPORT }
enum class AmbientWifiAvailability {
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_LIMITED,
	AVAILABLE,
}
enum class AmbientWifiCoverage { COMPLETE, UNVERIFIABLE }

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
	}
}

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

sealed interface AmbientWifiReadResult {
	data class Snapshot(
		val facts: List<AmbientWifiFact>,
		val gaps: List<AmbientWifiGap>,
		val availability: AmbientWifiAvailability,
	) : AmbientWifiReadResult

	data object DependencyOverflow : AmbientWifiReadResult
	data object StorageUnavailable : AmbientWifiReadResult
}

interface AmbientWifiRepository {
	suspend fun read(request: AmbientWifiReadRequest): AmbientWifiReadResult
	suspend fun readDay(request: AmbientWifiDayReadRequest): AmbientWifiReadResult
}

private const val MAX_AMBIENT_WIFI_FACTS = 4_096
