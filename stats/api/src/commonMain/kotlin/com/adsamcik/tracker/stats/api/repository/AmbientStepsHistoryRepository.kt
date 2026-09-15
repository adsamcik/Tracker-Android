package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/**
 * Read-only Ambient Steps history over exact stored structural-day authority.
 *
 * Historical qualification is independent of current platform capability. Implementations never
 * probe permissions, start a provider, acquire demand, repair storage, or use the current zone.
 */
interface AmbientStepsHistoryRepository {
	suspend fun readDay(request: AmbientStepsHistoryDayRequest): AmbientStepsHistoryRead

	/** One bounded source snapshot for every requested structural day; no per-day storage fan-out. */
	suspend fun readRange(request: AmbientStepsHistoryRangeRequest): AmbientStepsHistoryRead

	/** Newest source-qualified structural days using an exact keyset continuation. */
	suspend fun readRecent(request: AmbientStepsHistoryRecentRequest): AmbientStepsHistoryRecentRead

	fun observeRange(request: AmbientStepsHistoryRangeRequest): Flow<AmbientStepsHistoryRead>
}

/** Narrow source total boundary for numeric consumers that already own the Room transaction. */
interface AmbientStepsNumericRangeReader {
	suspend fun readNumericRangeInCurrentTransaction(
		request: AmbientStepsHistoryRangeRequest,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericRangeRead
}

data class AmbientStepsStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= MAX_ZONE_ID_LENGTH)
	}

	companion object {
		const val MAX_ZONE_ID_LENGTH = 128
	}
}

data class AmbientStepsHistoryDayRequest(
	val day: AmbientStepsStructuralDay,
)

data class AmbientStepsHistoryRangeRequest(
	val days: List<AmbientStepsStructuralDay>,
) {
	init {
		require(days.isNotEmpty())
		require(days.size <= MAX_DAY_COUNT)
		require(days.distinct().size == days.size)
		require(days == days.sortedWith(
			compareBy(AmbientStepsStructuralDay::epochDay)
				.thenBy(AmbientStepsStructuralDay::storedZoneId),
		))
		val span = days.last().epochDay.toULong() - days.first().epochDay.toULong()
		require(span < MAX_DAY_COUNT.toULong())
	}

	companion object {
		const val MAX_DAY_COUNT = 370
	}
}

data class AmbientStepsHistoryRecentCursor(
	val latestEvidenceTimeMs: Long,
	val epochDay: Long,
	val storedZoneId: String,
	val opaqueDayIdentity: String,
) {
	init {
		require(latestEvidenceTimeMs >= 0L)
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= AmbientStepsStructuralDay.MAX_ZONE_ID_LENGTH)
		require(OPAQUE_DIGEST.matches(opaqueDayIdentity))
	}
}

data class AmbientStepsHistoryRecentRequest(
	val limit: Int,
	val before: AmbientStepsHistoryRecentCursor? = null,
) {
	init {
		require(limit in 1..MAX_RECENT_DAY_COUNT)
	}

	companion object {
		const val MAX_RECENT_DAY_COUNT = 31
	}
}

sealed interface AmbientStepsHistoryRead {
	data class Snapshot(
		val sourceEvidenceRevision: Long,
		val days: List<AmbientStepsHistoryDay>,
	) : AmbientStepsHistoryRead {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(days.isNotEmpty())
			require(days.size <= AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT)
			require(days.map(AmbientStepsHistoryDay::day).distinct().size == days.size)
		}
	}

	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsHistoryRead

	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsHistoryRead

	data object StorageUnavailable : AmbientStepsHistoryRead
}

sealed interface AmbientStepsHistoryRecentRead {
	data class Page(
		val sourceEvidenceRevision: Long,
		val days: List<AmbientStepsHistoryDay>,
		val next: AmbientStepsHistoryRecentCursor?,
	) : AmbientStepsHistoryRecentRead {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(days.size <= AmbientStepsHistoryRecentRequest.MAX_RECENT_DAY_COUNT)
			require(days.map(AmbientStepsHistoryDay::day).distinct().size == days.size)
		}
	}

	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsHistoryRecentRead

	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsHistoryRecentRead

	data object StorageUnavailable : AmbientStepsHistoryRecentRead
}

sealed interface AmbientStepsNumericRangeRead {
	data class Snapshot(
		val sourceEvidenceRevision: Long,
		val days: List<AmbientStepsNumericHistoryDay>,
	) : AmbientStepsNumericRangeRead {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(days.isNotEmpty())
			require(days.size <= AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT)
			require(days.map(AmbientStepsNumericHistoryDay::day).distinct().size == days.size)
		}
	}

	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsNumericRangeRead

	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsNumericRangeRead

	data object StorageUnavailable : AmbientStepsNumericRangeRead
}

data class AmbientStepsNumericHistoryDay(
	val day: AmbientStepsStructuralDay,
	val total: AmbientStepsHistoryValue,
)

