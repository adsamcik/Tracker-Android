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
	/**
	 * Compacts imported Pressure affected by the already-published floor while rechecking source
	 * revision and data epoch.
	 */
	suspend fun truncate(
		request: TruncateImportedPressureRetentionRequest,
	): TruncateImportedPressureRetentionResult
}

/**
 * Outcome of compacting imported Pressure below the published floor, including removed row counts.
 */
sealed interface TruncateImportedPressureRetentionResult {
	/**
	 * Reports the number of imported entries and revision/run/window rows removed by retention.
	 */
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

	/**
	 * No imported Pressure rows require compaction for the requested published floor.
	 */
	data object NoChange : TruncateImportedPressureRetentionResult

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: ImportedPressureRetentionBlockedReason,
	) : TruncateImportedPressureRetentionResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: ImportedPressureMaintenanceUnverifiableReason,
	) : TruncateImportedPressureRetentionResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortablePressureTransferRetryableReason,
	) : TruncateImportedPressureRetentionResult
}

/**
 * Changed source evidence or stale published-retention request that prevents imported compaction.
 */
enum class ImportedPressureRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	STALE_REQUEST,
}

/**
 * Missing or inconsistent imported lineage, partial maintenance state or exceeded dependency/value
 * bounds.
 */
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
	/**
	 * Erases Pressure payload under the exact source lifecycle request after settling its
	 * writer/provider barrier.
	 */
	suspend fun erase(request: ErasePressureSourceRequest): ErasePressureSourceResult
}

/**
 * Outcome of source-specific Pressure erasure with separate local, legacy and imported payload
 * counts.
 */
sealed interface ErasePressureSourceResult {
	/**
	 * Reports removed native, legacy and imported Pressure payload rows and fenced native runs.
	 */
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

	/**
	 * Pressure source payload is already absent or erased.
	 */
	data object AlreadyErased : ErasePressureSourceResult

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: PressureSourceEraseBlockedReason,
	) : ErasePressureSourceResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: ImportedPressureMaintenanceUnverifiableReason,
	) : ErasePressureSourceResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PressureSourceEraseRetryableReason,
	) : ErasePressureSourceResult
}

/**
 * Active capture, changed lifecycle/destination or stale policy/erase authority preventing
 * Pressure erasure.
 */
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

/**
 * Temporary callback-drain, concurrent-state or storage failures while erasing Pressure.
 */
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

/**
 * Result of settling the physical Pressure provider and pending legacy writer before source
 * erasure.
 */
sealed interface PressureSourceEraseBarrierResult {
	/**
	 * No registered local Pressure provider remains; the token still carries the settled writer
	 * fence.
	 */
	data class NoLocalProvider(
		val token: PressureSourceEraseBarrierToken,
	) : PressureSourceEraseBarrierResult {
		init {
			require(token.providerRegistrationGeneration == null)
		}
	}

	/**
	 * The registered Pressure provider and pending writer are settled for the returned exact token.
	 */
	data class Established(
		val token: PressureSourceEraseBarrierToken,
	) : PressureSourceEraseBarrierResult {
		init {
			requireNotNull(token.providerRegistrationGeneration)
		}
	}

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: PressureSourceEraseBarrierBlockedReason,
	) : PressureSourceEraseBarrierResult

	/**
	 * The provider cannot yet be settled or verified; retry after the stated transient failure.
	 */
	data class Retryable(
		val reason: PressureSourceEraseBarrierRetryableReason,
	) : PressureSourceEraseBarrierResult
}

/**
 * Exact data epoch, optional provider generation and durable writer fence to recheck inside the
 * erase transaction.
 */
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
					legacyWriteFenceGeneration >= MIN_CONTAINED_PRESSURE_FENCE_GENERATION &&
						legacyWriteFenceGeneration % 2L == 1L,
				)
		}
	}
}

/**
 * Persisted discriminator for the legacy sample writer or contained session-fact writer that owns
 * the erase fence.
 */
enum class PressureSourceEraseFenceOwner(val storageValue: String) {
	LEGACY_PRESSURE_SAMPLE("LEGACY_PRESSURE_SAMPLE"),
	CONTAINED_PRESSURE_SESSION_FACTS("CONTAINED_PRESSURE_SESSION_FACTS"),
	;

	/**
	 * Decodes persisted Pressure writer-fence ownership values.
	 */
	companion object {
		/**
		 * Returns the matching persisted writer-fence owner, or null for an unknown storage value.
		 */
		fun fromStorageValue(value: String): PressureSourceEraseFenceOwner? =
			entries.singleOrNull { it.storageValue == value }
	}
}

/**
 * Result of rechecking the settled provider and writer-fence token while erasure is in progress.
 */
sealed interface PressureSourceEraseBarrierVerification {
	/**
	 * The current provider/writer state still matches the settled erase token.
	 */
	data object Verified : PressureSourceEraseBarrierVerification

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: PressureSourceEraseBarrierBlockedReason,
	) : PressureSourceEraseBarrierVerification

	/**
	 * The provider cannot yet be settled or verified; retry after the stated transient failure.
	 */
	data class Retryable(
		val reason: PressureSourceEraseBarrierRetryableReason,
	) : PressureSourceEraseBarrierVerification
}

/**
 * Capture still active or lifecycle changed, so the Pressure erase barrier cannot be accepted.
 */
enum class PressureSourceEraseBarrierBlockedReason {
	CAPTURE_AUTHORIZATION_ACTIVE,
	STALE_LIFECYCLE,
}

/**
 * Provider-removal or callback-drain failures that allow retrying the Pressure erase barrier.
 */
enum class PressureSourceEraseBarrierRetryableReason {
	CALLBACK_DRAIN_TIMED_OUT,
	PROVIDER_REMOVAL_FAILED,
}

private const val MIN_CONTAINED_PRESSURE_FENCE_GENERATION = 3L
