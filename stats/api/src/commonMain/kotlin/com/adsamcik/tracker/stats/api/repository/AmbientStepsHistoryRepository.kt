package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/**
 * Read-only Ambient Steps history over exact stored structural-day authority.
 *
 * Historical qualification is independent of current platform capability. Implementations never
 * probe permissions, start a provider, acquire demand, repair storage, or use the current zone.
 */
interface AmbientStepsHistoryRepository {
	/**
	 * Reads one all-day Steps day with independent total, session partition and imported provenance
	 * states.
	 */
	suspend fun readDay(request: AmbientStepsHistoryDayRequest): AmbientStepsHistoryRead

	/** One bounded source snapshot for every requested structural day; no per-day storage fan-out. */
	suspend fun readRange(request: AmbientStepsHistoryRangeRequest): AmbientStepsHistoryRead

	/** Newest source-qualified structural days using an exact keyset continuation. */
	suspend fun readRecent(request: AmbientStepsHistoryRecentRequest): AmbientStepsHistoryRecentRead

	/**
	 * Observes the requested bounded all-day Steps range while subscribed; it does not activate
	 * recording.
	 */
	fun observeRange(request: AmbientStepsHistoryRangeRequest): Flow<AmbientStepsHistoryRead>
}

/** Narrow source total boundary for numeric consumers that already own the Room transaction. */
interface AmbientStepsNumericRangeReader {
	/**
	 * Display-only discovery from the same bounded stored keys. Conflicting days are identified
	 * individually; no conflicted day is rebound to the current zone or added into another day.
	 */
	suspend fun discoverDisplayStructuralDaysInCurrentTransaction(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		sessionCalendarDays: List<AmbientStepsStructuralDay>,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsDisplayStructuralDayRead = when (val strict =
		discoverNumericStructuralDaysInCurrentTransaction(
			firstEpochDay, lastEpochDayInclusive, sessionCalendarDays, expectedSourceEvidenceRevision,
		)
	) {
		is AmbientStepsNumericStructuralDayRead.Exact -> AmbientStepsDisplayStructuralDayRead.Snapshot(
			strict.sourceEvidenceRevision, strict.days, emptySet(),
		)
		AmbientStepsNumericStructuralDayRead.NoAmbientAuthority ->
			AmbientStepsDisplayStructuralDayRead.Snapshot(expectedSourceEvidenceRevision, emptyList(), emptySet())
		is AmbientStepsNumericStructuralDayRead.Unavailable ->
			AmbientStepsDisplayStructuralDayRead.Unavailable(strict.reason)
		AmbientStepsNumericStructuralDayRead.StorageUnavailable ->
			AmbientStepsDisplayStructuralDayRead.StorageUnavailable
	}

	/**
	 * Discovers exact stored structural-day authority for the requested epoch range. Session
	 * calendar candidates are constraints, not defaults; conflicting authorities fail closed.
	 */
	suspend fun discoverNumericStructuralDaysInCurrentTransaction(
		firstEpochDay: Long,
		lastEpochDayInclusive: Long,
		sessionCalendarDays: List<AmbientStepsStructuralDay>,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericStructuralDayRead

	/**
	 * Reads strict per-day values inside the caller's Room transaction only under the expected
	 * source-evidence revision.
	 */
	suspend fun readNumericRangeInCurrentTransaction(
		request: AmbientStepsHistoryRangeRequest,
		expectedSourceEvidenceRevision: Long,
	): AmbientStepsNumericRangeRead
}

/**
 * Stored calendar authority for display, retaining healthy days alongside a set of conflicting
 * local dates.
 */
sealed interface AmbientStepsDisplayStructuralDayRead {
	/**
	 * Source-revision snapshot of usable saved days plus dates with conflicting calendar authority.
	 */
	data class Snapshot(
		val sourceEvidenceRevision: Long,
		val days: List<AmbientStepsStructuralDay>,
		val calendarConflictEpochDays: Set<Long>,
	) : AmbientStepsDisplayStructuralDayRead {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(days.size + calendarConflictEpochDays.size <= AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT)
			require(days.map(AmbientStepsStructuralDay::epochDay).distinct().size == days.size)
			require(days.none { it.epochDay in calendarConflictEpochDays })
			require(days == days.sortedBy(AmbientStepsStructuralDay::epochDay))
		}
	}

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsDisplayStructuralDayRead
	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientStepsDisplayStructuralDayRead
}

/**
 * Exact stored calendar authority for strict totals, or an explicit absence/failure of all-day
 * authority.
 */
sealed interface AmbientStepsNumericStructuralDayRead {
	/**
	 * Saved structural days resolved under one source revision for strict numeric composition.
	 */
	data class Exact(
		val sourceEvidenceRevision: Long,
		val days: List<AmbientStepsStructuralDay>,
	) : AmbientStepsNumericStructuralDayRead {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(days.isNotEmpty())
			require(days.size <= AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT)
			require(days.map(AmbientStepsStructuralDay::epochDay).distinct().size == days.size)
			require(days == days.sortedWith(
				compareBy(AmbientStepsStructuralDay::epochDay)
					.thenBy(AmbientStepsStructuralDay::storedZoneId),
			))
		}
	}