data class AmbientStepsHistoryDay(
	val day: AmbientStepsStructuralDay,
	val opaqueDayIdentity: String,
	val structuralDayStartTimeMs: Long,
	val structuralDayEndTimeMs: Long,
	val total: AmbientStepsHistoryValue,
	val inSession: List<AmbientStepsSessionPartition>,
	val betweenSession: AmbientStepsHistoryValue,
	val factOrigins: List<AmbientStepsHistoryFactOrigin>,
	val importedDisposition: AmbientStepsImportedDisposition,
) {
	init {
		require(OPAQUE_DIGEST.matches(opaqueDayIdentity))
		require(structuralDayEndTimeMs > structuralDayStartTimeMs)
		require(inSession == inSession.sortedWith(
			compareBy(AmbientStepsSessionPartition::startTimeMs)
				.thenBy(AmbientStepsSessionPartition::endTimeMs)
				.thenBy { it.origin.ordinal },
		))
		require(factOrigins == factOrigins.distinct().sortedWith(
			compareBy(AmbientStepsHistoryFactOrigin::opaqueFactIdentity)
				.thenBy { it.kind.ordinal }
				.thenBy(AmbientStepsHistoryFactOrigin::correctionRevision),
		))
	}
}

enum class AmbientStepsImportedDisposition {
	NONE,
	PRESENT,
	DELETED,
	RETAINED,
}

sealed interface AmbientStepsHistoryValue {
	val count: Long?

	data class Exact(override val count: Long) : AmbientStepsHistoryValue {
		init {
			require(count >= 0L)
		}
	}

	data class Partial(
		override val count: Long,
		val causes: Set<AmbientStepsHistoryCause>,
	) : AmbientStepsHistoryValue {
		init {
			require(count >= 0L)
			require(causes.isNotEmpty())
		}
	}

	data class Unavailable(
		val causes: Set<AmbientStepsHistoryCause>,
	) : AmbientStepsHistoryValue {
		override val count: Long? = null

		init {
			require(causes.isNotEmpty())
		}
	}
}

data class AmbientStepsSessionPartition(
	val startTimeMs: Long,
	val endTimeMs: Long,
	val count: Long?,
	val origin: AmbientStepsSessionOrigin,
) {
	init {
		require(startTimeMs >= 0L)
		require(endTimeMs >= startTimeMs)
		require(count == null || count >= 0L)
		require(count == null || endTimeMs > startTimeMs)
	}
}

enum class AmbientStepsSessionOrigin {
	LOCAL_CAPTURE,
	PORTABLE_IMPORT,
}

data class AmbientStepsHistoryFactOrigin(
	val kind: AmbientStepsHistoryFactOriginKind,
	val opaqueFactIdentity: String,
	val correctionRevision: Long,
	val archiveIdentity: String? = null,
) {
	init {
		require(OPAQUE_DIGEST.matches(opaqueFactIdentity))
		require(correctionRevision > 0L)
		when (kind) {
			AmbientStepsHistoryFactOriginKind.NATIVE_PROVIDER -> require(archiveIdentity == null)
			AmbientStepsHistoryFactOriginKind.PORTABLE_IMPORT ->
				require(archiveIdentity?.let(OPAQUE_DIGEST::matches) == true)
		}
	}
}

enum class AmbientStepsHistoryFactOriginKind {
	NATIVE_PROVIDER,
	PORTABLE_IMPORT,
}

enum class AmbientStepsHistoryCause {
	NO_EVIDENCE,
	EXPLICIT_GAP,
	PARTIAL_COVERAGE,
	FACT_OVERLAP,
	ORIGIN_IDENTITY_CONFLICT,
	COUNT_OVERFLOW,
	STRUCTURAL_AUTHORITY_CONFLICT,
	SOURCE_AUTHORITY_UNVERIFIABLE,
	SESSION_OUTSIDE_DAY,
	SESSION_VALUE_UNAVAILABLE,
	SESSION_OVERLAP,
	SESSION_OWNERSHIP_UNVERIFIABLE,
	SESSION_NOT_COVERED,
	SESSION_COUNT_EXCEEDS_TOTAL,
	MATERIALIZING,
	DELETED,
	RETAINED,
}

enum class AmbientStepsHistoryDependency {
	NATIVE_FACTS,
	NATIVE_GAPS,
	NATIVE_CURSORS,
	NATIVE_AUTHORITY,
	IMPORTED_DAYS,
	IMPORTED_LINEAGES,
	SESSION_CANDIDATES,
	SESSION_HISTORY,
	IMPORTED_SESSION_ENTRIES,
}

enum class AmbientStepsHistoryUnavailableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	CORRUPT_RETAINED_STATE,
	CALENDAR_AUTHORITY_UNAVAILABLE,
}

private val OPAQUE_DIGEST = Regex("sha256:[0-9a-f]{64}")
