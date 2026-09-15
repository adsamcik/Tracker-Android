package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Read-only, identity-free access to captured Wi-Fi history. */
interface WifiHistoryRepository {
	/** Resolves the complete logical replacement group containing one physical presentation row. */
	suspend fun session(segmentId: Long): WifiHistoryQuery

	/** Resolves one exact imported-origin selection without inferring native membership. */
	suspend fun imported(selection: WifiImportedHistorySelectionKey): WifiHistoryQuery

	/** Resolves one source-issued opaque selection; callers never derive or decode the key. */
	suspend fun lookup(selection: WifiHistorySelection): WifiHistoryQuery

	/** Discovers recent local and imported entries from authenticated source facts. */
	suspend fun recent(limit: Int): WifiHistoryPage
}

sealed interface WifiHistoryQuery {
	data object NotFound : WifiHistoryQuery
	data class Found(val entry: WifiHistoryEntry) : WifiHistoryQuery
	data class Failed(val cause: WifiHistoryCause) : WifiHistoryQuery {
		init {
			require(cause.isIntegrityFailure)
		}
	}
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

/** Opaque imported entry identity suitable only for exact imported-origin selection. */
@JvmInline
value class WifiImportedHistorySelectionKey(val value: String) {
	init {
		require(LOWERCASE_SHA_256.matches(value))
	}

	override fun toString(): String = "WifiImportedHistorySelectionKey"
}

/** Opaque local logical-entry key issued only by the authenticated Wi-Fi history repository. */
@JvmInline
value class WifiLocalHistorySelectionKey(val value: String) {
	init {
		require(LOWERCASE_SHA_256.matches(value))
	}

	override fun toString(): String = "WifiLocalHistorySelectionKey"
}

/** Exact imported revision selected from an authenticated current-origin history snapshot. */
data class WifiImportedHistorySelection(
	val key: WifiImportedHistorySelectionKey,
	val importRevision: Long,
	val contentChecksum: String,
) {
	init {
		require(importRevision > 0L)
		require(LOWERCASE_SHA_256.matches(contentChecksum))
	}

	override fun toString(): String = "WifiImportedHistorySelection"
}

sealed interface WifiHistorySelection {
	val origin: WifiHistoryOrigin

	data class Local(val key: WifiLocalHistorySelectionKey) : WifiHistorySelection {
		override val origin: WifiHistoryOrigin = WifiHistoryOrigin.LOCAL
	}

	data class Imported(val selected: WifiImportedHistorySelection) : WifiHistorySelection {
		override val origin: WifiHistoryOrigin = WifiHistoryOrigin.IMPORTED
	}
}

enum class WifiHistoryOrigin { LOCAL, IMPORTED }
enum class WifiHistoryProductState { MATERIALIZING, PARTIAL, READY, UNAVAILABLE, MISSING, DELETED, FAILED }
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
	DELETED,
	PRIVACY_EPOCH_MISMATCH,
	IMPORTED_EVIDENCE_UNVERIFIABLE(isIntegrityFailure = true),
	ORIGIN_IDENTITY_CONFLICT(isIntegrityFailure = true),
	STALE_SELECTION(isIntegrityFailure = true),
	READ_BUDGET_EXCEEDED(isIntegrityFailure = true),
	PHYSICAL_MEMBERSHIP_INVALID(isIntegrityFailure = true),
	MANIFEST_INTEGRITY_FAILED(isIntegrityFailure = true),
	PLAN_INTEGRITY_FAILED(isIntegrityFailure = true),
	WRITER_PROVENANCE_INVALID(isIntegrityFailure = true),
	FACT_INTEGRITY_FAILED(isIntegrityFailure = true),
	STORED_ZONE_INVALID(isIntegrityFailure = true),
	VALUE_OVERFLOW(isIntegrityFailure = true),
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
	val origin: WifiHistoryOrigin = WifiHistoryOrigin.LOCAL,
	val importedSelection: WifiImportedHistorySelection? = null,
	val localSelection: WifiLocalHistorySelectionKey? = null,
	/** Exact local manifest proof only; imported portable evidence never grants this claim. */
	val capturesOnlyWifi: Boolean = false,
) {
	val selection: WifiHistorySelection?
		get() = when {
			localSelection != null -> WifiHistorySelection.Local(localSelection)
			importedSelection != null -> WifiHistorySelection.Imported(importedSelection)
			else -> null
		}

	init {
		require(endTime >= startTime)
		require(storedZoneIds.none(String::isBlank))
		require(importedSelection == null || origin == WifiHistoryOrigin.IMPORTED)
		require(localSelection == null || origin == WifiHistoryOrigin.LOCAL)
		require(importedSelection == null || localSelection == null)
		require(origin == WifiHistoryOrigin.LOCAL || !capturesOnlyWifi)
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
			WifiHistoryProductState.DELETED,
			-> {
				require(observations.isEmpty() && coverage == WifiHistoryCoverage.NONE)
				require(causes.isNotEmpty())
				require((state == WifiHistoryProductState.DELETED) ==
					(causes == setOf(WifiHistoryCause.DELETED)))
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

private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
