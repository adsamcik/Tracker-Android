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

	/**
	 * Retains verified imported hierarchy plus deletion and retention masks; these determine visible
	 * readings and re-export eligibility.
	 */
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

	/**
	 * Keeps the candidate and specific read failure, with a selection only when its authority was
	 * verified separately.
	 */
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

/**
 * Missing source state, stale data epoch, conflicting origin or exceeded dependency/value bounds
 * in imported Wi-Fi reads.
 */
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
	/**
	 * Evaluates the latest saved revision of the exact imported entry inside the caller's
	 * transaction, or returns null when absent.
	 */
	suspend fun selectIdentityInTransaction(
		selection: WifiImportedHistorySelectionKey,
	): ImportedWifiProductEvaluation?

	/**
	 * Evaluates the newest imported candidates inside the caller's transaction; limit must be from 1
	 * to 100.
	 */
	suspend fun selectRecentInTransaction(limit: Int): List<ImportedWifiProductEvaluation>

	/**
	 * Evaluates a bounded range page using the paired start-time/identity cursor inside the caller's
	 * transaction.
	 */
	suspend fun selectRangeInTransaction(
		request: ImportedWifiProductRangeRequest,
	): ImportedWifiProductRangePage

	/** Export candidates selected by saved scan uncertainty or recorded gaps, not entry headers. */
	suspend fun selectExportRangeInTransaction(
		request: ImportedWifiProductRangeRequest,
	): ImportedWifiProductRangePage
}

/**
 * Cursor-bearing recent-page authority for imported-only product consumers.
 * Calls must remain inside the caller-owned Room transaction.
 */
interface ImportedWifiProductRecentPageEvaluator {
	/**
	 * Reads a newest-member cursor page in the caller's transaction, returning evaluations and
	 * whether further candidates remain.
	 */
	suspend fun selectRecentPageInTransaction(
		request: ImportedWifiProductRecentRequest,
	): ImportedWifiProductRecentPage

	/**
	 * Opens a scan for the current Room transaction so its pages share source ownership and a finite
	 * dependency budget.
	 */
	suspend fun openRecentScanInTransaction(): ImportedWifiProductRecentScan =
		ImportedWifiProductRecentScan { request ->
			selectRecentPageInTransaction(request)
		}
}

/**
 * Pages one imported Wi-Fi scan inside the caller's Room transaction, sharing its bounded read
 * state.
 */
fun interface ImportedWifiProductRecentScan {
	/**
	 * Reads the next newest-member page within the owning transaction and scan budget; cancellation
	 * propagates.
	 */
	suspend fun selectPageInTransaction(
		request: ImportedWifiProductRecentRequest,
	): ImportedWifiProductRecentPage
}

/**
 * Signals exhaustion of the imported Wi-Fi snapshot dependency/query budget without a stack trace.
 */
class ImportedWifiProductReadLimitExceeded :
	RuntimeException(null, null, false, false)

/**
 * Page size from 1 to 100 with an optional paired UTC millisecond/identity cursor for the newest
 * saved member.
 */
data class ImportedWifiProductRecentRequest(
	val limit: Int,
	val beforeNewestMemberStartTimeMs: Long? = null,
	val beforeNewestMemberIdentity: PortableWifiOpaqueIdentity? = null,
) {
	init {
		require(limit in 1..MAX_IMPORTED_WIFI_PAGE_SIZE)
		require(
			(beforeNewestMemberStartTimeMs == null) == (beforeNewestMemberIdentity == null),
		)
		require(beforeNewestMemberStartTimeMs?.let { it >= 0L } != false)
	}
}

/**
 * At most 100 imported evaluations plus continuation availability; a continuing page cannot be
 * empty.
 */
data class ImportedWifiProductRecentPage(
	val evaluations: List<ImportedWifiProductEvaluation>,
	val hasMore: Boolean,
) {
	init {
		require(evaluations.size <= MAX_IMPORTED_WIFI_PAGE_SIZE)
		require(!hasMore || evaluations.isNotEmpty())
	}
}

/**
 * Half-open UTC millisecond range, page size from 1 to 100 and an optional paired
 * start-time/identity cursor.
 */
data class ImportedWifiProductRangeRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val limit: Int,
	val beforeStartTimeMs: Long? = null,
	val beforeIdentity: PortableWifiOpaqueIdentity? = null,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(limit in 1..MAX_IMPORTED_WIFI_PAGE_SIZE)
		require((beforeStartTimeMs == null) == (beforeIdentity == null))
		require(beforeStartTimeMs?.let { it >= 0L } != false)
	}
}

