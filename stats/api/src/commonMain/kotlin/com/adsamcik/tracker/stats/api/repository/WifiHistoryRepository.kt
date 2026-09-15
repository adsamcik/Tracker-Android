package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Read-only, identity-free access to captured Wi-Fi history. */
interface WifiHistoryRepository {
	/** Resolves the complete logical replacement group containing one physical presentation row. */
	suspend fun session(segmentId: Long): WifiHistoryQuery

	/** Discovers recent logical entries from durable Wi-Fi fact carriers, never presentation counts. */
	suspend fun recent(limit: Int): WifiHistoryPage
}

sealed interface WifiHistoryQuery {
	data object NotFound : WifiHistoryQuery
	data class Found(val entry: WifiHistoryEntry) : WifiHistoryQuery
}

sealed interface WifiHistoryPage {
	data class Available(val entries: List<WifiHistoryEntry>) : WifiHistoryPage
	data class Failed(val cause: WifiHistoryCause) : WifiHistoryPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/** Opaque logical identity. Physical runs and radio identifiers stay internal. */
@JvmInline
value class WifiHistoryEntryKey(private val opaqueValue: String) {
	init {
		require(opaqueValue.isNotBlank())
	}

	override fun toString(): String = "WifiHistoryEntryKey"
}

enum class WifiHistoryProductState { MATERIALIZING, PARTIAL, READY, UNAVAILABLE, MISSING, FAILED }
enum class WifiHistoryCoverage { NONE, PARTIAL, COMPLETE, UNKNOWN }
enum class WifiHistoryAvailability { AVAILABLE }
enum class WifiHistoryResultCompleteness { COMPLETE, PARTIAL }
enum class WifiHistoryBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

enum class WifiHistoryCause(val isIntegrityFailure: Boolean = false) {
	SESSION_ACTIVE,
	MATERIALIZATION_BEHIND,
	SOURCE_NOT_CAPTURED,
	NO_QUALIFIED_FACTS,
	PROVIDER_UNAVAILABLE,
	ACQUISITION_INCOMPLETE,
	RESULT_SET_PARTIAL,
	RETENTION_LIMIT,
	PRIVACY_EPOCH_MISMATCH,
	READ_BUDGET_EXCEEDED(isIntegrityFailure = true),
	PHYSICAL_MEMBERSHIP_INVALID(isIntegrityFailure = true),
	MANIFEST_INTEGRITY_FAILED(isIntegrityFailure = true),
	PLAN_INTEGRITY_FAILED(isIntegrityFailure = true),
	WRITER_PROVENANCE_INVALID(isIntegrityFailure = true),
	FACT_INTEGRITY_FAILED(isIntegrityFailure = true),
	STORED_ZONE_INVALID(isIntegrityFailure = true),
}

/** One logical Wi-Fi entry without SSID, BSSID, route, or Location-derived values. */
data class WifiHistoryEntry(
	val key: WifiHistoryEntryKey,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val storedZoneIds: Set<String>,
	val state: WifiHistoryProductState,
	val coverage: WifiHistoryCoverage,
	val observations: List<WifiHistoryObservation>,
	val causes: Set<WifiHistoryCause> = emptySet(),
) {
	init {
		require(endTime >= startTime)
		require(storedZoneIds.none(String::isBlank))
		when (state) {
			WifiHistoryProductState.READY -> {
				require(observations.isNotEmpty())
				require(coverage == WifiHistoryCoverage.COMPLETE && causes.isEmpty())
			}
			WifiHistoryProductState.PARTIAL -> {
				require(observations.isNotEmpty() && coverage == WifiHistoryCoverage.PARTIAL)
				require(causes.isNotEmpty() && causes.none { it.isIntegrityFailure })
			}
			WifiHistoryProductState.MATERIALIZING,
			WifiHistoryProductState.UNAVAILABLE,
			WifiHistoryProductState.MISSING,
			-> {
				require(observations.isEmpty() && coverage == WifiHistoryCoverage.NONE)
				require(causes.isNotEmpty())
			}
			WifiHistoryProductState.FAILED -> {
				require(observations.isEmpty() && coverage == WifiHistoryCoverage.NONE)
				require(causes.any { it.isIntegrityFailure })
			}
		}
	}
}

/** One qualified delivery reduced to count, band mix, quality, availability, and coverage. */
data class WifiHistoryObservation(
	val intervalStartTime: EpochMs,
	val observedTime: EpochMs,
	val wallTimeUncertaintyMs: Long,
	val availability: WifiHistoryAvailability,
	val resultCompleteness: WifiHistoryResultCompleteness,
	val submittedResultCount: Int,
	val acceptedResultCount: Int,
	val rejectedResultCount: Int,
	val observationCount: Int,
	val bandMix: Map<WifiHistoryBand, Int>,
	val signalQuality: WifiHistorySignalQuality,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
	val storedZoneId: String,
) {
	init {
		require(observedTime >= intervalStartTime)
		require(wallTimeUncertaintyMs >= 0L)
		require(submittedResultCount > 0 && acceptedResultCount > 0)
		require(rejectedResultCount >= 0 && acceptedResultCount + rejectedResultCount == submittedResultCount)
		require((resultCompleteness == WifiHistoryResultCompleteness.COMPLETE) == (rejectedResultCount == 0))
		require(observationCount == acceptedResultCount)
		require(bandMix.values.all { it > 0 } && bandMix.values.sum() == observationCount)
		require(signalQuality.sampleCount == observationCount)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(storedZoneId.isNotBlank())
	}
}

data class WifiHistorySignalQuality(
	val strongestSignalDbm: Int,
	val weakestSignalDbm: Int,
	val meanSignalDbm: Double,
	val sampleCount: Int,
) {
	init {
		require(sampleCount > 0)
		require(strongestSignalDbm >= weakestSignalDbm)
		require(meanSignalDbm.isFinite())
		require(meanSignalDbm in weakestSignalDbm.toDouble()..strongestSignalDbm.toDouble())
	}
}
