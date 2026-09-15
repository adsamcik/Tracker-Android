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
	 * Observe one finite recent-history page with exact Steps-, Wi-Fi-, Cell-, Activity-, and
	 * Pressure-only replacement plus explicit portable source origins.
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
	val wifi: WifiHistoryQuery? = null,
	val cell: CellHistoryQuery? = null,
	val readSnapshot: TrackingHistoryReadSnapshot? = null,
) {
	init {
		require(segmentId > 0L) { "Live session snapshot requires a persisted segment identity" }
		val foundSession = (session as? SessionHistoryQuery.Found)?.history
		val foundActivity = (activity as? ActivityHistoryQuery.Found)?.entry
		val foundPressure = (pressure as? PressureSessionHistoryQuery.Found)?.history
		val foundWifi = (wifi as? WifiHistoryQuery.Found)?.entry
		val foundCell = (cell as? CellHistoryQuery.Found)?.entry
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
		require(
			foundSession?.capture !is HistoryCapture.Exact ||
				foundActivity == null ||
				foundActivity.origin == ActivityHistoryOrigin.LOCAL,
		) { "A native selected session cannot acquire imported Activity evidence" }
		foundSession?.sourceProducts?.let { products ->
			require(wifi == null || wifi == products.wifi) {
				"Live Wi-Fi history must match the common selected product snapshot"
			}
			require(cell == null || cell == products.cell) {
				"Live Cell history must match the common selected product snapshot"
			}
			require(activity == products.activity) {
				"Live Activity history must match the common selected product snapshot"
			}
			require(pressure == products.pressure) {
				"Live Pressure history must match the common selected product snapshot"
			}
		}
		require(foundWifi == null || foundWifi.origin == WifiHistoryOrigin.LOCAL) {
			"A physical live session cannot acquire imported Wi-Fi ownership"
		}
		require(foundCell == null || foundCell.origin == CellHistoryOrigin.Local) {
			"A physical live session cannot acquire imported Cell ownership"
		}
		val sessionSnapshot = when (session) {
			is SessionHistoryQuery.Found -> session.history.readSnapshot
			is SessionHistoryQuery.Unavailable -> session.readSnapshot
			SessionHistoryQuery.NotFound -> null
		}
		require(readSnapshot == null || sessionSnapshot == null || readSnapshot == sessionSnapshot) {
			"Live session products must share one source-evidence snapshot"
		}
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

	/** The segment may exist, but its complete five-source snapshot could not be proven safely. */
	data class Unavailable(
		val reason: TrackingHistoryUnavailableReason,
		val source: HistorySource? = null,
		val readSnapshot: TrackingHistoryReadSnapshot? = null,
	) : SessionHistoryQuery {
		init {
			require(
				source != null ||
					reason == TrackingHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_UNAVAILABLE,
			) { "Source-specific history failures require the affected source" }
		}
	}
}

/** Source-qualified history attached to one session segment. */
data class SessionHistory(
	val segmentId: Long,
	val capture: HistoryCapture,
	val qualifiedSources: Set<HistorySource>,
	val steps: StepsHistory,
	val sourceProducts: SessionHistoryProducts? = null,
	val readSnapshot: TrackingHistoryReadSnapshot? = null,
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
		sourceProducts?.let { products ->
			require(products.segmentId == segmentId) {
				"Selected source products must own the same physical segment"
			}
			require(
				qualifiedSources == deriveQualifiedSources(capture, steps, products),
			) {
				"Qualified history sources must be derived from authenticated retained source facts"
			}
			val pressure = (products.pressure as? PressureSessionHistoryQuery.Found)?.history
			require(pressure == null || pressure.segmentId == segmentId) {
				"Pressure history does not own the selected physical segment"
			}
			require(pressure == null || pressure.capture == capture) {
				"Pressure history must share selected capture authority"
			}
			val wifi = (products.wifi as? WifiHistoryQuery.Found)?.entry
			require(wifi == null || wifi.origin == WifiHistoryOrigin.LOCAL) {
				"Selected physical Wi-Fi history must remain local"
			}
			val cell = (products.cell as? CellHistoryQuery.Found)?.entry
			require(cell == null || cell.origin == CellHistoryOrigin.Local) {
				"Selected physical Cell history must remain local"
			}
			val activity = (products.activity as? ActivityHistoryQuery.Found)?.entry
			require(
				capture !is HistoryCapture.Exact ||
					activity == null ||
					activity.origin == ActivityHistoryOrigin.LOCAL,
			) { "Selected native Activity history must remain local" }
		}
	}

	/** True only when every retained manifest revision captured Steps and no other source. */
	val capturesOnlySteps: Boolean
		get() = (capture as? HistoryCapture.Exact)?.revisions?.all { revision ->
			revision.capturedSources == setOf(HistorySource.STEPS)
		} == true

	companion object {
		/**
		 * Derives public qualification only from retained source-local product evidence.
		 *
		 * Capture intent remains independent: a factless source-only row is still visible, but it
		 * does not become a qualified numeric or observational source.
		 */
		fun deriveQualifiedSources(
			capture: HistoryCapture,
			steps: StepsHistory,
			products: SessionHistoryProducts,
		): Set<HistorySource> {
			val capturedSources = when (capture) {
				is HistoryCapture.Exact ->
					capture.revisions.flatMapTo(linkedSetOf()) { it.capturedSources }
				is HistoryCapture.ImportedSteps -> setOf(HistorySource.STEPS)
				HistoryCapture.Unverifiable -> emptySet()
			}
			return buildSet {
				if (
					HistorySource.STEPS in capturedSources &&
					steps.hasQualifiedRetainedProof
				) add(HistorySource.STEPS)
				val wifi = (products.wifi as? WifiHistoryQuery.Found)?.entry
				if (
					HistorySource.WIFI in capturedSources &&
					wifi?.observations?.isNotEmpty() == true
				) add(HistorySource.WIFI)
				val cell = (products.cell as? CellHistoryQuery.Found)?.entry
				if (
					HistorySource.CELL in capturedSources &&
					cell?.observations?.isNotEmpty() == true
				) add(HistorySource.CELL)
				val activity = (products.activity as? ActivityHistoryQuery.Found)?.entry
				if (
					HistorySource.ACTIVITY in capturedSources &&
					activity?.origin == ActivityHistoryOrigin.LOCAL &&
					activity?.fragments?.any { it is ActivityHistoryFragment.Band } == true
				) add(HistorySource.ACTIVITY)
				val pressure = (products.pressure as? PressureSessionHistoryQuery.Found)?.history
				if (
					HistorySource.PRESSURE in capturedSources &&
					pressure?.pressure?.productState != HistoryProductState.FAILED &&
					pressure?.pressure?.windows?.isNotEmpty() == true
				) add(HistorySource.PRESSURE)
			}
		}
	}
}

