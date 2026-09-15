package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.flow.Flow

/**
 * Read-only history facade for source-qualified tracking products.
 *
 * The facade reports durable product state. Observing history never starts a provider, changes a
 * writer, repairs a projection, or writes a derived summary.
 */
interface TrackingHistoryRepository {
	/**
	 * Observe the durable history attached to one local session-segment row.
	 *
	 * This narrow query is intended for one selected session. It is not a portable session identity
	 * or a list-row API; logical-session and day composition remain separate contracts.
	 */
	fun observeSession(segmentId: Long): Flow<SessionHistoryQuery>

	/**
	 * Observe the common session product plus source-local Activity and Pressure truth from one
	 * durable Room snapshot. Live presentation must use this seam when exact source-only intent
	 * changes which controls are visible.
	 */
	fun observeLiveSession(segmentId: Long): Flow<LiveSessionHistorySnapshot>

	/**
	 * Observe recent logical entries whose exact capture set was Steps and no other source.
	 *
	 * The list is composed in one bounded read. It exposes neither a numeric cross-run total nor a
	 * selectable physical identity. Existing Trip-row suppression requires a separate, consumer-owned
	 * bounded composition against that consumer's actual physical candidate window.
	 */
	fun observeRecentStepsOnlyEntries(limit: Int): Flow<List<StepsOnlyHistoryEntry>>

	/**
	 * Observe one finite recent-history page composed against the caller's physical candidates.
	 *
	 * [candidateSegmentIds] is the complete bounded physical candidate window owned by the caller.
	 * Existing candidates that remain physical are echoed by id; exact Steps-only logical groups are
	 * represented only by an opaque [StepsAwareHistoryPageEntry.StepsOnly] row. Missing candidates are
	 * omitted. The final [limit] is applied after both kinds of row are merged by durable recency.
	 */
	fun observeRecentStepsAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<List<StepsAwareHistoryPageEntry>>

	/**
	 * Observe one finite recent-history page with exact Steps-, Activity-, and Pressure-only
	 * replacement.
	 *
	 * A physical row is suppressed only after its complete logical manifest union authenticates one
	 * exact source-only intent. Materializing and unavailable source-only products remain visible;
	 * legacy and mixed entries retain their physical presentation. All sources are composed in one
	 * bounded snapshot and sorted once before [limit] is applied.
	 */
	fun observeRecentSourceAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<SourceAwareHistoryPageQuery>

	/** Observe source-qualified Pressure history for one exact physical session segment. */
	fun observePressureSession(segmentId: Long): Flow<PressureSessionHistoryQuery>

	/**
	 * Observe recent exact Pressure-only logical entries through one bounded source-local read.
	 *
	 * Local and authenticated portable origins remain explicit. Returned keys are deliberately opaque
	 * and do not grant physical delete, detail, or export authority. Each row retains only direct
	 * Pressure evidence and an explicit product state; missing evidence never becomes numeric zero.
	 */
	fun observeRecentPressureOnlyEntries(limit: Int): Flow<List<PressureOnlyHistoryEntry>>
}

/** One exact-segment Room snapshot used to choose the live session presentation. */
data class LiveSessionHistorySnapshot(
	val segmentId: Long,
	val session: SessionHistoryQuery,
	val activity: ActivityHistoryQuery,
	val pressure: PressureSessionHistoryQuery,
) {
	init {
		require(segmentId > 0L) { "Live session snapshot requires a persisted segment identity" }
		val foundSession = (session as? SessionHistoryQuery.Found)?.history
		val foundActivity = (activity as? ActivityHistoryQuery.Found)?.entry
		val foundPressure = (pressure as? PressureSessionHistoryQuery.Found)?.history
		require(listOf(foundSession, foundActivity, foundPressure).all { it == null } ||
			listOf(foundSession, foundActivity, foundPressure).all { it != null }
		) {
			"Live session products must resolve the segment in the same snapshot"
		}
		require(foundSession == null || foundSession.segmentId == segmentId) {
			"Session history does not belong to the live snapshot segment"
		}
		require(foundPressure == null || foundPressure.segmentId == segmentId) {
			"Pressure history does not belong to the live snapshot segment"
		}
		require(foundSession == null || foundPressure == null ||
			foundSession.capture == foundPressure.capture
		) { "Live session products must share one capture-authority snapshot" }
		require(foundActivity == null || foundSession == null ||
			foundActivity.capturesOnlyActivity == foundSession.capturesOnly(HistorySource.ACTIVITY)
		) { "Activity-only truth contradicts the common capture snapshot" }
		require(foundActivity?.capturesOnlyActivity != true ||
			foundPressure?.capture?.capturesOnly(HistorySource.PRESSURE) != true
		) { "One logical snapshot cannot be both Activity-only and Pressure-only" }
	}
}

