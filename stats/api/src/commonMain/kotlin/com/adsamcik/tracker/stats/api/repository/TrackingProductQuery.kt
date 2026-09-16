package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * Read-only all-source product query.
 *
 * Implementations compose retained source evidence only. Reading this contract cannot start or
 * retain providers, create source demand, repair projections, or authorize a writer.
 */
interface TrackingProductQuery {
	suspend fun read(request: TrackingProductQueryRequest): TrackingProductQueryResult
}

/** Exact public scope for one bounded product read. */
sealed interface TrackingProductQueryScope {
	/** Absolute wall-time scope; stored-zone membership remains part of each returned snapshot. */
	data class WallRange(
		val fromInclusive: EpochMs,
		val toExclusive: EpochMs,
	) : TrackingProductQueryScope {
		init {
			require(toExclusive > fromInclusive)
			require(toExclusive.raw - fromInclusive.raw <= MAX_TRACKING_PRODUCT_RANGE_MILLIS)
		}
	}

	/**
	 * Explicit structural days under the zones persisted with their source evidence.
	 *
	 * Callers must not replace these zones with the device's current zone.
	 */
	data class StructuralDays(
		val days: List<TrackingProductStructuralDay>,
	) : TrackingProductQueryScope {
		init {
			require(days.isNotEmpty())
			require(days.size <= MAX_TRACKING_PRODUCT_DAY_COUNT)
			require(days.distinct().size == days.size)
			require(days == days.sortedWith(TRACKING_PRODUCT_DAY_ORDER))
			val span = days.last().epochDay.toULong() - days.first().epochDay.toULong()
			require(span < MAX_TRACKING_PRODUCT_DAY_COUNT.toULong())
		}
	}
}

/** One immutable civil-day identity from retained source authority. */
data class TrackingProductStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(epochDay >= 0L)
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= MAX_TRACKING_PRODUCT_ZONE_ID_LENGTH)
		require('\n' !in storedZoneId && '\r' !in storedZoneId)
	}
}

data class TrackingProductQueryRequest(
	val scope: TrackingProductQueryScope,
	val limit: Int,
	val continuation: TrackingProductContinuation? = null,
) {
	init {
		require(limit in 1..MAX_TRACKING_PRODUCT_PAGE_SIZE)
		require(continuation == null || continuation.scope == scope) {
			"A continuation can resume only its producer-issued scope"
		}
	}
}

/** Durable evidence boundary shared by every source in one composed read. */
data class TrackingProductReadSnapshot(
	val collectedDataEpoch: Long,
	val sourceEvidenceRevision: Long,
) {
	init {
		require(collectedDataEpoch >= 0L)
		require(sourceEvidenceRevision >= 0L)
	}
}

/**
 * Opaque keyset continuation bound to the scope and evidence snapshot that issued it.
 *
 * Only the producing implementation interprets its private subtype.
 */
interface TrackingProductContinuation {
	val scope: TrackingProductQueryScope
	val readSnapshot: TrackingProductReadSnapshot
}

sealed interface TrackingProductQueryResult {
	/**
	 * One immutable page. Every entry and continuation belongs to [readSnapshot].
	 *
	 * Empty final pages are permitted; a non-null continuation still identifies the same snapshot.
	 */
	data class Page(
		val scope: TrackingProductQueryScope,
		val readSnapshot: TrackingProductReadSnapshot,
		val entries: List<TrackingProductSnapshot>,
		val next: TrackingProductContinuation?,
	) : TrackingProductQueryResult {
		init {
			require(entries.size <= MAX_TRACKING_PRODUCT_PAGE_SIZE)
			require(entries.map(TrackingProductSnapshot::identity).distinct().size == entries.size)
			require(entries.all { it.readSnapshot == readSnapshot })
			require(next == null || next.scope == scope)
			require(next == null || next.readSnapshot == readSnapshot)
		}
	}

	data class Unavailable(
		val reason: TrackingProductQueryUnavailableReason,
		val source: HistorySource? = null,
	) : TrackingProductQueryResult {
		init {
			if (reason.requiresSource) {
				require(source != null)
			}
		}
	}
}

