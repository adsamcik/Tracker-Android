package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.tracker.source.runtime.WifiCaptureDeletionBarrierBlockedReason
import com.adsamcik.tracker.tracker.source.runtime.WifiCaptureDeletionBarrierResult
import com.adsamcik.tracker.tracker.source.runtime.WifiCaptureDeletionBarrierRetryableReason
import com.adsamcik.tracker.tracker.source.runtime.WifiSourceRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class WifiCaptureConsentRevocationDeletionRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedDeletedSourceEventHighWaterOrdinal: Long,
	val expectedCurrentPolicyRevision: Long,
	val expectedRevokedConsentEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
		require(expectedCurrentPolicyRevision > 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(deletedAtMs >= 0L)
	}
}

internal enum class WifiCaptureConsentRevocationDeletionRetryableReason {
	CALLBACK_BARRIER_UNAVAILABLE,
	STORAGE_UNAVAILABLE,
}

internal sealed interface WifiCaptureConsentRevocationDeletionResult {
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : WifiCaptureConsentRevocationDeletionResult

	data object AlreadyDeleted : WifiCaptureConsentRevocationDeletionResult

	data class Blocked(
		val reason: WifiCapturedSourceDeletionBlockedReason,
	) : WifiCaptureConsentRevocationDeletionResult

	data class Retryable(
		val reason: WifiCaptureConsentRevocationDeletionRetryableReason,
	) : WifiCaptureConsentRevocationDeletionResult
}

/**
 * Explicit Wi-Fi session-capture consent cleanup. It neither changes policy nor acquisition. The
 * already-running source owner first settles its callback FIFO and publishes the durable barrier;
 * independently authorized CONTROL or AMBIENT_PRODUCT operation remains eligible to continue.
 */
@Singleton
internal class WifiCaptureConsentRevocationDeletionCommand @Inject constructor(
	private val database: AppDatabase,
	private val runtime: WifiSourceRuntime,
	private val maintenance: WifiCapturedFactMaintenance,
) {
	suspend fun delete(
		request: WifiCaptureConsentRevocationDeletionRequest,
	): WifiCaptureConsentRevocationDeletionResult {
		val preflight = try {
			preflight(request)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("SwallowedException") _: SQLiteException) {
			return WifiCaptureConsentRevocationDeletionResult.Retryable(
				WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
			return WifiCaptureConsentRevocationDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		}
		if (preflight != null) {
			return WifiCaptureConsentRevocationDeletionResult.Blocked(preflight)
		}

		val barrierResult = try {
			runtime.establishCaptureDeletionBarrier(request.expectedCollectedDataEpoch)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("SwallowedException") _: SQLiteException) {
			return WifiCaptureConsentRevocationDeletionResult.Retryable(
				WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		when (val barrier = barrierResult) {
			WifiCaptureDeletionBarrierResult.NoLocalProvider -> Unit
			is WifiCaptureDeletionBarrierResult.Established -> Unit
			is WifiCaptureDeletionBarrierResult.Retryable ->
				return WifiCaptureConsentRevocationDeletionResult.Retryable(
					when (barrier.reason) {
						WifiCaptureDeletionBarrierRetryableReason.BARRIER_PUBLICATION_FAILED ->
							WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE
						WifiCaptureDeletionBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT,
						WifiCaptureDeletionBarrierRetryableReason.CALLBACK_LANE_UNAVAILABLE,
						-> WifiCaptureConsentRevocationDeletionRetryableReason.CALLBACK_BARRIER_UNAVAILABLE
					},
				)
			is WifiCaptureDeletionBarrierResult.Blocked ->
				return WifiCaptureConsentRevocationDeletionResult.Blocked(
					barrier.reason.toDeletionBlockedReason(),
				)
		}

		val result = try {
			maintenance.deleteAfterCaptureConsentReset(
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				expectedDeletedSourceEventHighWaterOrdinal =
					request.expectedDeletedSourceEventHighWaterOrdinal,
				expectedCurrentPolicyRevision = request.expectedCurrentPolicyRevision,
				expectedRevokedConsentEpoch = request.expectedRevokedConsentEpoch,
				deletedAtMs = request.deletedAtMs,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("SwallowedException") _: SQLiteException) {
			return WifiCaptureConsentRevocationDeletionResult.Retryable(
				WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		return when (result) {
			is WifiCapturedSourceDeletionResult.Deleted ->
				WifiCaptureConsentRevocationDeletionResult.Deleted(
					result.logicalFactCount,
					result.revisionCount,
					result.fencedServiceRunCount,
				)
			WifiCapturedSourceDeletionResult.AlreadyDeleted ->
				WifiCaptureConsentRevocationDeletionResult.AlreadyDeleted
			is WifiCapturedSourceDeletionResult.Blocked ->
				WifiCaptureConsentRevocationDeletionResult.Blocked(result.reason)
		}
	}

	/** Null means exact current authority proves that Wi-Fi session capture is revoked. */
	private suspend fun preflight(
		request: WifiCaptureConsentRevocationDeletionRequest,
	): WifiCapturedSourceDeletionBlockedReason? = database.withTransaction {
		val evidence = database.sourceEvidenceStateDao().get()
		if (evidence == null ||
			evidence.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			evidence.deletedSourceEventHighWaterOrdinal !=
				request.expectedDeletedSourceEventHighWaterOrdinal
		) return@withTransaction
			WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED

		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()?.takeIf { current ->
			current.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				current.currentPolicyRevision == request.expectedCurrentPolicyRevision
		} ?: return@withTransaction WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE
		val policy = policyDao.policyAtRevision(request.expectedCurrentPolicyRevision, WIFI_SOURCE)
		val consent = policyDao.latestConsentEpoch(WIFI_SOURCE, CAPTURE_PURPOSE)
		if (policy == null || consent == null ||
			consent.epoch != request.expectedRevokedConsentEpoch
		) return@withTransaction WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE
		if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
			consent.eligible || consent.persistenceEligible ||
			consent.policyRevision > policy.policyRevision
		) return@withTransaction WifiCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE
		// Generic evidence time also advances for independent CONTROL/AMBIENT WAL. The exact
		// epoch/high-water checks above fence lifecycle authority without making those sources block.
		if (request.deletedAtMs < maxOf(
			authority.updatedAtMs,
			policy.effectiveWallTimeMs,
			consent.effectiveWallTimeMs,
		)
		) return@withTransaction WifiCapturedSourceDeletionBlockedReason.STALE_REQUEST
		null
	}
}

private fun WifiCaptureDeletionBarrierBlockedReason.toDeletionBlockedReason() = when (this) {
	WifiCaptureDeletionBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
	WifiCaptureDeletionBarrierBlockedReason.STALE_REGISTRATION,
	-> WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED
	WifiCaptureDeletionBarrierBlockedReason.STALE_LIFECYCLE ->
		WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED
	WifiCaptureDeletionBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE ->
		WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
}

private const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
