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
		val legacySampleCount: Int,
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
				legacySampleCount,
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
	/**
	 * Settles the physical provider and the legacy Pressure persistence lane.
	 *
	 * The legacy fence must make pre-fence pending or in-memory Pressure samples ineligible for a
	 * later `pressure_sample` flush, not merely unregister the SensorManager listener.
	 */
	suspend fun establish(expectedCollectedDataEpoch: Long): PressureSourceEraseBarrierResult

	/**
	 * Revalidates the exact settled token while the erase transaction is open.
	 *
	 * Runtime integration must use the same durable generation consulted by every legacy Pressure
	 * writer so a delayed buffer cannot pass after this check.
	 */
	suspend fun verifySettled(
		token: PressureSourceEraseBarrierToken,
	): PressureSourceEraseBarrierVerification
}

sealed interface PressureSourceEraseBarrierResult {
	data class NoLocalProvider(
		val token: PressureSourceEraseBarrierToken,
	) : PressureSourceEraseBarrierResult {
		init {
			require(token.providerRegistrationGeneration == null)
		}
	}

	data class Established(
		val token: PressureSourceEraseBarrierToken,
	) : PressureSourceEraseBarrierResult {
		init {
			requireNotNull(token.providerRegistrationGeneration)
		}
	}

	data class Blocked(
		val reason: PressureSourceEraseBarrierBlockedReason,
	) : PressureSourceEraseBarrierResult

	data class Retryable(
		val reason: PressureSourceEraseBarrierRetryableReason,
	) : PressureSourceEraseBarrierResult
}

data class PressureSourceEraseBarrierToken(
	val collectedDataEpoch: Long,
	val providerRegistrationGeneration: Long?,
	/** Exact durable destination owner paired with [legacyWriteFenceGeneration]. */
	val legacyWriteFenceOwner: PressureSourceEraseFenceOwner,
	val legacyWriteFenceGeneration: Long,
) {
	init {
		require(collectedDataEpoch >= 0L)
		require(providerRegistrationGeneration == null || providerRegistrationGeneration > 0L)
		require(legacyWriteFenceGeneration > 0L)
		when (legacyWriteFenceOwner) {
			PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE ->
				require(legacyWriteFenceGeneration >= 2L)
			PressureSourceEraseFenceOwner.CONTAINED_PRESSURE_SESSION_FACTS ->
				require(
					legacyWriteFenceGeneration >= 3L &&
						legacyWriteFenceGeneration % 2L == 1L,
				)
		}
	}
}

enum class PressureSourceEraseFenceOwner(val storageValue: String) {
	LEGACY_PRESSURE_SAMPLE("LEGACY_PRESSURE_SAMPLE"),
	CONTAINED_PRESSURE_SESSION_FACTS("CONTAINED_PRESSURE_SESSION_FACTS"),
	;

	companion object {
		fun fromStorageValue(value: String): PressureSourceEraseFenceOwner? =
			entries.singleOrNull { it.storageValue == value }
	}
}

sealed interface PressureSourceEraseBarrierVerification {
	data object Verified : PressureSourceEraseBarrierVerification

	data class Blocked(
		val reason: PressureSourceEraseBarrierBlockedReason,
	) : PressureSourceEraseBarrierVerification

	data class Retryable(
		val reason: PressureSourceEraseBarrierRetryableReason,
	) : PressureSourceEraseBarrierVerification
}

enum class PressureSourceEraseBarrierBlockedReason {
	CAPTURE_AUTHORIZATION_ACTIVE,
	STALE_LIFECYCLE,
}

enum class PressureSourceEraseBarrierRetryableReason {
	CALLBACK_DRAIN_TIMED_OUT,
	PROVIDER_REMOVAL_FAILED,
}