enum class TrackingProductQueryUnavailableReason(
	internal val requiresSource: Boolean = false,
) {
	INVALID_CONTINUATION,
	SOURCE_EVIDENCE_STATE_UNAVAILABLE(requiresSource = true),
	SOURCE_READ_BUDGET_EXCEEDED(requiresSource = true),
	SOURCE_INTEGRITY_FAILURE(requiresSource = true),
	STRUCTURAL_AUTHORITY_UNAVAILABLE(requiresSource = true),
	ORIGIN_IDENTITY_CONFLICT(requiresSource = true),
}

/** Opaque logical product identity; it grants no storage-row authority. */
@JvmInline
value class TrackingProductIdentity(val value: String) {
	init {
		require(TRACKING_PRODUCT_SHA_256.matches(value))
	}

	override fun toString(): String = "TrackingProductIdentity"
}

/** A source origin that is independently eligible for product use. Control is intentionally absent. */
sealed interface TrackingProductOrigin {
	data class Session(
		val logicalSessionIdentity: TrackingProductIdentity,
		val consentEpoch: Long,
	) : TrackingProductOrigin {
		init {
			require(consentEpoch >= 0L)
		}
	}

	data class Imported(
		val importedEntryIdentity: TrackingProductIdentity,
	) : TrackingProductOrigin

	data class Ambient(
		val structuralDay: TrackingProductStructuralDay,
		val ambientEntryIdentity: TrackingProductIdentity,
		val consentEpoch: Long,
	) : TrackingProductOrigin {
		init {
			require(consentEpoch >= 0L)
		}
	}
}

/** Product truth without a fabricated numeric or observational value. */
enum class TrackingProductSourceState {
	QUALIFIED,
	RETAINED,
	DELETED,
	UNVERIFIABLE,
	NO_EVIDENCE,
}

/**
 * One source's state inside an all-six snapshot.
 *
 * Source-specific value contracts remain separate. This envelope carries only provenance, state,
 * and exact action authority when the producer can prove it.
 */
data class TrackingProductSourceSnapshot(
	val source: HistorySource,
	val origin: TrackingProductOrigin?,
	val state: TrackingProductSourceState,
	val actionAuthority: TrackingProductActionAuthority? = null,
) {
	init {
		when (state) {
			TrackingProductSourceState.QUALIFIED,
			TrackingProductSourceState.RETAINED,
			TrackingProductSourceState.DELETED,
			-> require(origin != null)
			TrackingProductSourceState.UNVERIFIABLE,
			TrackingProductSourceState.NO_EVIDENCE,
			-> require(origin == null)
		}
		require(
			actionAuthority == null ||
				state == TrackingProductSourceState.QUALIFIED ||
				state == TrackingProductSourceState.RETAINED,
		)
		require(actionAuthority == null || actionAuthority.selection.source == source)
		require(actionAuthority == null || actionAuthority.selection.origin == origin)
	}
}

enum class TrackingProductStructuralAuthority {
	EXACT,
	PARTIAL,
	UNVERIFIABLE,
}

/** One logical history product with a complete, source-isolated six-source state vector. */
@Suppress("LongParameterList")
data class TrackingProductSnapshot(
	val identity: TrackingProductIdentity,
	val fromInclusive: EpochMs,
	val toExclusive: EpochMs,
	val structuralDays: Set<TrackingProductStructuralDay>,
	val structuralAuthority: TrackingProductStructuralAuthority,
	val readSnapshot: TrackingProductReadSnapshot,
	val sources: List<TrackingProductSourceSnapshot>,
) {
	init {
		require(toExclusive > fromInclusive)
		require(structuralDays.size <= MAX_TRACKING_PRODUCT_DAY_COUNT)
		require(sources.map(TrackingProductSourceSnapshot::source) == HistorySource.entries)
		require(sources.all { source ->
			source.actionAuthority?.selection?.readSnapshot?.let { it == readSnapshot } != false
		})
		when (structuralAuthority) {
			TrackingProductStructuralAuthority.EXACT,
			TrackingProductStructuralAuthority.PARTIAL,
			-> require(structuralDays.isNotEmpty())
			TrackingProductStructuralAuthority.UNVERIFIABLE -> require(structuralDays.isEmpty())
		}
	}
}

