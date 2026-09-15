package com.adsamcik.tracker.stats.api.repository

/** Exact global retention authority already published before Pressure import compaction. */
data class TruncateImportedPressureRetentionRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedSourceEvidenceRevision: Long,
	val retainedFromMs: Long,
	val retainedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedSourceEvidenceRevision >= 0L)
		require(retainedFromMs >= 0L)
		require(retainedAtMs >= 0L)
	}
}

/**
 * Compacts affected imported Pressure lineages without changing the global retention floor.
 *
 * Retention workers must invoke this after publishing the exact floor and before deleting local
 * Pressure facts, source WAL, or session segments needed by source-local authentication.
 */
interface TruncateImportedPressureRetention {
	suspend fun truncate(
		request: TruncateImportedPressureRetentionRequest,
	): TruncateImportedPressureRetentionResult
}

sealed interface TruncateImportedPressureRetentionResult {
	data class Truncated(
		val entryCount: Int,
		val revisionCount: Int,
		val runRowCount: Int,
		val windowRowCount: Int,
	) : TruncateImportedPressureRetentionResult {
		init {
			require(entryCount > 0)
			require(revisionCount >= entryCount)
			require(runRowCount >= revisionCount)
			require(windowRowCount >= 0)
		}
	}

	data object NoChange : TruncateImportedPressureRetentionResult

	data class Blocked(
		val reason: ImportedPressureRetentionBlockedReason,
	) : TruncateImportedPressureRetentionResult

	data class Unverifiable(
		val reason: ImportedPressureMaintenanceUnverifiableReason,
	) : TruncateImportedPressureRetentionResult

	data class RetryableFailure(
		val reason: PortablePressureTransferRetryableReason,
	) : TruncateImportedPressureRetentionResult
}

enum class ImportedPressureRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	STALE_REQUEST,
	SOURCE_ALREADY_ERASED,
}

enum class ImportedPressureMaintenanceUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	VALUE_OVERFLOW,
	PARTIAL_MAINTENANCE_STATE,
}

/** Exact source-wide erase request. It cannot revoke consent or stop acquisition itself. */
data class ErasePressureSourceRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedSourceEvidenceRevision: Long,
	val expectedDeletedSourceEventHighWaterOrdinal: Long,
	val expectedCurrentPolicyRevision: Long,
	val expectedRevokedConsentEpoch: Long,
	val erasedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedSourceEvidenceRevision >= 0L)
		require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
		require(expectedCurrentPolicyRevision > 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(erasedAtMs >= 0L)
	}
}

/**
 * Erases Pressure capture payload and imported Pressure payload only.
 *
 * Implementations preserve other sources, sessions, CONTROL authority, and unrelated WAL.
 * Settings orchestration must call this before any shared session-segment cleanup: local Pressure
 * authentication still requires run, manifest, policy, consent, destination-owner, and WAL rows.
 * A whole-database clear is not a substitute for this source-specific privacy operation.
 */
interface ErasePressureSource {
	suspend fun erase(request: ErasePressureSourceRequest): ErasePressureSourceResult
}

sealed interface ErasePressureSourceResult {
	data class Erased(
		val localFactRevisionCount: Int,
		val localWalEventCount: Int,
		val importedEntryCount: Int,
		val importedRevisionCount: Int,
		val importedRunCount: Int,
		val importedWindowCount: Int,
		val fencedLocalRunCount: Int,
	) : ErasePressureSourceResult {
		init {
			listOf(
				localFactRevisionCount,
				localWalEventCount,
				importedEntryCount,
				importedRevisionCount,
				importedRunCount,
				importedWindowCount,
				fencedLocalRunCount,
			).forEach { require(it >= 0) }
		}
	}

	data object AlreadyErased : ErasePressureSourceResult

	data class Blocked(
		val reason: PressureSourceEraseBlockedReason,
	) : ErasePressureSourceResult

	data class Unverifiable(
		val reason: ImportedPressureMaintenanceUnverifiableReason,
	) : ErasePressureSourceResult

	data class RetryableFailure(
		val reason: PressureSourceEraseRetryableReason,
	) : ErasePressureSourceResult
}

enum class PressureSourceEraseBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	DIRECT_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	DESTINATION_OWNER_CHANGED,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
}

enum class PressureSourceEraseRetryableReason {
	CALLBACK_DRAIN_UNAVAILABLE,
	STORAGE_UNAVAILABLE,
	CONCURRENT_STATE_CHANGE,
}

/** Runtime-only barrier used by the source-wide service when local hardware evidence exists. */
interface PressureSourceEraseBarrier {
	suspend fun establish(expectedCollectedDataEpoch: Long): PressureSourceEraseBarrierResult
}

sealed interface PressureSourceEraseBarrierResult {
	data object NoLocalProvider : PressureSourceEraseBarrierResult

	data class Established(
		val registrationGeneration: Long,
	) : PressureSourceEraseBarrierResult {
		init {
			require(registrationGeneration > 0L)
		}
	}

	data class Blocked(
		val reason: PressureSourceEraseBarrierBlockedReason,
	) : PressureSourceEraseBarrierResult

	data class Retryable(
		val reason: PressureSourceEraseBarrierRetryableReason,
	) : PressureSourceEraseBarrierResult
}

enum class PressureSourceEraseBarrierBlockedReason {
	CAPTURE_AUTHORIZATION_ACTIVE,
	STALE_LIFECYCLE,
}

enum class PressureSourceEraseBarrierRetryableReason {
	CALLBACK_DRAIN_TIMED_OUT,
	PROVIDER_REMOVAL_FAILED,
}
