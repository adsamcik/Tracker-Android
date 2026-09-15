package com.adsamcik.tracker.stats.api.repository

/** Bounded latest imported-entry shell read from one caller-owned Room snapshot. */
data class ImportedWifiProductCandidate(
	val identity: PortableWifiOpaqueIdentity,
	val importRevision: Long,
	val contentChecksum: PortableWifiDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val receivedAtMs: Long,
	val newestMemberStartTimeMs: Long = startTimeMs,
	val newestMemberIdentity: PortableWifiOpaqueIdentity = identity,
) {
	init {
		require(importRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(receivedAtMs >= 0L)
		require(newestMemberStartTimeMs in startTimeMs..endTimeMs)
	}

	val selection: WifiImportedHistorySelection
		get() = WifiImportedHistorySelection(
			WifiImportedHistorySelectionKey(identity.value),
			importRevision,
			contentChecksum.value,
		)
}

/**
 * Authenticated imported Wi-Fi product authority. It contains no provider, local session, WAL,
 * consent, policy, writer, or rollout grant.
 */
sealed interface ImportedWifiProductEvaluation {
	val candidate: ImportedWifiProductCandidate

	data class Readable(
		override val candidate: ImportedWifiProductCandidate,
		val entry: PortableCapturedWifiEntryV1,
		val entryDeleted: Boolean,
		val deletedRunIdentities: Set<PortableWifiOpaqueIdentity>,
		val retainedObservationIdentities: Set<PortableWifiOpaqueIdentity>,
		val retentionLimited: Boolean,
		/** Present only when every imported identity/scope collides with one local origin. */
		val collidingLocalLogicalTrackingId: String? = null,
	) : ImportedWifiProductEvaluation {
		init {
			require(candidate.identity == entry.identity)
			require(candidate.contentChecksum == entry.contentChecksum)
			require(candidate.startTimeMs == entry.startTimeMs && candidate.endTimeMs == entry.endTimeMs)
			require(deletedRunIdentities.all { deleted ->
				entry.runs.any { it.identity == deleted }
			})
			val observationIdentities = entry.runs.flatMapTo(linkedSetOf()) { run ->
				run.observations.map(PortableCapturedWifiObservationV1::identity)
			}
			require(retainedObservationIdentities.all { it in observationIdentities })
			require(!retentionLimited || retainedObservationIdentities.size < observationIdentities.size)
			require(collidingLocalLogicalTrackingId?.isNotBlank() != false)
		}

		val isReExportable: Boolean
			get() = !entryDeleted && deletedRunIdentities.isEmpty() && !retentionLimited
	}

	data class Unverifiable(
		override val candidate: ImportedWifiProductCandidate,
		val reason: ImportedWifiProductFailure,
		/** Known top-level local collision retained even when imported descendants are corrupt. */
		val collidingLocalLogicalTrackingId: String? = null,
		/** Present only when current selector authority was authenticated independently of the failure. */
		val authenticatedSelection: WifiImportedHistorySelection? = null,
	) : ImportedWifiProductEvaluation {
		init {
			require(collidingLocalLogicalTrackingId?.isNotBlank() != false)
			require(authenticatedSelection == null || authenticatedSelection == candidate.selection)
			require(
				authenticatedSelection == null ||
					reason == ImportedWifiProductFailure.VALUE_OVERFLOW,
			)
		}
	}
}

enum class ImportedWifiProductFailure {
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	VALUE_OVERFLOW,
}

/**
 * Engine-owned Room adapter contract used by the data layer without a tracker-engine dependency.
 * Every method must be called inside the caller's bounded Room transaction.
 */
interface ImportedWifiProductEvaluator {
	suspend fun selectIdentityInTransaction(
		selection: WifiImportedHistorySelectionKey,
	): ImportedWifiProductEvaluation?

	suspend fun selectRecentInTransaction(limit: Int): List<ImportedWifiProductEvaluation>

	suspend fun selectRangeInTransaction(
		request: ImportedWifiProductRangeRequest,
	): ImportedWifiProductRangePage
}

/**
 * Cursor-bearing recent-page authority for imported-only product consumers.
 * Calls must remain inside the caller-owned Room transaction.
 */
interface ImportedWifiProductRecentPageEvaluator {
	suspend fun selectRecentPageInTransaction(
		request: ImportedWifiProductRecentRequest,
	): ImportedWifiProductRecentPage

	suspend fun openRecentScanInTransaction(): ImportedWifiProductRecentScan =
		ImportedWifiProductRecentScan { request ->
			selectRecentPageInTransaction(request)
		}
}

fun interface ImportedWifiProductRecentScan {
	suspend fun selectPageInTransaction(
		request: ImportedWifiProductRecentRequest,
	): ImportedWifiProductRecentPage
}

class ImportedWifiProductReadLimitExceeded :
	RuntimeException(null, null, false, false)

data class ImportedWifiProductRecentRequest(
	val limit: Int,
	val beforeNewestMemberStartTimeMs: Long? = null,
	val beforeNewestMemberIdentity: PortableWifiOpaqueIdentity? = null,
) {
	init {
		require(limit in 1..100)
		require(
			(beforeNewestMemberStartTimeMs == null) == (beforeNewestMemberIdentity == null),
		)
		require(beforeNewestMemberStartTimeMs?.let { it >= 0L } != false)
	}
}

data class ImportedWifiProductRecentPage(
	val evaluations: List<ImportedWifiProductEvaluation>,
	val hasMore: Boolean,
) {
	init {
		require(evaluations.size <= 100)
		require(!hasMore || evaluations.isNotEmpty())
	}
}

data class ImportedWifiProductRangeRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val limit: Int,
	val beforeStartTimeMs: Long? = null,
	val beforeIdentity: PortableWifiOpaqueIdentity? = null,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(limit in 1..100)
		require((beforeStartTimeMs == null) == (beforeIdentity == null))
		require(beforeStartTimeMs?.let { it >= 0L } != false)
	}
}