private fun SessionHistory.capturesOnly(source: HistorySource): Boolean = capture.capturesOnly(source)

private fun HistoryCapture.capturesOnly(source: HistorySource): Boolean =
	(this as? HistoryCapture.Exact)?.revisions?.all { revision ->
		revision.capturedSources == setOf(source)
	} == true

/** Result of resolving one local session-segment row identity. */
sealed interface SessionHistoryQuery {
	/** No session segment exists with the requested local row identity. */
	data object NotFound : SessionHistoryQuery

	/** A coherent durable snapshot for the requested session segment. */
	data class Found(
		val history: SessionHistory,
	) : SessionHistoryQuery
}

/** Source-qualified history attached to one session segment. */
data class SessionHistory(
	val segmentId: Long,
	val capture: HistoryCapture,
	val qualifiedSources: Set<HistorySource>,
	val steps: StepsHistory,
) {
	init {
		require(segmentId > 0L) { "Session history requires a persisted segment identity" }
		val capturedSources = when (capture) {
			is HistoryCapture.Exact -> capture.revisions.flatMapTo(linkedSetOf()) { it.capturedSources }
			is HistoryCapture.ImportedSteps -> setOf(HistorySource.STEPS)
			HistoryCapture.Unverifiable -> emptySet()
		}
		require(qualifiedSources.all(capturedSources::contains)) {
			"Qualified history sources require exact historical capture authority"
		}
	}

	/** True only when every retained manifest revision captured Steps and no other source. */
	val capturesOnlySteps: Boolean
		get() = (capture as? HistoryCapture.Exact)?.revisions?.all { revision ->
			revision.capturedSources == setOf(HistorySource.STEPS)
		} == true
}

/** Stable source names used by historical capture and source-qualification evidence. */
enum class HistorySource {
	LOCATION,
	WIFI,
	CELL,
	ACTIVITY,
	STEPS,
	PRESSURE,
}

/** Exact revisioned capture intent, or a typed boundary that prevents source inference. */
sealed interface HistoryCapture {
	/** Checksum-verified immutable capture/control revisions for one physical service run. */
	data class Exact(
		val revisions: List<HistoryCaptureRevision>,
	) : HistoryCapture {
		init {
			require(revisions.isNotEmpty()) { "Exact capture history requires a revision" }
			require(revisions.zipWithNext().all { (left, right) ->
				left.revision < right.revision
			}) { "Capture revisions must be strictly increasing" }
			require(revisions.any { it.capturedSources.isNotEmpty() }) {
				"Exact capture history requires a captured source"
			}
		}
	}

	/** Migrated or incomplete evidence cannot safely name the historical capture set. */
	data object Unverifiable : HistoryCapture

	/**
	 * Verified portable Steps membership, not the original full capture/control selection.
	 * Other captured sources and automation controls are deliberately absent from portable v1.
	 */
	data class ImportedSteps(val revisions: List<ImportedStepsCaptureRevision>) : HistoryCapture {
		init {
			require(revisions.isNotEmpty())
			require(revisions.zipWithNext().all { (left, right) -> left.revision < right.revision })
		}
	}
}

/** A retained Steps capture revision; makes no claim about omitted capture or control sources. */
data class ImportedStepsCaptureRevision(val revision: Long, val effectiveAt: EpochMs) {
	init { require(revision > 0L) }
}

/** One checksum-verified immutable capture/control set within a physical service run. */
data class HistoryCaptureRevision(
	val revision: Long,
	val effectiveAt: EpochMs,
	val capturedSources: Set<HistorySource>,
	val controlSources: Set<HistorySource>,
) {
	init {
		require(revision > 0L) { "Capture revision must be positive" }
	}
}