	/**
	 * No all-day calendar authority exists; a caller may still evaluate independently saved session
	 * evidence.
	 */
	data object NoAmbientAuthority : AmbientStepsNumericStructuralDayRead

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsNumericStructuralDayRead

	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientStepsNumericStructuralDayRead
}

/**
 * A nonnegative local calendar epoch day paired with its saved nonblank zone identifier.
 */
data class AmbientStepsStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= MAX_ZONE_ID_LENGTH)
	}

	/**
	 * Maximum supported length of a saved Steps zone identifier.
	 */
	companion object {
		const val MAX_ZONE_ID_LENGTH = 128
	}
}

/**
 * Selects one saved structural Steps day without requiring a session.
 */
data class AmbientStepsHistoryDayRequest(
	val day: AmbientStepsStructuralDay,
)

/**
 * Bounded unique ordered structural days for one all-day Steps read generation.
 */
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

	/**
	 * Maximum number of structural Steps days in one bounded range read.
	 */
	companion object {
		const val MAX_DAY_COUNT = 370
	}
}

/**
 * Recent-day cursor bound to the data epoch and source revision, with measured recency and stable
 * day identity.
 */
data class AmbientStepsHistoryRecentCursor(
	val sourceEvidenceRevision: Long,
	val collectedDataEpoch: Long,
	val latestEvidenceTimeMs: Long,
	val epochDay: Long,
	val storedZoneId: String,
	val publicDayIdentity: String,
) {
	init {
		require(sourceEvidenceRevision >= 0L)
		require(collectedDataEpoch >= 0L)
		require(latestEvidenceTimeMs >= 0L)
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= AmbientStepsStructuralDay.MAX_ZONE_ID_LENGTH)
		require(OPAQUE_DIGEST.matches(publicDayIdentity))
	}
}

/**
 * Bounded recent-day page size and an optional cursor from an earlier source snapshot.
 */
data class AmbientStepsHistoryRecentRequest(
	val limit: Int,
	val before: AmbientStepsHistoryRecentCursor? = null,
) {
	init {
		require(limit in 1..MAX_RECENT_DAY_COUNT)
	}

	/**
	 * Maximum number of recent Steps days returned by one page.
	 */
	companion object {
		const val MAX_RECENT_DAY_COUNT = 31
	}
}

/**
 * All-day Steps day results from one source revision, or explicit read/qualification limits.
 */
sealed interface AmbientStepsHistoryRead {
	/**
	 * Saved day results bound to one source-evidence revision; each value retains its own
	 * completeness.
	 */
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

	/**
	 * A required saved-data dependency exceeds the bounded read budget; a truncated prefix is not a
	 * complete result.
	 */
	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsHistoryRead

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsHistoryRead

	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientStepsHistoryRead
}

/**
 * Recent all-day Steps page with a revision-bound continuation, or an explicit
 * unavailable/read-limit outcome.
 */
