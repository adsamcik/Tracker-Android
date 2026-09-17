package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * Read-only all-source product query.
 *
 * Implementations compose retained source evidence only. Reading cannot create demand, start or
 * retain a provider, repair a projection, or authorize a writer.
 */
interface TrackingProductQuery {
	suspend fun read(request: TrackingProductQueryRequest): TrackingProductQueryResult
}

/** Exact public scope for one bounded product read. */
sealed interface TrackingProductQueryScope {
	internal val canonicalValue: String

	/** Absolute wall-time scope; stored-zone membership remains attached to returned products. */
	data class WallRange(
		val fromInclusive: EpochMs,
		val toExclusive: EpochMs,
	) : TrackingProductQueryScope {
		override val canonicalValue: String
			get() = "wall:${fromInclusive.raw}:${toExclusive.raw}"

		init {
			require(toExclusive > fromInclusive)
			require(toExclusive.raw - fromInclusive.raw <= MAX_TRACKING_PRODUCT_RANGE_MILLIS)
		}

		internal fun intersects(other: WallRange): Boolean =
			fromInclusive < other.toExclusive && other.fromInclusive < toExclusive

		internal fun contains(other: WallRange): Boolean =
			fromInclusive <= other.fromInclusive && toExclusive >= other.toExclusive
	}

	/**
	 * Structural days under zones persisted with source evidence.
	 *
	 * The constructor snapshots [days]. Callers cannot substitute the current device zone.
	 */
	class StructuralDays(days: List<TrackingProductStructuralDay>) : TrackingProductQueryScope {
		private val daysSnapshot = days.toList()
		val days: List<TrackingProductStructuralDay>
			get() = daysSnapshot.toList()

		override val canonicalValue: String =
			daysSnapshot.joinToString(prefix = "days:", separator = "|") { day ->
				"${day.epochDay}:${day.storedZoneId.length}:${day.storedZoneId}"
			}

		init {
			require(daysSnapshot.isNotEmpty())
			require(daysSnapshot.size <= MAX_TRACKING_PRODUCT_DAY_COUNT)
			require(daysSnapshot.distinct().size == daysSnapshot.size)
			require(daysSnapshot == daysSnapshot.sortedWith(TRACKING_PRODUCT_DAY_ORDER))
			val span =
				daysSnapshot.last().epochDay.toULong() -
					daysSnapshot.first().epochDay.toULong()
			require(span < MAX_TRACKING_PRODUCT_DAY_COUNT.toULong())
		}

		override fun equals(other: Any?): Boolean =
			other is StructuralDays && daysSnapshot == other.daysSnapshot

