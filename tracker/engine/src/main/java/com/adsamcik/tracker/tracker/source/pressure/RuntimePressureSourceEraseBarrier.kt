package com.adsamcik.tracker.tracker.source.pressure

import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrier
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseFenceOwner
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseSettlement
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseVerification
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime-only bridge; source erase authority remains owned by the Pressure maintenance service. */
@Singleton
internal class RuntimePressureSourceEraseBarrier @Inject constructor(
	private val runtime: PressureSourceRuntime,
	private val legacyWriterBarrier: LegacyPressureWriterLifecycleBarrier,
) : PressureSourceEraseBarrier {
	override suspend fun establish(
		expectedCollectedDataEpoch: Long,
	): PressureSourceEraseBarrierResult = legacyWriterBarrier.establish(
		expectedCollectedDataEpoch = expectedCollectedDataEpoch,
		settleProvider = {
			runtime.establishSourceEraseBarrier(expectedCollectedDataEpoch)
		},
	)

	override suspend fun verifySettled(
		token: PressureSourceEraseBarrierToken,
	): PressureSourceEraseBarrierVerification = legacyWriterBarrier.verifySettled(
		token = token,
		verifyProvider = {
			runtime.verifySourceEraseProviderSettled(
				expectedCollectedDataEpoch = token.collectedDataEpoch,
				expectedRegistrationGeneration = token.providerRegistrationGeneration,
			)
		},
	)
}

/**
 * Shared lifecycle contract to be implemented by the PersistenceProcessor owner.
 *
 * Establishment must hold the same lifecycle lease used by live pipeline start/stop, pending-signal
 * recovery, and offline Location writing. It must settle [settleProvider], drain or quarantine every
 * legacy Pressure component, publish a positive durable fence generation, and return a token only
 * while direct Pressure demand remains revoked. [verifySettled] is called inside the erase Room
 * transaction and must reauthenticate that exact fence generation before invoking [verifyProvider].
 */
internal interface LegacyPressureWriterLifecycleBarrier {
	suspend fun establish(
		expectedCollectedDataEpoch: Long,
		settleProvider: suspend () -> PressureProviderEraseSettlement,
	): PressureSourceEraseBarrierResult

	suspend fun verifySettled(
		token: PressureSourceEraseBarrierToken,
		verifyProvider: suspend () -> PressureProviderEraseVerification,
	): PressureSourceEraseBarrierVerification
}

@Singleton
internal class UnavailableLegacyPressureWriterLifecycleBarrier @Inject constructor() :
	LegacyPressureWriterLifecycleBarrier {
	override suspend fun establish(
		expectedCollectedDataEpoch: Long,
		settleProvider: suspend () -> PressureProviderEraseSettlement,
	): PressureSourceEraseBarrierResult = PressureSourceEraseBarrierResult.Retryable(
		PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
	)

	override suspend fun verifySettled(
		token: PressureSourceEraseBarrierToken,
		verifyProvider: suspend () -> PressureProviderEraseVerification,
	): PressureSourceEraseBarrierVerification = PressureSourceEraseBarrierVerification.Retryable(
		PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
	)
}

internal fun PressureProviderEraseSettlement.toBarrierResult(
	collectedDataEpoch: Long,
	legacyWriteFenceOwner: PressureSourceEraseFenceOwner,
	legacyWriteFenceGeneration: Long,
): PressureSourceEraseBarrierResult = when (this) {
	PressureProviderEraseSettlement.NoLocalProvider -> PressureSourceEraseBarrierResult.NoLocalProvider(
		PressureSourceEraseBarrierToken(
			collectedDataEpoch = collectedDataEpoch,
			providerRegistrationGeneration = null,
			legacyWriteFenceOwner = legacyWriteFenceOwner,
			legacyWriteFenceGeneration = legacyWriteFenceGeneration,
		),
	)
	is PressureProviderEraseSettlement.Settled -> PressureSourceEraseBarrierResult.Established(
		PressureSourceEraseBarrierToken(
			collectedDataEpoch = collectedDataEpoch,
			providerRegistrationGeneration = registrationGeneration,
			legacyWriteFenceOwner = legacyWriteFenceOwner,
			legacyWriteFenceGeneration = legacyWriteFenceGeneration,
		),
	)
	PressureProviderEraseSettlement.CaptureAuthorizationActive ->
		PressureSourceEraseBarrierResult.Blocked(
			PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
		)
	PressureProviderEraseSettlement.StaleLifecycle -> PressureSourceEraseBarrierResult.Blocked(
		PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
	)
	PressureProviderEraseSettlement.CallbackDrainTimedOut -> PressureSourceEraseBarrierResult.Retryable(
		PressureSourceEraseBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT,
	)
	PressureProviderEraseSettlement.ProviderRemovalFailed -> PressureSourceEraseBarrierResult.Retryable(
		PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
	)
}

internal fun PressureProviderEraseVerification.toBarrierVerification():
	PressureSourceEraseBarrierVerification = when (this) {
	PressureProviderEraseVerification.Verified -> PressureSourceEraseBarrierVerification.Verified
	PressureProviderEraseVerification.CaptureAuthorizationActive ->
	PressureSourceEraseBarrierVerification.Blocked(
		PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
	)
	PressureProviderEraseVerification.StaleLifecycle -> PressureSourceEraseBarrierVerification.Blocked(
		PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
	)
	PressureProviderEraseVerification.ProviderRemovalFailed ->
		PressureSourceEraseBarrierVerification.Retryable(
			PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
		)
}