/** Opaque equality key for a non-selectable logical history row. */
@JvmInline
value class TrackingHistoryEntryKey(private val opaqueValue: String) {
	init {
		require(opaqueValue.isNotBlank()) { "Tracking history key cannot be blank" }
	}

	override fun toString(): String = "TrackingHistoryEntryKey"
}

/** Explicit list state; this seam deliberately exposes no cross-run Steps number. */
enum class StepsOnlyHistoryListState {
	AVAILABLE,
	MATERIALIZING,
	PARTIAL,
}

/** One exact Steps-only logical entry suitable for a non-clickable recent-history row. */
data class StepsOnlyHistoryEntry(
	val key: TrackingHistoryEntryKey,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val state: StepsOnlyHistoryListState,
) {
	init {
		require(endTime >= startTime) { "History entry cannot end before it starts" }
	}
}

/**
 * One row in a finite Steps-aware history page.
 *
 * A physical row only echoes an identity supplied by the caller. A Steps-only row retains no
 * selectable physical identity and therefore grants no detail, map, export, or deletion authority.
 */
sealed interface StepsAwareHistoryPageEntry {
	/** A caller-supplied physical candidate that remains eligible for existing Trip presentation. */
	data class Physical(
		val segmentId: Long,
	) : StepsAwareHistoryPageEntry {
		init {
			require(segmentId > 0L) { "Physical history candidate id must be positive" }
		}
	}

	/** An opaque, non-selectable replacement for an exact qualified Steps-only logical entry. */
	data class StepsOnly(
		val history: StepsOnlyHistoryEntry,
	) : StepsAwareHistoryPageEntry

	/** Only Steps is retained here; the original capture/control selection is not known. */
	data class ImportedSteps(val history: ImportedStepsHistoryEntry) : StepsAwareHistoryPageEntry
}

/** One authenticated imported logical entry and its exactly bound, individually selectable runs. */
data class ImportedStepsHistoryEntry(
	val key: TrackingHistoryEntryKey,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val physicalMembers: List<ImportedStepsHistoryMember>,
) {
	init {
		require(physicalMembers.isNotEmpty())
		require(physicalMembers.size <= MAX_PHYSICAL_MEMBERS) { "Imported Steps entries support at most 64 physical runs" }
		require(physicalMembers.map { it.segmentId }.distinct().size == physicalMembers.size)
		require(startTime == physicalMembers.minOf { it.startTime })
		require(endTime == physicalMembers.maxOf { it.endTime })
	}

	private companion object {
		const val MAX_PHYSICAL_MEMBERS = 64
	}
}

/** Physical detail authority is local; its Long id is never exported as a portable identity. */
data class ImportedStepsHistoryMember(
	val segmentId: Long,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val steps: StepsHistory,
) {
	init {
		require(segmentId > 0L)
		require(endTime >= startTime)
	}
}

/**
 * One row in the bounded source-aware history page.
 *
 * Only physical rows retain navigation authority. Source-only rows expose opaque logical identity
 * and never imply Location, distance, route, elevation, or a fabricated numeric zero.
 */
sealed interface SourceAwareHistoryPageEntry {
	/** A caller-supplied candidate that remains eligible for existing Trip presentation. */
	data class Physical(
		val segmentId: Long,
	) : SourceAwareHistoryPageEntry {
		init {
			require(segmentId > 0L) { "Physical history candidate id must be positive" }
		}
	}

	/** An opaque, non-selectable exact Steps-only logical row. */
	data class StepsOnly(
		val history: StepsOnlyHistoryEntry,
	) : SourceAwareHistoryPageEntry

	/** An opaque, non-selectable exact Activity-only logical row. */
	data class ActivityOnly(
		val history: ActivityHistoryEntry,
	) : SourceAwareHistoryPageEntry {
		init {
			require(history.capturesOnlyActivity) {
				"Activity-only page rows require exact all-revision Activity-only intent"
			}
		}
	}

	/** Retained imported Steps keep their exact product-origin identity and actions. */
	data class ImportedSteps(
		val history: ImportedStepsHistoryEntry,
	) : SourceAwareHistoryPageEntry

	/** An opaque, non-selectable exact Pressure-only logical row. */
	data class PressureOnly(
		val history: PressureOnlyHistoryEntry,
	) : SourceAwareHistoryPageEntry
}

