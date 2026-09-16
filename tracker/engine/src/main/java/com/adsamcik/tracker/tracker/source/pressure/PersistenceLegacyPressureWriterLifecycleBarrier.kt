package com.adsamcik.tracker.tracker.source.pressure

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseSettlement
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseVerification
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PersistenceLegacyPressureWriterLifecycleBarrier @Inject constructor(
	private val database: AppDatabase,
	private val persistenceProcessor: PersistenceProcessor,
	private val persistenceLifecycleLease: ExclusiveTrackingPersistenceLifecycleLease,
) : LegacyPressureWriterLifecycleBarrier {
	override suspend fun establish(
		expectedCollectedDataEpoch: Long,
		settleProvider: suspend () -> PressureProviderEraseSettlement,
	): PressureSourceEraseBarrierResult =
		persistenceLifecycleLease.withPressureWriterTransition {
			if (!hasExactEpoch(expectedCollectedDataEpoch)) {
				return@withPressureWriterTransition blockedStaleLifecycle()
			}
			if (hasActivePressurePersistenceDemand()) {
				return@withPressureWriterTransition blockedCaptureAuthorization()
			}
			val providerSettlement = settleProvider()
			if (providerSettlement != PressureProviderEraseSettlement.NoLocalProvider &&
				providerSettlement !is PressureProviderEraseSettlement.Settled
			) {
				return@withPressureWriterTransition providerSettlement.toBarrierResult(
					expectedCollectedDataEpoch,
					SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
				)
			}
			if (persistenceProcessor.isPipelineActiveForPersistenceLifecycle() ||
				!persistenceProcessor.drainOrphanedSignals()
			) {
				return@withPressureWriterTransition PressureSourceEraseBarrierResult.Retryable(
					PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
				)
			}
			val fenceGeneration = when (val fence = database.withTransaction {
				when {
					!hasExactEpoch(expectedCollectedDataEpoch) ->
						PressureFenceInstallResult.StaleLifecycle
					hasActivePressurePersistenceDemand() ->
						PressureFenceInstallResult.CaptureAuthorizationActive
					else -> installOrReadPressureFence()?.let { generation ->
						PressureFenceInstallResult.Installed(generation)
					} ?: PressureFenceInstallResult.StaleLifecycle
				}
			}) {
				is PressureFenceInstallResult.Installed -> fence.generation
				PressureFenceInstallResult.CaptureAuthorizationActive ->
					return@withPressureWriterTransition blockedCaptureAuthorization()
				PressureFenceInstallResult.StaleLifecycle ->
					return@withPressureWriterTransition blockedStaleLifecycle()
			}
			providerSettlement.toBarrierResult(
				expectedCollectedDataEpoch,
				fenceGeneration,
			)
		}

	override suspend fun verifySettled(
		token: PressureSourceEraseBarrierToken,
		verifyProvider: suspend () -> PressureProviderEraseVerification,
	): PressureSourceEraseBarrierVerification {
		if (!hasExactEpoch(token.collectedDataEpoch)) return blockedStaleVerification()
		if (hasActivePressurePersistenceDemand()) return blockedCaptureVerification()
		if (!hasExactPressureFence(token.legacyWriteFenceGeneration)) {
			return blockedStaleVerification()
		}
		val provider = verifyProvider().toBarrierVerification()
		if (provider != PressureSourceEraseBarrierVerification.Verified) return provider
		if (!hasExactEpoch(token.collectedDataEpoch) ||
			hasActivePressurePersistenceDemand() ||
			!hasExactPressureFence(token.legacyWriteFenceGeneration)
		) {
			return blockedStaleVerification()
		}
		return PressureSourceEraseBarrierVerification.Verified
	}

	private suspend fun hasExactEpoch(expectedCollectedDataEpoch: Long): Boolean =
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch == expectedCollectedDataEpoch

	private suspend fun hasActivePressurePersistenceDemand(): Boolean =
		database.sourceBrokerDao().activeDemands(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		).any { demand ->
			demand.persistenceEligible ||
				demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}

	private suspend fun installOrReadPressureFence(): Long? {
		val dao = database.sourceDestinationOwnerDao()
		val current = dao.get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)
		if (current == null) {
			dao.insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					ownerGeneration =
						SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
					updatedAtMs = Time.nowMillis,
				),
			)
			return dao.get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			)?.takeIf { it.isPressureFence() }?.ownerGeneration
		}
		if (current.isPressureFence()) return current.ownerGeneration
		if (current.owner != SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE ||
			current.ownerGeneration != SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		) {
			return null
		}
		val nextGeneration = SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
		if (dao.compareAndSetOwner(
				sourceKind = current.sourceKind,
				destination = current.destination,
				expectedOwner = current.owner,
				expectedOwnerGeneration = current.ownerGeneration,
				newOwner = current.owner,
				newOwnerGeneration = nextGeneration,
				updatedAtMs = Time.nowMillis,
			) != 1) {
			return null
		}
		return nextGeneration
	}

	private suspend fun hasExactPressureFence(expectedGeneration: Long): Boolean =
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.let { owner ->
			owner.ownerGeneration == expectedGeneration && owner.isPressureFence()
		} == true

	private fun SourceDestinationOwnerEntity.isPressureFence(): Boolean =
		when (owner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE ->
				ownerGeneration >=
					SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ->
				ownerGeneration >= SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			else -> false
		}

	private fun blockedStaleLifecycle() = PressureSourceEraseBarrierResult.Blocked(
		PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
	)

	private fun blockedCaptureAuthorization() = PressureSourceEraseBarrierResult.Blocked(
		PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
	)

	private fun blockedStaleVerification() = PressureSourceEraseBarrierVerification.Blocked(
		PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
	)

	private fun blockedCaptureVerification() = PressureSourceEraseBarrierVerification.Blocked(
		PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
	)

	private sealed interface PressureFenceInstallResult {
		data class Installed(val generation: Long) : PressureFenceInstallResult
		data object CaptureAuthorizationActive : PressureFenceInstallResult
		data object StaleLifecycle : PressureFenceInstallResult
	}
}
