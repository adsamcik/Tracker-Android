package com.adsamcik.tracker.stats.api.repository

/** Exact authority supplied by the settings privacy action for Activity captured-product erasure. */
data class ActivitySourceEraseRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedRevokedConsentEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(deletedAtMs >= 0L)
	}
}

/**
 * Erases local and imported Activity captured product.
 *
 * The implementation must first prove local capture consent revocation and quiescence. CONTROL
 * continuations, unrelated source state, WAL, policy history, and provider activation remain
 * outside this command.
 */
interface ActivitySourceErase {
	/**
	 * Erases Activity captured product after consent revocation and provider quiescence; callers must
	 * continue bounded batches until complete.
	 */
	suspend fun erase(request: ActivitySourceEraseRequest): ActivitySourceEraseResult
}

/**
 * Source-wide Activity erase outcome, including completed work when a later bounded batch cannot
 * finish.
 */
sealed interface ActivitySourceEraseResult {
	/**
	 * Reports removed local and imported Activity rows and fenced local runs after source erasure.
	 */
	data class Erased(
		val localLogicalWindowCount: Int,
		val localRevisionCount: Int,
		val localRegistrationPlanCount: Int,
		val localFencedServiceRunCount: Int,
		val importedLiveEntryCount: Int,
		val importedRetainedEntryCount: Int,
		val importedPhysicalRunCount: Int,
	) : ActivitySourceEraseResult {
		init {
			listOf(
				localLogicalWindowCount,
				localRevisionCount,
				localRegistrationPlanCount,
				localFencedServiceRunCount,
				importedLiveEntryCount,
				importedRetainedEntryCount,
				importedPhysicalRunCount,
			).forEach { require(it >= 0) }
			require(
				localLogicalWindowCount > 0 || localRevisionCount > 0 ||
					localRegistrationPlanCount > 0 || importedLiveEntryCount > 0 ||
					importedRetainedEntryCount > 0,
			)
		}
	}

	/** Local payload and every imported product lineage were already absent or durably erased. */
	data object AlreadyErased : ActivitySourceEraseResult

	/**
	 * Erasure stopped at the stated authority change; fields report work completed before that
	 * boundary.
	 */
	data class Blocked(
		val reason: ActivitySourceEraseBlockedReason,
		val localProductErasedBeforeFailure: Boolean = false,
		val importedEntriesErasedBeforeFailure: Int = 0,
	) : ActivitySourceEraseResult {
		init {
			require(importedEntriesErasedBeforeFailure >= 0)
		}
	}

	/** The bounded call erased its full batch and confirmed additional imported product remains. */
	data class ContinuationRequired(
		val localProductErasedBeforeContinuation: Boolean,
		val importedLiveEntryCount: Int,
		val importedRetainedEntryCount: Int,
		val importedPhysicalRunCount: Int,
	) : ActivitySourceEraseResult {
		init {
			listOf(
				importedLiveEntryCount,
				importedRetainedEntryCount,
				importedPhysicalRunCount,
			).forEach { require(it >= 0) }
			require(importedLiveEntryCount > 0 || importedRetainedEntryCount > 0)
		}
	}

	/**
	 * Erasure could not verify the remaining scope; fields preserve the amount already erased.
	 */
	data class Unverifiable(
		val reason: ActivitySourceEraseUnverifiableReason,
		val localProductErasedBeforeFailure: Boolean = false,
		val importedEntriesErasedBeforeFailure: Int = 0,
	) : ActivitySourceEraseResult {
		init {
			require(importedEntriesErasedBeforeFailure >= 0)
		}
	}

	/**
	 * Erasure encountered a temporary failure; fields report work completed before a retry is needed.
	 */
	data class RetryableFailure(
		val reason: ActivitySourceEraseRetryableReason,
		val localProductErasedBeforeFailure: Boolean = false,
		val importedEntriesErasedBeforeFailure: Int = 0,
	) : ActivitySourceEraseResult {
		init {
			require(importedEntriesErasedBeforeFailure >= 0)
		}
	}
}

/**
 * Consent, provider, destination or imported-selection changes that prevent continuing the
 * Activity erase.
 */
enum class ActivitySourceEraseBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	CAPTURE_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	DESTINATION_OWNER_CHANGED,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
	IMPORTED_SELECTION_CHANGED,
	IMPORTED_RETENTION_BOUNDARY_CHANGED,
}

/**
 * Invalid local/imported lineage, unrecognized payload or exceeded maintenance bounds during
 * Activity erase.
 */
enum class ActivitySourceEraseUnverifiableReason {
	LOCAL_FACT_AUTHORITY_UNVERIFIABLE,
	UNRECOGNIZED_LOCAL_PAYLOAD,
	MAINTENANCE_BOUND_EXCEEDED,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	IMPORTED_ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	VALUE_OVERFLOW,
}

/**
 * Concurrent-state or storage failures for which Activity erase can be retried.
 */
enum class ActivitySourceEraseRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