/** One bounded source-aware result; dependency failure never falls back to raw rows. */
sealed interface SourceAwareHistoryPageQuery {
	/** The complete requested page within the declared candidate and membership budgets. */
	data class Content(
		val entries: List<SourceAwareHistoryPageEntry>,
	) : SourceAwareHistoryPageQuery

	/** Exact source-only replacement could not be decided truthfully within one bounded read. */
	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
	) : SourceAwareHistoryPageQuery
}

enum class SourceAwareHistoryPageUnavailableReason {
	CANDIDATE_SCAN_LIMIT,
	LOGICAL_MEMBERSHIP_LIMIT,
	SOURCE_IDENTITY_COLLISION,
}

/** Policy/capability availability, independent of acquisition and product progress. */
enum class HistoryAvailability {
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_LIMITED,
	AVAILABLE,
	/** Imported product is retained; this says nothing about local provider capability or consent. */
	RETAINED_IMPORTED,
	/** Retained evidence cannot safely distinguish the policy/capability state. */
	UNAVAILABLE,
}

/** Acquisition evidence, independent of product materialization. */
enum class HistoryEvidence {
	NONE,
	STARTING,
	ACTIVE,
	RECORDED,
}

/** Product readiness, independent of availability and acquisition evidence. */
enum class HistoryProductState {
	MATERIALIZING,
	PARTIAL,
	READY,
	DEGRADED,
	FAILED,
}

/** How much of the requested session interval the Steps value represents. */
enum class StepsHistoryCoverage {
	NONE,
	COMPLETE,
	PARTIAL,
	UNKNOWN,
}

/** Stable, product-facing causes that explain incomplete or degraded Steps history. */
enum class StepsHistoryCause {
	SOURCE_NOT_CAPTURED,
	AVAILABILITY_UNAVAILABLE,
	CAPTURE_PARTIAL,
	SESSION_STILL_ACTIVE,
	BASELINE_ONLY,
	NO_OBSERVATION,
	HISTORY_MEMBERSHIP_UNAVAILABLE,
	HISTORY_INTEGRITY_FAILED,
	WRITER_PROVENANCE_INVALID,
	LEGACY_UNVERIFIED,
	MATERIALIZATION_BEHIND,
	MATERIALIZATION_UNAVAILABLE,
	ACQUISITION_INCOMPLETE,
	PROVIDER_GAP,
	FACTS_MISSING,
	DELETED,
	RETENTION_LIMIT,
	EVIDENCE_STATE_UNAVAILABLE,
	PRIVACY_EPOCH_MISMATCH,
	VALUE_OVERFLOW,
}

/**
 * Steps value and the independent evidence required to interpret it honestly.
 *
 * A partial count is a lower bound, never a complete total. Numeric zero exists only after a
 * covered interval; baseline-only and missing evidence remain null.
 */
data class StepsHistory(
	val count: Long?,
	val availability: HistoryAvailability,
	val evidence: HistoryEvidence,
	val productState: HistoryProductState,
	val coverage: StepsHistoryCoverage,
	val causes: Set<StepsHistoryCause> = emptySet(),
) {
	init {
		require(count == null || count >= 0L) { "Steps count cannot be negative" }
		when {
			count == null -> Unit
			count == 0L -> {
				require(evidence == HistoryEvidence.ACTIVE) {
					"Verified zero requires covered evidence without recording onset"
				}
				require(coverage == StepsHistoryCoverage.COMPLETE || coverage == StepsHistoryCoverage.PARTIAL) {
					"Verified zero requires a covered interval"
				}
			}
			else -> require(evidence == HistoryEvidence.RECORDED) {
				"A positive Steps count requires recorded evidence"
			}
		}
		if (evidence == HistoryEvidence.NONE || evidence == HistoryEvidence.STARTING) {
			require(count == null) { "Missing or starting evidence cannot expose a Steps value" }
		}
		if (productState != HistoryProductState.READY) {
			require(causes.isNotEmpty()) { "Incomplete Steps product state requires a named cause" }
		}
	}

	/** True only when [count] is safe for complete totals, awards, and progress decisions. */
	val hasCompleteValue: Boolean
		get() = count != null &&
			productState == HistoryProductState.READY &&
			coverage == StepsHistoryCoverage.COMPLETE

	/** A correction-safe retained count that covers only part of the requested session. */
	val isLowerBound: Boolean
		get() = count != null && coverage == StepsHistoryCoverage.PARTIAL
}