/** Five-source products resolved against one selected physical segment. */
data class SessionHistoryProducts(
	val segmentId: Long,
	val wifi: WifiHistoryQuery,
	val cell: CellHistoryQuery,
	val activity: ActivityHistoryQuery,
	val pressure: PressureSessionHistoryQuery,
) {
	init {
		require(segmentId > 0L)
	}
}

/** Durable source-evidence authority shared by one composed read. */
data class TrackingHistoryReadSnapshot(
	val collectedDataEpoch: Long,
	val sourceEvidenceRevision: Long,
) {
	init {
		require(collectedDataEpoch >= 0L)
		require(sourceEvidenceRevision >= 0L)
	}
}

enum class TrackingHistoryUnavailableReason {
	SOURCE_EVIDENCE_STATE_UNAVAILABLE,
	SOURCE_READ_BUDGET_EXCEEDED,
	SOURCE_INTEGRITY_FAILURE,
	PHYSICAL_MEMBERSHIP_INVALID,
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
	val source: HistorySource?
		get() = when (this) {
			is Physical -> null
			is StepsOnly, is ImportedSteps -> HistorySource.STEPS
			is ActivityOnly -> HistorySource.ACTIVITY
			is PressureOnly -> HistorySource.PRESSURE
			is WifiOnly -> HistorySource.WIFI
			is CellOnly -> HistorySource.CELL
		}

