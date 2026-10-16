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

	/** Bounded keyset range read over complete local and imported logical entries. */
	suspend fun range(request: WifiHistoryRangeRequest): WifiHistoryRangePage

	/** Structural-day membership derived only from stored zones and authenticated time evidence. */
	suspend fun structuralDays(request: WifiHistoryRangeRequest): WifiHistoryStructuralDayPage
}

/**
 * Exact Wi-Fi lookup outcome, distinguishing an existing product entry from a missing selection.
 */
sealed interface WifiHistoryQuery {
	/**
	 * No matching saved source entry or selection remains.
	 */
	data object NotFound : WifiHistoryQuery
	/**
	 * Contains the selected saved source product; its payload preserves current qualification and
	 * coverage.
	 */
	data class Found(val entry: WifiHistoryEntry) : WifiHistoryQuery
	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: WifiHistoryCause) : WifiHistoryQuery {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Recent Wi-Fi entries or an explicit integrity failure affecting the page.
 */
sealed interface WifiHistoryPage {
	/**
	 * Saved source entries whose individual states retain coverage and qualification.
	 */
	data class Available(val entries: List<WifiHistoryEntry>) : WifiHistoryPage
	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: WifiHistoryCause) : WifiHistoryPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Half-open UTC millisecond Wi-Fi range, bounded page size and optional source-issued
 * continuation.
 */
data class WifiHistoryRangeRequest(
	val fromInclusive: EpochMs,
	val toExclusive: EpochMs,
	val limit: Int,
	val continuation: WifiHistoryRangeContinuation? = null,
) {
	init {
		require(fromInclusive.raw >= 0L)
		require(toExclusive > fromInclusive)
		require(limit in 1..100)
	}
}

/**
 * Nonblank bounded source cursor; its opaque value is validated by the issuing History repository.
 */
@JvmInline
value class WifiHistoryRangeContinuation(val value: String) {
	init {
		require(value.isNotBlank() && value.length <= MAX_WIFI_CONTINUATION_LENGTH)
	}

	override fun toString(): String = "WifiHistoryRangeContinuation"
}

/**
 * Bounded Wi-Fi entries and continuation, or a page-wide integrity failure.
 */
sealed interface WifiHistoryRangePage {
	/**
	 * Bounded saved entries and an optional source-issued continuation; null ends the range.
	 */
	data class Available(
		val entries: List<WifiHistoryEntry>,
		val continuation: WifiHistoryRangeContinuation?,
	) : WifiHistoryRangePage

	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: WifiHistoryCause) : WifiHistoryRangePage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Saved local-day Wi-Fi memberships or an explicit failure of structural allocation.
 */
sealed interface WifiHistoryStructuralDayPage {
	/**
	 * Bounded saved entries and an optional source-issued continuation; null ends the range.
	 */
	data class Available(
		val days: List<WifiHistoryStructuralDay>,
		val continuation: WifiHistoryRangeContinuation?,
	) : WifiHistoryStructuralDayPage

	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: WifiHistoryCause) : WifiHistoryStructuralDayPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Saved local calendar date/zone and the Wi-Fi entry memberships allocated to it.
 */
data class WifiHistoryStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
	val memberships: List<WifiHistoryStructuralDayMembership>,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(memberships.isNotEmpty())
	}
}

/**
 * One Wi-Fi entry with the certainty of its allocation to the saved local day.
 */
data class WifiHistoryStructuralDayMembership(
	val entry: WifiHistoryEntry,
	val allocation: WifiHistoryDayAllocation,
)

/**
 * Whether an entry's saved local-day allocation is exact, partial or unavailable.
 */
enum class WifiHistoryDayAllocation {
	EXACT,
	PARTIAL,
	UNAVAILABLE,
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

/**
 * Exact local or imported Wi-Fi selection; a display identity alone is not imported revision
 * authority.
 */
sealed interface WifiHistorySelection {
	val origin: WifiHistoryOrigin

	/**
	 * Selects or labels native saved data without borrowing imported revision authority.
	 */
	data class Local(val key: WifiLocalHistorySelectionKey) : WifiHistorySelection {
		override val origin: WifiHistoryOrigin = WifiHistoryOrigin.LOCAL
	}

	/**
	 * Carries the source-issued imported identity/revision selection separately from native recording
	 * authority.
	 */
	data class Imported(val selected: WifiImportedHistorySelection) : WifiHistorySelection {
		override val origin: WifiHistoryOrigin = WifiHistoryOrigin.IMPORTED
	}
}

/**
 * Whether the displayed Wi-Fi entry came from local capture or a portable import.
 */
enum class WifiHistoryOrigin { LOCAL, IMPORTED }
/**
 * Presentation state separating active materialization, usable partial/ready data and unavailable
 * or failed Wi-Fi products.
 */
enum class WifiHistoryProductState { MATERIALIZING, PARTIAL, READY, UNAVAILABLE, MISSING, DELETED, FAILED }
/**
 * Amount of the selected Wi-Fi run represented by retained captured evidence.
 */
enum class WifiHistoryCoverage { NONE, PARTIAL, COMPLETE, UNKNOWN }
/**
 * Availability discriminator of a retained Wi-Fi observation.
 */
enum class WifiHistoryAvailability { AVAILABLE }
/**
 * Whether all submitted Wi-Fi results qualified or some were rejected.
 */
enum class WifiHistoryResultCompleteness { COMPLETE, PARTIAL }
/**
 * Frequency band classification retained from Wi-Fi observations, including unclassified
 * frequencies.
 */
enum class WifiHistoryBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

/**
 * Concrete capture, provider, retention and integrity causes; integrity failures are flagged
 * separately.
 */
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
	RANGE_CONTINUATION_INVALID(isIntegrityFailure = true),
	READ_BUDGET_EXCEEDED(isIntegrityFailure = true),
	PHYSICAL_MEMBERSHIP_INVALID(isIntegrityFailure = true),
	MANIFEST_INTEGRITY_FAILED(isIntegrityFailure = true),
	PLAN_INTEGRITY_FAILED(isIntegrityFailure = true),
	WRITER_PROVENANCE_INVALID(isIntegrityFailure = true),
	FACT_INTEGRITY_FAILED(isIntegrityFailure = true),
	STORED_ZONE_INVALID(isIntegrityFailure = true),
	VALUE_OVERFLOW(isIntegrityFailure = true),
}

/** One logical Wi-Fi entry. Network details are device observations, never network positions. */
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
		require(origin == WifiHistoryOrigin.LOCAL || observations.all { it.localSourceEventId == null })
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

/** One qualified delivery with its available network details. */
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
	/** Opaque native provider event used only to join independently retained optional context. */
	val localSourceEventId: String? = null,
	val networks: List<WifiObservedNetwork> = emptyList(),
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
		require(localSourceEventId == null || localSourceEventId.isNotBlank())
		require(networks.size <= acceptedResultCount)
	}
}

/** A network seen by the phone at one time; either identifier may be hidden by Android. */
data class WifiObservedNetwork(
	val observedTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val bssid: String?,
	val ssid: String?,
	val frequencyMhz: Int,
	val signalDbm: Int,
) {
	init {
		require(observedTimeMs >= 0L && wallTimeUncertaintyMs >= 0L)
		require(bssid == null || bssid.isNotBlank())
		require(ssid == null || ssid.isNotEmpty())
		require(frequencyMhz > 0 && signalDbm in -200..100)
	}

	override fun toString(): String = "WifiObservedNetwork"
}

/**
 * Retained strongest, weakest and mean signal in dBm with the actual represented sample count.
 */
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

private const val MAX_WIFI_CONTINUATION_LENGTH = 1_024
