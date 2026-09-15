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
	suspend fun erase(request: ActivitySourceEraseRequest): ActivitySourceEraseResult
}

sealed interface ActivitySourceEraseResult {
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

	data class Unverifiable(
		val reason: ActivitySourceEraseUnverifiableReason,
		val localProductErasedBeforeFailure: Boolean = false,
		val importedEntriesErasedBeforeFailure: Int = 0,
	) : ActivitySourceEraseResult {
		init {
			require(importedEntriesErasedBeforeFailure >= 0)
		}
	}

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

enum class ActivitySourceEraseUnverifiableReason {
	LOCAL_FACT_AUTHORITY_UNVERIFIABLE,
	UNRECOGNIZED_LOCAL_PAYLOAD,
	MAINTENANCE_BOUND_EXCEEDED,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	IMPORTED_ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	VALUE_OVERFLOW,
}

enum class ActivitySourceEraseRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