	val intent: SourceOnlyHistoryIntent?
		get() = when (this) {
			is Physical -> null
			is StepsOnly -> SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY
			is ImportedSteps -> SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP
			is ActivityOnly -> if (history.origin == ActivityHistoryOrigin.LOCAL) {
				SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY
			} else {
				SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP
			}
			is PressureOnly -> if (history.origin == PressureHistoryOrigin.Local) {
				SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY
			} else {
				SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP
			}
			is WifiOnly -> if (history.origin == WifiHistoryOrigin.LOCAL) {
				SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY
			} else {
				SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP
			}
			is CellOnly -> if (history.origin == CellHistoryOrigin.Local) {
				SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY
			} else {
				SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP
			}
		}

	val actionTarget: TrackingHistoryActionTarget
		get() = when (this) {
			is Physical -> TrackingHistoryActionTarget.PhysicalSegment(segmentId)
			is StepsOnly -> TrackingHistoryActionTarget.NonActionable(
				source = HistorySource.STEPS,
				reason = TrackingHistoryNonActionableReason.LOCAL_STEPS_SELECTOR_UNAVAILABLE,
			)
			is ImportedSteps -> TrackingHistoryActionTarget.ImportedStepsMembers(
				history.physicalMembers,
			)
			is ActivityOnly -> history.selection
				?.takeIf { history.state != ActivityHistoryProductState.FAILED }
				?.let(TrackingHistoryActionTarget::Activity)
				?: TrackingHistoryActionTarget.NonActionable(
					source = HistorySource.ACTIVITY,
					reason = TrackingHistoryNonActionableReason.ACTIVITY_SELECTOR_UNAVAILABLE,
				)
			is PressureOnly -> TrackingHistoryActionTarget.NonActionable(
				source = HistorySource.PRESSURE,
				reason = TrackingHistoryNonActionableReason.PRESSURE_SELECTOR_UNAVAILABLE,
			)
			is WifiOnly -> TrackingHistoryActionTarget.Wifi(requireNotNull(history.selection))
			is CellOnly -> TrackingHistoryActionTarget.Cell(requireNotNull(history.selection))
		}

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

	/** Exact native-only or explicit portable-origin Activity captured product. */
	data class ActivityOnly(
		val history: ActivityHistoryEntry,
	) : SourceAwareHistoryPageEntry {
		init {
			require(
				(history.origin == ActivityHistoryOrigin.LOCAL && history.capturesOnlyActivity) ||
					(history.origin == ActivityHistoryOrigin.IMPORTED &&
						!history.capturesOnlyActivity),
			) {
				"Activity rows require exact native-only intent or explicit portable membership"
			}
		}
	}

	/** Retained imported Steps keep their exact product-origin identity and actions. */
	data class ImportedSteps(
		val history: ImportedStepsHistoryEntry,
	) : SourceAwareHistoryPageEntry

	/** Exact native-only or explicit portable-origin Pressure captured product. */
	data class PressureOnly(
		val history: PressureOnlyHistoryEntry,
	) : SourceAwareHistoryPageEntry

	/** Exact native-only or explicit portable-origin Wi-Fi captured product. */
	data class WifiOnly(
		val history: WifiHistoryEntry,
	) : SourceAwareHistoryPageEntry {
		init {
			require(history.selection != null) {
				"Wi-Fi history rows require the producer-issued opaque selector"
			}
			require(
				(history.origin == WifiHistoryOrigin.LOCAL && history.capturesOnlyWifi) ||
					(history.origin == WifiHistoryOrigin.IMPORTED && !history.capturesOnlyWifi),
			) {
				"Wi-Fi rows require exact native-only intent or explicit portable membership"
			}
		}
	}

	/** Exact native-only or explicit portable-origin Cell captured product. */
	data class CellOnly(
		val history: CellHistoryEntry,
	) : SourceAwareHistoryPageEntry {
		init {
			require(history.selection != null) {
				"Cell history rows require the producer-issued opaque selector"
			}
		}
	}
}

enum class SourceOnlyHistoryIntent {
	EXACT_NATIVE_ONLY,
	PORTABLE_SOURCE_MEMBERSHIP,
}

/**
 * Existing producer-issued action authority carried without decoding opaque identities.
 *
 * The page-level [TrackingHistoryReadSnapshot] supplies the epoch/revision authority needed by
 * later optimistic actions. A non-actionable target is explicit rather than a fabricated scope.
 */
sealed interface TrackingHistoryActionTarget {
	data class PhysicalSegment(val segmentId: Long) : TrackingHistoryActionTarget {
		init {
			require(segmentId > 0L)
		}
	}