		override fun hashCode(): Int = daysSnapshot.hashCode()
		override fun toString(): String = "TrackingProductQueryScope.StructuralDays"
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

/** Stable value identity for an exact query scope and page limit. */
class TrackingProductQueryRequestIdentity private constructor(
	private val canonicalValue: String,
) {
	override fun equals(other: Any?): Boolean =
		other is TrackingProductQueryRequestIdentity &&
			canonicalValue == other.canonicalValue

	override fun hashCode(): Int = canonicalValue.hashCode()
	override fun toString(): String = "TrackingProductQueryRequestIdentity"

	internal companion object {
		fun trusted(
			scope: TrackingProductQueryScope,
			limit: Int,
		): TrackingProductQueryRequestIdentity =
			TrackingProductQueryRequestIdentity("${scope.canonicalValue}|limit:$limit")
	}
}

class TrackingProductQueryRequest(
	val scope: TrackingProductQueryScope,
	val limit: Int,
	val continuation: TrackingProductContinuation? = null,
) {
	val identity: TrackingProductQueryRequestIdentity =
		TrackingProductQueryRequestIdentity.trusted(scope, limit)

	init {
		require(limit in 1..MAX_TRACKING_PRODUCT_PAGE_SIZE)
		require(continuation == null || continuation.requestIdentity == identity) {
			"A continuation can resume only its exact originating scope and limit"
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

/** Module-owned producer identity used only to authenticate opaque capabilities. */
internal class TrackingProductCapabilityIssuer(
	private val canonicalValue: String,
) {
	init {
		require(canonicalValue.isNotBlank())
	}

	override fun equals(other: Any?): Boolean =
		other is TrackingProductCapabilityIssuer &&
			canonicalValue == other.canonicalValue

	override fun hashCode(): Int = canonicalValue.hashCode()
}

/**
 * Opaque keyset continuation.
 *
 * Construction and reconstruction are module-owned. Equality is stable when a trusted codec
 * reconstructs the same producer payload, request identity, and read snapshot.
 */
class TrackingProductContinuation private constructor(
	private val issuer: TrackingProductCapabilityIssuer,
	private val canonicalPayload: String,
	val requestIdentity: TrackingProductQueryRequestIdentity,
	val readSnapshot: TrackingProductReadSnapshot,
) {
	internal fun isIssuedBy(expected: TrackingProductCapabilityIssuer): Boolean =
		issuer == expected

	override fun equals(other: Any?): Boolean =
		other is TrackingProductContinuation &&
			issuer == other.issuer &&
			canonicalPayload == other.canonicalPayload &&
			requestIdentity == other.requestIdentity &&
			readSnapshot == other.readSnapshot

	override fun hashCode(): Int {
		var result = issuer.hashCode()
		result = 31 * result + canonicalPayload.hashCode()
		result = 31 * result + requestIdentity.hashCode()
		result = 31 * result + readSnapshot.hashCode()
		return result
	}

	override fun toString(): String = "TrackingProductContinuation"

	internal companion object {
		fun trusted(
			issuer: TrackingProductCapabilityIssuer,
			canonicalPayload: String,
			requestIdentity: TrackingProductQueryRequestIdentity,
			readSnapshot: TrackingProductReadSnapshot,
		): TrackingProductContinuation {
			require(canonicalPayload.isNotBlank())
			return TrackingProductContinuation(
				issuer,
				canonicalPayload,
				requestIdentity,
				readSnapshot,
			)
		}
	}
}

/** Exact wall/day authority independently authenticated by a source producer. */
class TrackingProductAuthorityScope(
	val wallRange: TrackingProductQueryScope.WallRange?,
	structuralDays: Set<TrackingProductStructuralDay>,
) {
	private val structuralDaysSnapshot = structuralDays.toSet()
	val structuralDays: Set<TrackingProductStructuralDay>
		get() = structuralDaysSnapshot.toSet()

	init {
		require(wallRange != null || structuralDaysSnapshot.isNotEmpty())
		require(structuralDaysSnapshot.size <= MAX_TRACKING_PRODUCT_DAY_COUNT)
	}

	internal fun isWithin(queryScope: TrackingProductQueryScope): Boolean = when (queryScope) {
		is TrackingProductQueryScope.WallRange ->
			wallRange?.let(queryScope::intersects) == true
		is TrackingProductQueryScope.StructuralDays ->
			structuralDaysSnapshot.any(queryScope.days.toSet()::contains)
	}

	internal fun contains(other: TrackingProductAuthorityScope): Boolean {
		val containsWall = when {
			other.wallRange == null -> true
			wallRange == null -> false
			else -> wallRange.contains(other.wallRange)
		}
		return containsWall && structuralDaysSnapshot.containsAll(other.structuralDaysSnapshot)
	}

	override fun equals(other: Any?): Boolean =
		other is TrackingProductAuthorityScope &&
			wallRange == other.wallRange &&
			structuralDaysSnapshot == other.structuralDaysSnapshot

	override fun hashCode(): Int =
		31 * (wallRange?.hashCode() ?: 0) + structuralDaysSnapshot.hashCode()

	override fun toString(): String = "TrackingProductAuthorityScope"
}

sealed interface TrackingProductQueryResult {
	/**
	 * One immutable page bound to the exact originating [requestIdentity].
	 *
	 * The constructor snapshots [entries], enforces the request limit, rejects out-of-scope rows,
	 * and requires any continuation to retain the same request identity and read snapshot.
	 */
	class Page(
		val scope: TrackingProductQueryScope,
		val limit: Int,
		val requestIdentity: TrackingProductQueryRequestIdentity,
		val readSnapshot: TrackingProductReadSnapshot,
		entries: List<TrackingProductSnapshot>,
		val next: TrackingProductContinuation?,
	) : TrackingProductQueryResult {
		private val entriesSnapshot = entries.toList()
		val entries: List<TrackingProductSnapshot>
			get() = entriesSnapshot.toList()

		init {
			require(limit in 1..MAX_TRACKING_PRODUCT_PAGE_SIZE)
			require(requestIdentity == TrackingProductQueryRequestIdentity.trusted(scope, limit))
			require(entriesSnapshot.size <= limit)
			require(
				entriesSnapshot.map(TrackingProductSnapshot::identity).distinct().size ==
					entriesSnapshot.size,
			)
			require(entriesSnapshot.all { it.readSnapshot == readSnapshot })
			require(entriesSnapshot.all { entry ->
				entry.authorityScope.isWithin(scope) &&
					(scope !is TrackingProductQueryScope.StructuralDays ||
						entry.structuralVerification !=
						TrackingProductStructuralVerification.UNVERIFIABLE)
			}) { "Every page entry requires proven membership in the originating request scope" }
			require(next == null || next.requestIdentity == requestIdentity)
			require(next == null || next.readSnapshot == readSnapshot)
		}
	}

	data class Unavailable(
		val reason: TrackingProductQueryUnavailableReason,
		val source: HistorySource? = null,
	) : TrackingProductQueryResult {
		init {
			if (reason.requiresSource) require(source != null)
		}
	}
}

enum class TrackingProductQueryUnavailableReason(
	internal val requiresSource: Boolean = false,
) {
	INVALID_CONTINUATION,
	FOREIGN_CONTINUATION,
	SOURCE_EVIDENCE_STATE_UNAVAILABLE(requiresSource = true),
	SOURCE_READ_BUDGET_EXCEEDED(requiresSource = true),
	SOURCE_INTEGRITY_FAILURE(requiresSource = true),
	STRUCTURAL_AUTHORITY_UNAVAILABLE(requiresSource = true),
	ORIGIN_IDENTITY_CONFLICT(requiresSource = true),
}

/** Opaque logical product identity reconstructed only by a trusted producer codec. */
class TrackingProductIdentity private constructor(
	private val canonicalValue: String,
) {
	override fun equals(other: Any?): Boolean =
		other is TrackingProductIdentity && canonicalValue == other.canonicalValue

	override fun hashCode(): Int = canonicalValue.hashCode()
	override fun toString(): String = "TrackingProductIdentity"

	internal companion object {
		fun trusted(canonicalValue: String): TrackingProductIdentity {
			require(TRACKING_PRODUCT_SHA_256.matches(canonicalValue))
			return TrackingProductIdentity(canonicalValue)
		}
	}
}

enum class TrackingProductOriginKind {
	SESSION,
	IMPORTED,
	AMBIENT,
}

/**
 * Opaque proven origin. Verification state is deliberately separate.
 *
 * Constructors are module-owned so callers cannot alter consent, session, import, or structural
 * authority while retaining a producer-issued identity.
 */
class TrackingProductOrigin private constructor(
	val kind: TrackingProductOriginKind,
	val identity: TrackingProductIdentity,
	val consentEpoch: Long?,
	val structuralDay: TrackingProductStructuralDay?,
) {
	init {
		when (kind) {
			TrackingProductOriginKind.SESSION -> {
				require(consentEpoch != null && consentEpoch >= 0L)
				require(structuralDay == null)
			}
			TrackingProductOriginKind.IMPORTED -> {
				require(consentEpoch == null)
				require(structuralDay == null)
			}
			TrackingProductOriginKind.AMBIENT -> {
				require(consentEpoch != null && consentEpoch >= 0L)
				require(structuralDay != null)
			}
		}
	}

	override fun equals(other: Any?): Boolean =
		other is TrackingProductOrigin &&
			kind == other.kind &&
			identity == other.identity &&
			consentEpoch == other.consentEpoch &&
			structuralDay == other.structuralDay

	override fun hashCode(): Int {
		var result = kind.hashCode()
		result = 31 * result + identity.hashCode()
		result = 31 * result + (consentEpoch?.hashCode() ?: 0)
		result = 31 * result + (structuralDay?.hashCode() ?: 0)
		return result
	}

	override fun toString(): String = "TrackingProductOrigin.$kind"

	internal companion object {
		fun session(
			identity: TrackingProductIdentity,
			consentEpoch: Long,
		): TrackingProductOrigin =
			TrackingProductOrigin(
				TrackingProductOriginKind.SESSION,
				identity,
				consentEpoch,
				null,
			)

		fun imported(identity: TrackingProductIdentity): TrackingProductOrigin =
			TrackingProductOrigin(
				TrackingProductOriginKind.IMPORTED,
				identity,
				null,
				null,
			)

		fun ambient(
			identity: TrackingProductIdentity,
			consentEpoch: Long,
			structuralDay: TrackingProductStructuralDay,
		): TrackingProductOrigin =
			TrackingProductOrigin(
				TrackingProductOriginKind.AMBIENT,
				identity,
				consentEpoch,
				structuralDay,
			)
	}
}

/** Verification of product values, independent of known origin and action capability. */
enum class TrackingProductVerificationState {
	QUALIFIED,
	RETAINED,
	DELETED,
	UNVERIFIABLE,
	NO_EVIDENCE,
}

/** Opaque source-specific selection reconstructed only by a trusted producer codec. */
class TrackingProductSelection private constructor(
	private val issuer: TrackingProductCapabilityIssuer,
	private val canonicalPayload: String,
) {
	internal fun isIssuedBy(expected: TrackingProductCapabilityIssuer): Boolean =
		issuer == expected

	override fun equals(other: Any?): Boolean =
		other is TrackingProductSelection &&
			issuer == other.issuer &&
			canonicalPayload == other.canonicalPayload

	override fun hashCode(): Int =
		31 * issuer.hashCode() + canonicalPayload.hashCode()

	override fun toString(): String = "TrackingProductSelection"

	internal companion object {
		fun trusted(
			issuer: TrackingProductCapabilityIssuer,
			canonicalPayload: String,
		): TrackingProductSelection {
			require(canonicalPayload.isNotBlank())
			return TrackingProductSelection(issuer, canonicalPayload)
		}
	}
}

/** Opaque checksum authenticated by the source that issued a selection. */
class TrackingProductChecksum private constructor(
	private val canonicalValue: String,
) {
	override fun equals(other: Any?): Boolean =
		other is TrackingProductChecksum && canonicalValue == other.canonicalValue

	override fun hashCode(): Int = canonicalValue.hashCode()
	override fun toString(): String = "TrackingProductChecksum"

	internal companion object {
		fun trusted(canonicalValue: String): TrackingProductChecksum {
			require(TRACKING_PRODUCT_SHA_256.matches(canonicalValue))
			return TrackingProductChecksum(canonicalValue)
		}
	}
}

/**
 * Exact producer-issued selection and optimistic action boundary.
 *
 * Construction is module-owned. The opaque selection preserves source-specific run hierarchy,
 * deletion scope, or import lineage rather than reducing authority to a generic row key.
 */
class TrackingProductSelectionAuthority private constructor(
	private val issuer: TrackingProductCapabilityIssuer,
	val source: HistorySource,
	val productIdentity: TrackingProductIdentity,
	val origin: TrackingProductOrigin,
	val authorityScope: TrackingProductAuthorityScope,
	val selection: TrackingProductSelection,
	val producerRevision: Long,
	val contentChecksum: TrackingProductChecksum,
	val readSnapshot: TrackingProductReadSnapshot,
) {
	internal fun isIssuedBy(expected: TrackingProductCapabilityIssuer): Boolean =
		issuer == expected && selection.isIssuedBy(expected)

	override fun equals(other: Any?): Boolean =
		other is TrackingProductSelectionAuthority &&
			issuer == other.issuer &&
			source == other.source &&
			productIdentity == other.productIdentity &&
			origin == other.origin &&
			authorityScope == other.authorityScope &&
			selection == other.selection &&
			producerRevision == other.producerRevision &&
			contentChecksum == other.contentChecksum &&
			readSnapshot == other.readSnapshot

	override fun hashCode(): Int {
		var result = issuer.hashCode()
		result = 31 * result + source.hashCode()
		result = 31 * result + productIdentity.hashCode()
		result = 31 * result + origin.hashCode()
		result = 31 * result + authorityScope.hashCode()
		result = 31 * result + selection.hashCode()
		result = 31 * result + producerRevision.hashCode()
		result = 31 * result + contentChecksum.hashCode()
		result = 31 * result + readSnapshot.hashCode()
		return result
	}

	override fun toString(): String = "TrackingProductSelectionAuthority"

	internal companion object {
		@Suppress("LongParameterList")
		fun trusted(
			issuer: TrackingProductCapabilityIssuer,
			source: HistorySource,
			productIdentity: TrackingProductIdentity,
			origin: TrackingProductOrigin,
			authorityScope: TrackingProductAuthorityScope,
			selection: TrackingProductSelection,
			producerRevision: Long,
			contentChecksum: TrackingProductChecksum,
			readSnapshot: TrackingProductReadSnapshot,
		): TrackingProductSelectionAuthority {
			require(producerRevision > 0L)
			require(selection.isIssuedBy(issuer))
			return TrackingProductSelectionAuthority(
				issuer,
				source,
				productIdentity,
				origin,
				authorityScope,
				selection,
				producerRevision,
				contentChecksum,
				readSnapshot,
			)
		}
	}
}

enum class TrackingProductAction {
	DETAIL,
	EXPORT,
	DELETE,
}

/** Exact producer-issued actions for one immutable selection. */
class TrackingProductActionAuthority private constructor(
	private val issuer: TrackingProductCapabilityIssuer,
	val selection: TrackingProductSelectionAuthority,
	actions: Set<TrackingProductAction>,
) {
	private val actionsSnapshot = actions.toSet()
	val actions: Set<TrackingProductAction>
		get() = actionsSnapshot.toSet()

	internal fun isIssuedBy(expected: TrackingProductCapabilityIssuer): Boolean =
		issuer == expected && selection.isIssuedBy(expected)

	init {
		require(actionsSnapshot.isNotEmpty())
		require(selection.isIssuedBy(issuer))
	}

	override fun equals(other: Any?): Boolean =
		other is TrackingProductActionAuthority &&
			issuer == other.issuer &&
			selection == other.selection &&
			actionsSnapshot == other.actionsSnapshot

	override fun hashCode(): Int =
		31 * (31 * issuer.hashCode() + selection.hashCode()) + actionsSnapshot.hashCode()

	override fun toString(): String = "TrackingProductActionAuthority"

	internal companion object {
		fun trusted(
			issuer: TrackingProductCapabilityIssuer,
			selection: TrackingProductSelectionAuthority,
			actions: Set<TrackingProductAction>,
		): TrackingProductActionAuthority =
			TrackingProductActionAuthority(issuer, selection, actions)
	}
}

/**
 * One source state inside an all-six snapshot.
 *
 * Proven origin survives value verification failure. An independently authenticated action
 * capability may remain available on retained, deleted, or unverifiable value-free shells.
 */
data class TrackingProductSourceSnapshot(
	val source: HistorySource,
	val verificationState: TrackingProductVerificationState,
	val origin: TrackingProductOrigin?,
	val actionAuthority: TrackingProductActionAuthority? = null,
) {
	init {
		when (verificationState) {
			TrackingProductVerificationState.QUALIFIED,
			TrackingProductVerificationState.RETAINED,
			TrackingProductVerificationState.DELETED,
			-> require(origin != null)
			TrackingProductVerificationState.UNVERIFIABLE,
			TrackingProductVerificationState.NO_EVIDENCE,
			-> Unit
		}
		require(actionAuthority == null || actionAuthority.selection.source == source)
		require(actionAuthority == null || actionAuthority.selection.origin == origin)
	}
}

enum class TrackingProductStructuralVerification {
	EXACT,
	PARTIAL,
	UNVERIFIABLE,
}

/** One logical history product with a complete source-isolated six-source state vector. */
class TrackingProductSnapshot(
	val identity: TrackingProductIdentity,
	val authorityScope: TrackingProductAuthorityScope,
	val structuralVerification: TrackingProductStructuralVerification,
	val readSnapshot: TrackingProductReadSnapshot,
	sources: List<TrackingProductSourceSnapshot>,
) {
	private val sourcesSnapshot = sources.toList()
	val sources: List<TrackingProductSourceSnapshot>
		get() = sourcesSnapshot.toList()

	init {
		require(sourcesSnapshot.map(TrackingProductSourceSnapshot::source) == HistorySource.entries)
		when (structuralVerification) {
			TrackingProductStructuralVerification.EXACT,
			TrackingProductStructuralVerification.PARTIAL,
			-> require(authorityScope.structuralDays.isNotEmpty())
			TrackingProductStructuralVerification.UNVERIFIABLE -> Unit
		}
		sourcesSnapshot.forEach { source ->
			source.actionAuthority?.let { actionAuthority ->
				val selection = actionAuthority.selection
				require(selection.productIdentity == identity)
				require(selection.authorityScope == authorityScope)
				require(selection.readSnapshot == readSnapshot)
			}
		}
	}
}

enum class TrackingProductSelectionStaleness {
	COLLECTED_DATA_EPOCH_CHANGED,
	SOURCE_EVIDENCE_REVISION_CHANGED,
	PRODUCER_REVISION_CHANGED,
	CONTENT_CHECKSUM_CHANGED,
}

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

data class TrackingProductActionRequest(
	val authority: TrackingProductActionAuthority,
	val action: TrackingProductAction,
) {
	init {
		require(action in authority.actions)
	}
}

/**
 * Executes producer-issued actions.
 *
 * Implementations must reauthenticate the opaque capability against the exact source producer,
 * then re-read revision, checksum, read snapshot, identity, and authority scope. Caller-visible
 * fields are never sufficient authority.
 */
interface TrackingProductActionDispatcher {
	suspend fun dispatch(request: TrackingProductActionRequest): TrackingProductActionResult
}

sealed interface TrackingProductActionResult {
	data object Applied : TrackingProductActionResult
	data class Rejected(
		val reason: TrackingProductActionRejectionReason,
	) : TrackingProductActionResult
	data class RetryableFailure(
		val reason: TrackingProductActionRetryableReason,
	) : TrackingProductActionResult
}

enum class TrackingProductActionRejectionReason {
	FOREIGN_CAPABILITY,
	ACTION_NOT_AUTHORIZED,
	STALE_SELECTION,
	OUTSIDE_AUTHORITY,
	ORIGIN_CONFLICT,
}

internal fun TrackingProductActionAuthority.producerRejection(
	expectedIssuer: TrackingProductCapabilityIssuer,
): TrackingProductActionRejectionReason? =
	if (isIssuedBy(expectedIssuer)) {
		null
	} else {
		TrackingProductActionRejectionReason.FOREIGN_CAPABILITY
	}

enum class TrackingProductActionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}

/** Module-owned test/codec seam; future source adapters live beside this contract. */
internal object TrackingProductTrustedCapabilities {
	fun issuer(canonicalValue: String): TrackingProductCapabilityIssuer =
		TrackingProductCapabilityIssuer(canonicalValue)

	fun identity(canonicalValue: String): TrackingProductIdentity =
		TrackingProductIdentity.trusted(canonicalValue)

	fun checksum(canonicalValue: String): TrackingProductChecksum =
		TrackingProductChecksum.trusted(canonicalValue)

	fun sessionOrigin(
		identity: TrackingProductIdentity,
		consentEpoch: Long,
	): TrackingProductOrigin = TrackingProductOrigin.session(identity, consentEpoch)

	fun importedOrigin(identity: TrackingProductIdentity): TrackingProductOrigin =
		TrackingProductOrigin.imported(identity)

	fun ambientOrigin(
		identity: TrackingProductIdentity,
		consentEpoch: Long,
		structuralDay: TrackingProductStructuralDay,
	): TrackingProductOrigin =
		TrackingProductOrigin.ambient(identity, consentEpoch, structuralDay)

	fun selection(
		issuer: TrackingProductCapabilityIssuer,
		canonicalPayload: String,
	): TrackingProductSelection =
		TrackingProductSelection.trusted(issuer, canonicalPayload)

	@Suppress("LongParameterList")
	fun selectionAuthority(
		issuer: TrackingProductCapabilityIssuer,
		source: HistorySource,
		productIdentity: TrackingProductIdentity,
		origin: TrackingProductOrigin,
		authorityScope: TrackingProductAuthorityScope,
		selection: TrackingProductSelection,
		producerRevision: Long,
		contentChecksum: TrackingProductChecksum,
		readSnapshot: TrackingProductReadSnapshot,
	): TrackingProductSelectionAuthority =
		TrackingProductSelectionAuthority.trusted(
			issuer,
			source,
			productIdentity,
			origin,
			authorityScope,
			selection,
			producerRevision,
			contentChecksum,
			readSnapshot,
		)

	fun actionAuthority(
		issuer: TrackingProductCapabilityIssuer,
		selection: TrackingProductSelectionAuthority,
		actions: Set<TrackingProductAction>,
	): TrackingProductActionAuthority =
		TrackingProductActionAuthority.trusted(issuer, selection, actions)

	fun continuation(
		issuer: TrackingProductCapabilityIssuer,
		canonicalPayload: String,
		requestIdentity: TrackingProductQueryRequestIdentity,
		readSnapshot: TrackingProductReadSnapshot,
	): TrackingProductContinuation =
		TrackingProductContinuation.trusted(
			issuer,
			canonicalPayload,
			requestIdentity,
			readSnapshot,
		)
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