sealed interface AmbientStepsHistoryRecentRead {
	/**
	 * Bounded saved day results and continuation under one source-evidence revision.
	 */
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

	/**
	 * A required saved-data dependency exceeds the bounded read budget; a truncated prefix is not a
	 * complete result.
	 */
	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsHistoryRecentRead

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsHistoryRecentRead

	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientStepsHistoryRecentRead
}

/**
 * Per-day all-day numeric values read under one expected source-evidence revision.
 */
sealed interface AmbientStepsNumericRangeRead {
	/**
	 * Saved day results bound to one source-evidence revision; each value retains its own
	 * completeness.
	 */
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

	/**
	 * A required saved-data dependency exceeds the bounded read budget; a truncated prefix is not a
	 * complete result.
	 */
	data class DependencyOverflow(
		val dependency: AmbientStepsHistoryDependency,
	) : AmbientStepsNumericRangeRead

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: AmbientStepsHistoryUnavailableReason,
	) : AmbientStepsNumericRangeRead

	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : AmbientStepsNumericRangeRead
}

/**
 * A saved structural day paired with its exact, partial or unavailable all-day count result.
 */
data class AmbientStepsNumericHistoryDay(
	val day: AmbientStepsStructuralDay,
	val total: AmbientStepsHistoryValue,
)

/**
 * All-day total and session partitions, preserving real UTC millisecond day bounds and
 * native/imported fact provenance.
 */
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

/**
 * Whether imported Steps are absent, present, explicitly deleted or removed by retention for this
 * day.
 */
enum class AmbientStepsImportedDisposition {
	NONE,
	PRESENT,
	DELETED,
	RETAINED,
}

/**
 * A complete count, a saved subtotal with named gaps, or an unavailable count; absence is never
 * numeric zero.
 */
sealed interface AmbientStepsHistoryValue {
	val count: Long?

	/**
	 * Complete nonnegative count supported by the represented saved Steps coverage.
	 */
	data class Exact(override val count: Long) : AmbientStepsHistoryValue {
		init {
			require(count >= 0L)
		}
	}

	/**
	 * Nonnegative recorded subtotal with nonempty named coverage/ownership causes; this is not a
	 * complete daily count.
	 */
	data class Partial(
		override val count: Long,
		val causes: Set<AmbientStepsHistoryCause>,
	) : AmbientStepsHistoryValue {
		init {
			require(count >= 0L)
			require(causes.isNotEmpty())
		}
	}

	/**
	 * No qualified numeric value is available; nonempty causes preserve the actual reason.
	 */
	data class Unavailable(
		val causes: Set<AmbientStepsHistoryCause>,
	) : AmbientStepsHistoryValue {
		override val count: Long? = null

		init {
			require(causes.isNotEmpty())
		}
	}
}

/**
 * Real session overlap bounds in UTC milliseconds and an optional exact count; null means the
 * partition cannot be counted.
 */
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

/**
 * Whether a Steps partition belongs to local capture or portable imported data.
 */
enum class AmbientStepsSessionOrigin {
	LOCAL_CAPTURE,
	PORTABLE_IMPORT,
}

/**
 * Original opaque fact identity and correction revision, with archive membership only for imported
 * facts.
 */
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

/**
 * Distinguishes native provider facts from portable imported Steps facts.
 */
enum class AmbientStepsHistoryFactOriginKind {
	NATIVE_PROVIDER,
	PORTABLE_IMPORT,
}

/**
 * Named missing-coverage, ownership, overlap, calendar, deletion and retention causes for
 * incomplete Steps data.
 */
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

/**
 * Which bounded native, imported or session dependency prevented a complete all-day Steps read.
 */
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

/**
 * Missing source state, corrupt retained data or unavailable saved calendar authority preventing
 * the Steps read.
 */
enum class AmbientStepsHistoryUnavailableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	CORRUPT_RETAINED_STATE,
	CALENDAR_AUTHORITY_UNAVAILABLE,
}

private val OPAQUE_DIGEST = Regex("sha256:[0-9a-f]{64}")