/**
 * At most 100 imported evaluations and whether further range candidates remain.
 */
data class ImportedWifiProductRangePage(
	val evaluations: List<ImportedWifiProductEvaluation>,
	val hasMore: Boolean,
) {
	init {
		require(evaluations.size <= MAX_IMPORTED_WIFI_PAGE_SIZE)
	}
}

/**
 * Exact imported History revision/checksum selection bound to the expected collected-data epoch.
 */
data class ReexportImportedCapturedWifiRequest(
	val selection: WifiImportedHistorySelection,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/**
 * Re-exports the selected imported Wi-Fi hierarchy without activating a provider or granting
 * native authority.
 */
interface ReexportImportedCapturedWifi {
	/**
	 * Rechecks the selected imported revision/checksum and data epoch, then emits a complete retained
	 * hierarchy outside the snapshot transaction.
	 */
	suspend fun reexport(
		request: ReexportImportedCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ReexportImportedCapturedWifiResult
}

/**
 * Imported Wi-Fi re-export outcome, separating saved output counts from missing, deleted or stale
 * selection states.
 */
sealed interface ReexportImportedCapturedWifiResult {
	/**
	 * The selected imported Wi-Fi revision was emitted with its original entry identity, checksum and
	 * counts.
	 */
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

	/**
	 * The exact selected imported Wi-Fi entry is no longer present.
	 */
	data object NotFound : ReexportImportedCapturedWifiResult
	/**
	 * The selected imported Wi-Fi entry or represented run is deleted and cannot be re-exported.
	 */
	data object Deleted : ReexportImportedCapturedWifiResult
	/**
	 * The requested complete export is unavailable for the stated capture, retention or privacy
	 * reason.
	 */
	data class Unavailable(val reason: ImportedWifiReexportUnavailableReason) :
		ReexportImportedCapturedWifiResult
	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(val reason: ImportedWifiReexportBlockedReason) :
		ReexportImportedCapturedWifiResult
	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(val reason: ImportedWifiProductFailure) :
		ReexportImportedCapturedWifiResult
	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(val reason: PortableWifiRetryableReason) :
		ReexportImportedCapturedWifiResult
}

/**
 * Retention loss or privacy-epoch mismatch preventing complete imported Wi-Fi re-export.
 */
enum class ImportedWifiReexportUnavailableReason {
	RETENTION_LIMIT,
	PRIVACY_EPOCH_MISMATCH,
}

/**
 * Changed data epoch or History revision/checksum requiring a new selection before re-export.
 */
enum class ImportedWifiReexportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_SELECTION,
}

/** Fixed foreign ownership plus exact successor semantics; no correction can be invented. */
// Reject the first nonreciprocal child immediately; a successful loop still requires a real change.
@Suppress("ReturnCount")
fun PortableCapturedWifiEntryV1.isReciprocalCorrectionOf(
	previous: PortableCapturedWifiEntryV1,
): Boolean {
	if (isAdditiveReadingSelectionOf(previous)) {
		return true
	}
	if (!hasSameImportedWifiStructureAs(previous) || contentChecksum == previous.contentChecksum) {
		return false
	}
	var changed = false
	runs.zip(previous.runs).forEach { (currentRun, previousRun) ->
		currentRun.observations.zip(previousRun.observations).forEach { (current, prior) ->
			when {
				current.semanticRevision == prior.semanticRevision -> if (current != prior) {
					return false
				}
				current.isImmediateImportedWifiCorrectionOf(prior) -> changed = true
				else -> return false
			}
		}
	}
	return changed
}

/** Full immutable hierarchy shape shared by import admission and retained product authentication. */
// Compare the full immutable hierarchy correspondence without splitting its fixed identity fields.
@Suppress("CyclomaticComplexMethod")
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

/** An immediate imported correction advances authority without rewriting represented provider payload. */
private fun PortableCapturedWifiObservationV1.isImmediateImportedWifiCorrectionOf(
	previous: PortableCapturedWifiObservationV1,
): Boolean = previous.semanticRevision < Long.MAX_VALUE &&
	semanticRevision == previous.semanticRevision + 1L &&
	supersedesSemanticRevision == previous.semanticRevision &&
	contentChecksum != previous.contentChecksum && try {
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

private const val MAX_IMPORTED_WIFI_PAGE_SIZE = 100