	data class ImportedStepsMembers(
		val members: List<ImportedStepsHistoryMember>,
	) : TrackingHistoryActionTarget {
		init {
			require(members.isNotEmpty())
		}
	}

	data class Wifi(val selection: WifiHistorySelection) : TrackingHistoryActionTarget

	data class Cell(val selection: CellHistoryEntrySelection) : TrackingHistoryActionTarget

	data class Activity(
		val selection: ActivityHistorySelection,
	) : TrackingHistoryActionTarget

	data class NonActionable(
		val source: HistorySource,
		val reason: TrackingHistoryNonActionableReason,
	) : TrackingHistoryActionTarget
}

enum class TrackingHistoryNonActionableReason {
	LOCAL_STEPS_SELECTOR_UNAVAILABLE,
	ACTIVITY_SELECTOR_UNAVAILABLE,
	PRESSURE_SELECTOR_UNAVAILABLE,
}

/** One bounded source-aware result; dependency failure never falls back to raw rows. */
sealed interface SourceAwareHistoryPageQuery {
	/** The complete requested page within the declared candidate and membership budgets. */
	data class Content(
		val entries: List<SourceAwareHistoryPageEntry>,
		val readSnapshot: TrackingHistoryReadSnapshot? = null,
	) : SourceAwareHistoryPageQuery

	/** Exact source-only replacement could not be decided truthfully within one bounded read. */
	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
		val source: HistorySource? = null,
	) : SourceAwareHistoryPageQuery {
		init {
			if (
				reason == SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED ||
				reason == SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE ||
				reason ==
				SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE
			) {
				require(source != null) { "Source read failures require the affected source" }
			}
		}
	}
}

enum class SourceAwareHistoryPageUnavailableReason {
	CANDIDATE_SCAN_LIMIT,
	LOGICAL_MEMBERSHIP_LIMIT,
	SOURCE_IDENTITY_COLLISION,
	SOURCE_EVIDENCE_STATE_UNAVAILABLE,
	SOURCE_READ_BUDGET_EXCEEDED,
	SOURCE_INTEGRITY_FAILURE,
	SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
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

	/**
	 * True only when a retained numeric value is backed by covered non-failed evidence.
	 *
	 * Historical qualification deliberately ignores current capability availability. Legacy
	 * replay with unknown coverage and recorded-but-value-free materialization are not proof.
	 */
	val hasQualifiedRetainedProof: Boolean
		get() = count != null &&
			evidence in setOf(HistoryEvidence.ACTIVE, HistoryEvidence.RECORDED) &&
			coverage in setOf(StepsHistoryCoverage.COMPLETE, StepsHistoryCoverage.PARTIAL) &&
			productState != HistoryProductState.FAILED &&
			causes.none(StepsHistoryCause::invalidatesQualifiedRetainedProof)
}

private val StepsHistoryCause.invalidatesQualifiedRetainedProof: Boolean
	get() = when (this) {
		StepsHistoryCause.SOURCE_NOT_CAPTURED,
		StepsHistoryCause.BASELINE_ONLY,
		StepsHistoryCause.NO_OBSERVATION,
		StepsHistoryCause.HISTORY_MEMBERSHIP_UNAVAILABLE,
		StepsHistoryCause.HISTORY_INTEGRITY_FAILED,
		StepsHistoryCause.WRITER_PROVENANCE_INVALID,
		StepsHistoryCause.LEGACY_UNVERIFIED,
		StepsHistoryCause.MATERIALIZATION_UNAVAILABLE,
		StepsHistoryCause.FACTS_MISSING,
		StepsHistoryCause.DELETED,
		StepsHistoryCause.EVIDENCE_STATE_UNAVAILABLE,
		StepsHistoryCause.PRIVACY_EPOCH_MISMATCH,
		StepsHistoryCause.VALUE_OVERFLOW,
		-> true

		StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		StepsHistoryCause.CAPTURE_PARTIAL,
		StepsHistoryCause.SESSION_STILL_ACTIVE,
		StepsHistoryCause.MATERIALIZATION_BEHIND,
		StepsHistoryCause.ACQUISITION_INCOMPLETE,
		StepsHistoryCause.PROVIDER_GAP,
		StepsHistoryCause.RETENTION_LIMIT,
		-> false
	}
