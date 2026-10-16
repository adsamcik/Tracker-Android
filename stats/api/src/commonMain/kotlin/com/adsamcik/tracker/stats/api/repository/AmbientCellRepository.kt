package com.adsamcik.tracker.stats.api.repository

import kotlinx.serialization.Serializable

/**
 * Distinguishes on-device readings from portable imported data without changing their measured
 * times.
 */
@Serializable
enum class AmbientCellOrigin { LOCAL_DEVICE, PORTABLE_IMPORT }
/**
 * Current source availability presented separately from retained history and coverage.
 */
enum class AmbientCellAvailability {
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_LIMITED,
	AVAILABLE,
}
/**
 * Whether the saved Cell fact can verify its represented coverage.
 */
@Serializable
enum class AmbientCellCoverage { COMPLETE, UNVERIFIABLE }
/**
 * Whether represented subscription children are complete, partial or unobservable.
 */
@Serializable
enum class AmbientCellSubscriptionCompleteness { COMPLETE, PARTIAL, UNKNOWN }

/**
 * Half-open UTC millisecond selection, nonempty origin filter and fact limit from 1 to 4096.
 */
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

/**
 * Exact saved local day/zone selection with a nonempty origin filter and fact limit from 1 to
 * 4096.
 */
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

/**
 * Nonnegative observed tower counts by radio technology; their sum is the represented observation
 * count.
 */
@Serializable
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

/**
 * Nonnegative counts of Android signal-quality bins, including separate unavailable and
 * none/unknown values.
 */
@Serializable
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

/**
 * One all-day Cell summary revision with UTC millisecond uncertainty bounds, saved local day/zone
 * and available tower details.
 */
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
	val towers: List<CellObservedTower> = emptyList(),
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
		require(towers.size <= observationCount)
	}
}

/**
 * Saved Cell coverage gap with original identity, UTC millisecond bounds, local day/zone and
 * reason.
 */
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

/**
 * Retained Cell facts and gaps with current availability, or explicit dependency/storage failure.
 */
sealed interface AmbientCellReadResult {
	/**
	 * Saved facts and explicit timed gaps with the separately reported current source availability.
	 */
	data class Snapshot(
		val facts: List<AmbientCellFact>,
		val gaps: List<AmbientCellGap>,
		val availability: AmbientCellAvailability,
	) : AmbientCellReadResult
	/**
	 * A required saved-data dependency exceeds the bounded read budget; a truncated prefix is not a
	 * complete result.
	 */
	data object DependencyOverflow : AmbientCellReadResult
	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientCellReadResult
}

/**
 * Read-only all-day Cell access independent of sessions and optional Location enrichment.
 */
interface AmbientCellRepository {
	/**
	 * Reads the bounded UTC millisecond range for the selected Cell origins, preserving saved gaps
	 * and availability.
	 */
	suspend fun read(request: AmbientCellReadRequest): AmbientCellReadResult
	/**
	 * Reads one exact saved Cell local day/zone with the selected origins and finite fact bound.
	 */
	suspend fun readDay(request: AmbientCellDayReadRequest): AmbientCellReadResult
}

private const val MAX_AMBIENT_CELL_FACTS = 4_096
