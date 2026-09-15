package com.adsamcik.tracker.stats.api.repository

enum class AmbientCellOrigin { LOCAL_DEVICE, PORTABLE_IMPORT }
enum class AmbientCellAvailability {
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_LIMITED,
	AVAILABLE,
}
enum class AmbientCellCoverage { COMPLETE, UNVERIFIABLE }
enum class AmbientCellSubscriptionCompleteness { COMPLETE, PARTIAL, UNKNOWN }

data class AmbientCellReadRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val origins: Set<AmbientCellOrigin>,
	val limit: Int,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(origins.isNotEmpty())
		require(limit in 1..MAX_AMBIENT_CELL_FACTS)
	}
}

data class AmbientCellDayReadRequest(
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val origins: Set<AmbientCellOrigin>,
	val limit: Int,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(origins.isNotEmpty())
		require(limit in 1..MAX_AMBIENT_CELL_FACTS)
	}
}

data class AmbientCellTechnologyMix(
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
) {
	init {
		require(listOf(gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount)
			.all { it >= 0 })
	}

	val totalCount: Int
		get() = gsmCount + cdmaCount + wcdmaCount + tdscdmaCount + lteCount + nrCount
}

data class AmbientCellQualityDistribution(
	val unknownCount: Int,
	val noneOrUnknownCount: Int,
	val poorCount: Int,
	val moderateCount: Int,
	val goodCount: Int,
	val greatCount: Int,
) {
	init {
		require(listOf(
			unknownCount,
			noneOrUnknownCount,
			poorCount,
			moderateCount,
			goodCount,
			greatCount,
		).all { it >= 0 })
	}

	val totalCount: Int
		get() = unknownCount + noneOrUnknownCount + poorCount + moderateCount + goodCount +
			greatCount
}

@Suppress("LongParameterList")
data class AmbientCellFact(
	val identity: String,
	val origin: AmbientCellOrigin,
	val coverageStartTimeMs: Long,
	val observedTimeMs: Long,
	val latestPossibleTimeMs: Long,
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val coverage: AmbientCellCoverage,
	val subscriptionCompleteness: AmbientCellSubscriptionCompleteness,
	val observationCount: Int,
	val registeredObservationCount: Int,
	val technologyMix: AmbientCellTechnologyMix,
	val qualityDistribution: AmbientCellQualityDistribution,
	val semanticRevision: Long = 1L,
	val supersedesSemanticRevision: Long? = null,
) {
	init {
		require(identity.isNotBlank())
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(latestPossibleTimeMs >= observedTimeMs)
		require(storedZoneId.isNotBlank())
		require(observationCount > 0 && registeredObservationCount in 0..observationCount)
		require(technologyMix.totalCount == observationCount)
		require(qualityDistribution.totalCount == observationCount)
		require(semanticRevision in 1L..65_536L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
	}
}

data class AmbientCellGap(
	val identity: String,
	val origin: AmbientCellOrigin,
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

sealed interface AmbientCellReadResult {
	data class Snapshot(
		val facts: List<AmbientCellFact>,
		val gaps: List<AmbientCellGap>,
		val availability: AmbientCellAvailability,
	) : AmbientCellReadResult
	data object DependencyOverflow : AmbientCellReadResult
	data object StorageUnavailable : AmbientCellReadResult
}

interface AmbientCellRepository {
	suspend fun read(request: AmbientCellReadRequest): AmbientCellReadResult
	suspend fun readDay(request: AmbientCellDayReadRequest): AmbientCellReadResult
}

private const val MAX_AMBIENT_CELL_FACTS = 4_096
