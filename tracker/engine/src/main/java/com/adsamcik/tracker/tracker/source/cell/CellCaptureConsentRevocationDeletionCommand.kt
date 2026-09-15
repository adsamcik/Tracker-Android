package com.adsamcik.tracker.tracker.source.cell

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedSourceDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedSourceDeletionResult
import com.adsamcik.tracker.shared.base.database.deleteCapturedCellFactsAfterConsentReset
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.tracker.source.runtime.CellCaptureDeletionBarrierBlockedReason
import com.adsamcik.tracker.tracker.source.runtime.CellCaptureDeletionBarrierResult
import com.adsamcik.tracker.tracker.source.runtime.CellCaptureDeletionBarrierRetryableReason
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class CellCaptureConsentRevocationDeletionRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedDeletedSourceEventHighWaterOrdinal: Long,
	val expectedRevokedConsentEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(deletedAtMs >= 0L)
	}
}

internal enum class CellCaptureConsentRevocationDeletionRetryableReason {
	CALLBACK_BARRIER_UNAVAILABLE,
	STORAGE_UNAVAILABLE,
}

internal sealed interface CellCaptureConsentRevocationDeletionResult {
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : CellCaptureConsentRevocationDeletionResult

	data object AlreadyDeleted : CellCaptureConsentRevocationDeletionResult

	data class Blocked(
		val reason: CellCapturedSourceDeletionBlockedReason,
	) : CellCaptureConsentRevocationDeletionResult

	data class Retryable(
		val reason: CellCaptureConsentRevocationDeletionRetryableReason,
	) : CellCaptureConsentRevocationDeletionResult
}

/**
 * Explicit Cell capture-consent cleanup. It neither changes policy nor starts/stops acquisition;
 * it only asks the already-running Cell owner to settle its callback barrier before invoking the
 * authenticated source-local Room deletion.
 */
@Singleton
internal class CellCaptureConsentRevocationDeletionCommand @Inject constructor(
	private val database: AppDatabase,
	private val runtime: CellSourceRuntime,
) {
	suspend fun delete(
		request: CellCaptureConsentRevocationDeletionRequest,
	): CellCaptureConsentRevocationDeletionResult {
		val preflight = try {
			preflight(request)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("SwallowedException") _: SQLiteException) {
			return CellCaptureConsentRevocationDeletionResult.Retryable(
				CellCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
			return CellCaptureConsentRevocationDeletionResult.Blocked(
				CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		}
		if (preflight != null) {
			return CellCaptureConsentRevocationDeletionResult.Blocked(preflight)
		}

		val barrierResult = try {
			runtime.establishCaptureDeletionBarrier(request.expectedCollectedDataEpoch)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("SwallowedException") _: SQLiteException) {
			return CellCaptureConsentRevocationDeletionResult.Retryable(
				CellCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (val barrier = barrierResult) {
			CellCaptureDeletionBarrierResult.NoLocalProvider -> Unit
			is CellCaptureDeletionBarrierResult.Established -> Unit
			is CellCaptureDeletionBarrierResult.Retryable ->
				return CellCaptureConsentRevocationDeletionResult.Retryable(
					when (barrier.reason) {
						CellCaptureDeletionBarrierRetryableReason.BARRIER_PUBLICATION_FAILED ->
							CellCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE
						CellCaptureDeletionBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT,
						CellCaptureDeletionBarrierRetryableReason.CALLBACK_LANE_UNAVAILABLE,
						-> CellCaptureConsentRevocationDeletionRetryableReason.CALLBACK_BARRIER_UNAVAILABLE
					},
				)
			is CellCaptureDeletionBarrierResult.Blocked ->
				return CellCaptureConsentRevocationDeletionResult.Blocked(
					barrier.reason.toDeletionBlockedReason(),
				)
		}

		val result = try {
			database.deleteCapturedCellFactsAfterConsentReset(
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				expectedDeletedSourceEventHighWaterOrdinal =
					request.expectedDeletedSourceEventHighWaterOrdinal,
				expectedRevokedConsentEpoch = request.expectedRevokedConsentEpoch,
				deletedAtMs = request.deletedAtMs,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("SwallowedException") _: SQLiteException) {
			return CellCaptureConsentRevocationDeletionResult.Retryable(
				CellCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		return when (result) {
			is CellCapturedSourceDeletionResult.Deleted ->
				CellCaptureConsentRevocationDeletionResult.Deleted(
					result.logicalFactCount,
					result.revisionCount,
					result.fencedServiceRunCount,
				)
			CellCapturedSourceDeletionResult.AlreadyDeleted ->
				CellCaptureConsentRevocationDeletionResult.AlreadyDeleted
			is CellCapturedSourceDeletionResult.Blocked ->
				CellCaptureConsentRevocationDeletionResult.Blocked(result.reason)
		}
	}

	/** Null means the exact current authority proves that capture is revoked. */
	private suspend fun preflight(
		request: CellCaptureConsentRevocationDeletionRequest,
	): CellCapturedSourceDeletionBlockedReason? = database.withTransaction {
		val evidence = database.sourceEvidenceStateDao().get()
		if (evidence == null ||
			evidence.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			evidence.deletedSourceEventHighWaterOrdinal !=
				request.expectedDeletedSourceEventHighWaterOrdinal
		) return@withTransaction
			CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED

		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()?.takeIf { current ->
			current.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		} ?: return@withTransaction CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, CELL_SOURCE)
		val consent = policyDao.latestConsentEpoch(CELL_SOURCE, CAPTURE_PURPOSE)
		if (policy == null || consent == null ||
			consent.epoch != request.expectedRevokedConsentEpoch
		) return@withTransaction CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE
		if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
			consent.eligible || consent.persistenceEligible ||
			consent.policyRevision != policy.policyRevision ||
			consent.effectiveBootId != policy.effectiveBootId ||
			consent.effectiveElapsedRealtimeNanos != policy.effectiveElapsedRealtimeNanos ||
			consent.effectiveWallTimeMs != policy.effectiveWallTimeMs
		) return@withTransaction CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE
		if (request.deletedAtMs < maxOf(
				authority.updatedAtMs,
				policy.effectiveWallTimeMs,
				consent.effectiveWallTimeMs,
				evidence.updatedAtMs,
			)
		) return@withTransaction CellCapturedSourceDeletionBlockedReason.STALE_REQUEST
		null
	}
}

private fun CellCaptureDeletionBarrierBlockedReason.toDeletionBlockedReason() = when (this) {
	CellCaptureDeletionBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
	CellCaptureDeletionBarrierBlockedReason.STALE_REGISTRATION,
	-> CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED
	CellCaptureDeletionBarrierBlockedReason.STALE_LIFECYCLE ->
		CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED
	CellCaptureDeletionBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE ->
		CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
}

private const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