enum class TrackingProductAction {
	DETAIL,
	EXPORT,
	DELETE,
}

/** Opaque checksum authenticated by the source that issued a selection. */
@JvmInline
value class TrackingProductChecksum(val value: String) {
	init {
		require(TRACKING_PRODUCT_SHA_256.matches(value))
	}

	override fun toString(): String = "TrackingProductChecksum"
}

/**
 * Opaque source-specific selection payload.
 *
 * Implementations may retain exact run hierarchies, deletion scopes, or imported lineage inside
 * their private subtype. Consumers keep and return the object unchanged.
 */
interface TrackingProductSelection

/**
 * Exact producer-issued selection and optimistic action boundary.
 *
 * The opaque [selection] preserves source-specific authority instead of reducing every source to
 * one generic row key. Revision, checksum, and read snapshot must all remain current.
 */
@Suppress("LongParameterList")
data class TrackingProductSelectionAuthority(
	val source: HistorySource,
	val origin: TrackingProductOrigin,
	val selection: TrackingProductSelection,
	val producerRevision: Long,
	val contentChecksum: TrackingProductChecksum,
	val readSnapshot: TrackingProductReadSnapshot,
) {
	init {
		require(producerRevision > 0L)
	}
}

/** Exact actions the producer authorized for one immutable selection. */
data class TrackingProductActionAuthority(
	val selection: TrackingProductSelectionAuthority,
	val actions: Set<TrackingProductAction>,
) {
	init {
		require(actions.isNotEmpty())
	}
}

enum class TrackingProductSelectionStaleness {
	COLLECTED_DATA_EPOCH_CHANGED,
	SOURCE_EVIDENCE_REVISION_CHANGED,
	PRODUCER_REVISION_CHANGED,
	CONTENT_CHECKSUM_CHANGED,
}

/** Classifies an optimistic selection boundary without treating any mismatch as current authority. */
fun TrackingProductSelectionAuthority.stalenessAgainst(
	currentSnapshot: TrackingProductReadSnapshot,
	currentProducerRevision: Long,
	currentContentChecksum: TrackingProductChecksum,
): TrackingProductSelectionStaleness? {
	require(currentProducerRevision > 0L)
	return when {
		readSnapshot.collectedDataEpoch != currentSnapshot.collectedDataEpoch ->
			TrackingProductSelectionStaleness.COLLECTED_DATA_EPOCH_CHANGED
		readSnapshot.sourceEvidenceRevision != currentSnapshot.sourceEvidenceRevision ->
			TrackingProductSelectionStaleness.SOURCE_EVIDENCE_REVISION_CHANGED
		producerRevision != currentProducerRevision ->
			TrackingProductSelectionStaleness.PRODUCER_REVISION_CHANGED
		contentChecksum != currentContentChecksum ->
			TrackingProductSelectionStaleness.CONTENT_CHECKSUM_CHANGED
		else -> null
	}
}

private val TRACKING_PRODUCT_DAY_ORDER =
	compareBy(TrackingProductStructuralDay::epochDay)
		.thenBy(TrackingProductStructuralDay::storedZoneId)
private val TRACKING_PRODUCT_SHA_256 = Regex("sha256:[0-9a-f]{64}")
private const val MAX_TRACKING_PRODUCT_ZONE_ID_LENGTH = 128
private const val MAX_TRACKING_PRODUCT_PAGE_SIZE = 100
private const val MAX_TRACKING_PRODUCT_DAY_COUNT = 370
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_TRACKING_PRODUCT_RANGE_MILLIS =
	MAX_TRACKING_PRODUCT_DAY_COUNT * MILLIS_PER_DAY