data class ImportedWifiProductRangePage(
	val evaluations: List<ImportedWifiProductEvaluation>,
	val hasMore: Boolean,
) {
	init {
		require(evaluations.size <= 100)
	}
}

data class ReexportImportedCapturedWifiRequest(
	val selection: WifiImportedHistorySelection,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

interface ReexportImportedCapturedWifi {
	suspend fun reexport(
		request: ReexportImportedCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ReexportImportedCapturedWifiResult
}

sealed interface ReexportImportedCapturedWifiResult {
	data class Exported(
		val importRevision: Long,
		val entryIdentity: PortableWifiOpaqueIdentity,
		val contentChecksum: PortableWifiDigest,
		val physicalRunCount: Int,
		val observationCount: Int,
	) : ReexportImportedCapturedWifiResult {
		init {
			require(importRevision > 0L)
			require(physicalRunCount > 0 && observationCount >= 0)
		}
	}

	data object NotFound : ReexportImportedCapturedWifiResult
	data object Deleted : ReexportImportedCapturedWifiResult
	data class Unavailable(val reason: ImportedWifiReexportUnavailableReason) :
		ReexportImportedCapturedWifiResult
	data class Blocked(val reason: ImportedWifiReexportBlockedReason) :
		ReexportImportedCapturedWifiResult
	data class Unverifiable(val reason: ImportedWifiProductFailure) :
		ReexportImportedCapturedWifiResult
	data class RetryableFailure(val reason: PortableWifiRetryableReason) :
		ReexportImportedCapturedWifiResult
}

enum class ImportedWifiReexportUnavailableReason {
	RETENTION_LIMIT,
	PRIVACY_EPOCH_MISMATCH,
}

enum class ImportedWifiReexportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_SELECTION,
}

/** Fixed foreign ownership plus exact successor semantics; no correction can be invented. */
fun PortableCapturedWifiEntryV1.isReciprocalCorrectionOf(
	previous: PortableCapturedWifiEntryV1,
): Boolean {
	if (!hasSameImportedWifiStructureAs(previous) || contentChecksum == previous.contentChecksum) {
		return false
	}
	var changed = false
	runs.zip(previous.runs).forEach { (currentRun, previousRun) ->
		currentRun.observations.zip(previousRun.observations).forEach { (current, prior) ->
			when {
				current.semanticRevision == prior.semanticRevision -> if (current != prior) return false
				prior.semanticRevision < Long.MAX_VALUE &&
					current.semanticRevision == prior.semanticRevision + 1L &&
					current.supersedesSemanticRevision == prior.semanticRevision &&
					current.contentChecksum != prior.contentChecksum &&
					current.hasSameImportedWifiPayloadAs(prior) -> changed = true
				else -> return false
			}
		}
	}
	return changed
}

/** Full immutable hierarchy shape shared by import admission and retained product authentication. */
fun PortableCapturedWifiEntryV1.hasSameImportedWifiStructureAs(
	other: PortableCapturedWifiEntryV1,
): Boolean = identity == other.identity && sessionMode == other.sessionMode &&
	startTimeMs == other.startTimeMs && endTimeMs == other.endTimeMs &&
	runs.size == other.runs.size && runs.zip(other.runs).all { (left, right) ->
		left.identity == right.identity && left.deletionScopeDigest == right.deletionScopeDigest &&
			left.startTimeMs == right.startTimeMs && left.endTimeMs == right.endTimeMs &&
			left.storedZoneIds == right.storedZoneIds && left.captureCoverage == right.captureCoverage &&
			left.availability == right.availability &&
			left.acquisitionCompleteness == right.acquisitionCompleteness &&
			left.hasUnresolvedProviderRange == right.hasUnresolvedProviderRange &&
			left.retentionLoss == right.retentionLoss &&
			left.observations.size == right.observations.size &&
			left.observations.zip(right.observations).all { (leftObservation, rightObservation) ->
				leftObservation.identity == rightObservation.identity &&
					leftObservation.aggregateOwnerIdentity == rightObservation.aggregateOwnerIdentity
			}
	}

/** Imported corrections settle authority without rewriting represented provider payload. */
private fun PortableCapturedWifiObservationV1.hasSameImportedWifiPayloadAs(
	previous: PortableCapturedWifiObservationV1,
): Boolean = try {
	copy(
		semanticRevision = previous.semanticRevision,
		supersedesSemanticRevision = previous.supersedesSemanticRevision,
		contentChecksum = previous.contentChecksum,
	) == previous
} catch (_: IllegalArgumentException) {
	false
} catch (_: ArithmeticException) {
	false
}
