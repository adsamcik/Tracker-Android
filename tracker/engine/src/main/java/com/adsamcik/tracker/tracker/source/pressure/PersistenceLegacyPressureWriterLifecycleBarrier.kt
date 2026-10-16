package com.adsamcik.tracker.tracker.source.pressure

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseFenceOwner
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.coordinator.CONTAINED_PRESSURE_SESSION_OWNER
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ProductProjectionStage
import com.adsamcik.tracker.tracker.source.coordinator.SourceOwner
import com.adsamcik.tracker.tracker.source.coordinator.decodeCurrentModelOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
			preparePressureFence(expectedCollectedDataEpoch)?.let { return@withPressureWriterTransition it }
			val providerSettlement = settleProvider()
			if (providerSettlement != PressureProviderEraseSettlement.NoLocalProvider &&
				providerSettlement !is PressureProviderEraseSettlement.Settled
			) {
				return@withPressureWriterTransition providerSettlement.toBarrierResult(
					expectedCollectedDataEpoch,
					PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
					SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
				)
			}
			if (!flushPressurePersistence()) {
				return@withPressureWriterTransition PressureSourceEraseBarrierResult.Retryable(
					PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
				)
			}
			val fenceIdentity = when (val fence = installPressureFence(expectedCollectedDataEpoch)) {
				is PressureFenceInstallResult.Installed -> fence.identity
				PressureFenceInstallResult.CaptureAuthorizationActive ->
					return@withPressureWriterTransition blockedCaptureAuthorization()
				PressureFenceInstallResult.StaleLifecycle ->
					return@withPressureWriterTransition blockedStaleLifecycle()
			}
			providerSettlement.toBarrierResult(
				expectedCollectedDataEpoch,
				fenceIdentity.owner,
				fenceIdentity.generation,
			)
		}

	private suspend fun preparePressureFence(expectedCollectedDataEpoch: Long): PressureSourceEraseBarrierResult? =
		when {
			!hasExactEpoch(expectedCollectedDataEpoch) -> blockedStaleLifecycle()
			hasActivePressurePersistenceDemand() -> blockedCaptureAuthorization()
			!hasEstablishablePressureOwner() -> blockedStaleLifecycle()
			else -> null
		}

	private suspend fun flushPressurePersistence(): Boolean =
		!persistenceProcessor.hasUnrecoverablePersistenceStateForLifecycleFence() &&
			persistenceProcessor.drainOrphanedSignals() &&
			!persistenceProcessor.hasUnsettledPersistenceStateForPressureFence()

	private suspend fun installPressureFence(expectedCollectedDataEpoch: Long): PressureFenceInstallResult =
		database.withTransaction {
			when {
				!hasExactEpoch(expectedCollectedDataEpoch) -> PressureFenceInstallResult.StaleLifecycle
				hasActivePressurePersistenceDemand() -> PressureFenceInstallResult.CaptureAuthorizationActive
				database.pendingSignalDao().hasPressureWriterCommand() -> PressureFenceInstallResult.StaleLifecycle
				else -> installOrReadPressureFence()?.let { identity ->
					PressureFenceInstallResult.Installed(identity)
				}
					?: PressureFenceInstallResult.StaleLifecycle
			}
		}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	override suspend fun verifySettled(
		token: PressureSourceEraseBarrierToken,
		verifyProvider: suspend () -> PressureProviderEraseVerification,
	): PressureSourceEraseBarrierVerification {
		if (persistenceProcessor.hasUnsettledPersistenceStateForPressureFence()) {
			return PressureSourceEraseBarrierVerification.Retryable(
				PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
			)
		}
		if (!hasExactEpoch(token.collectedDataEpoch)) {
			return blockedStaleVerification()
		}
		if (hasActivePressurePersistenceDemand()) {
			return blockedCaptureVerification()
		}
		if (!hasExactPressureFence(
				token.legacyWriteFenceOwner,
				token.legacyWriteFenceGeneration,
			)
		) {
			return blockedStaleVerification()
		}
		val provider = verifyProvider().toBarrierVerification()
		if (provider != PressureSourceEraseBarrierVerification.Verified) {
			return provider
		}
		if (!hasExactEpoch(token.collectedDataEpoch) ||
			hasActivePressurePersistenceDemand() ||
			!hasExactPressureFence(
				token.legacyWriteFenceOwner,
				token.legacyWriteFenceGeneration,
			)
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

	private suspend fun hasEstablishablePressureOwner(): Boolean {
		val current = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		) ?: return true
		return when (current.owner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE -> true
			CONTAINED_PRESSURE_SESSION_OWNER -> current.toPressureFenceIdentity() != null
			else -> false
		}
	}

	private suspend fun installOrReadPressureFence(): PressureFenceIdentity? {
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
			)?.toPressureFenceIdentity()
		}
		current.toPressureFenceIdentity()?.let { return it }
		if (current.owner != SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE ||
			current.ownerGeneration != SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		) {
			return null
		}
		val nextGeneration = SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
		return if (dao.compareAndSetOwner(
				sourceKind = current.sourceKind,
				destination = current.destination,
				expectedOwner = current.owner,
				expectedOwnerGeneration = current.ownerGeneration,
				newOwner = current.owner,
				newOwnerGeneration = nextGeneration,
				updatedAtMs = Time.nowMillis,
			) == 1) {
			PressureFenceIdentity(PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE, nextGeneration)
		} else {
			null
		}
	}

	private suspend fun hasExactPressureFence(
		expectedOwner: PressureSourceEraseFenceOwner,
		expectedGeneration: Long,
	): Boolean {
		if (database.pendingSignalDao().hasPressureWriterCommand()) {
			return false
		}
		return database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.toPressureFenceIdentity() == PressureFenceIdentity(
			expectedOwner,
			expectedGeneration,
		)
	}

	private suspend fun SourceDestinationOwnerEntity.toPressureFenceIdentity():
		PressureFenceIdentity? {
		return when (owner) {
		SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE -> {
			ownerGeneration.takeIf {
				it >= SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
			}?.let { generation ->
				PressureFenceIdentity(
					PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
					generation,
				)
			}
		}
		CONTAINED_PRESSURE_SESSION_OWNER -> {
			val bindingGeneration =
				SourceWriterGenerationContract.bindingGenerationForContainedOwner(ownerGeneration)
					?: return null
			if (hasContainedPressureFence(bindingGeneration)) {
				PressureFenceIdentity(
					PressureSourceEraseFenceOwner.CONTAINED_PRESSURE_SESSION_FACTS,
					ownerGeneration,
				)
			} else {
				null
			}
		}
		else -> null
		}
	}

	private suspend fun hasContainedPressureFence(bindingGeneration: Long): Boolean {
		val binding = ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS
		if (database.hasActivePressureFenceOwners()) {
			return false
		}
		val rollout = database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull() ?: return false
		if (rollout.sourceOwners[SourceKind.PRESSURE] != SourceOwner.CONTAINED ||
			rollout.productProjectionStages[SourceKind.PRESSURE] != ProductProjectionStage.LEGACY_CANONICAL ||
			rollout.captureModeMasks[SourceKind.PRESSURE] != 0L
		) {
			return false
		}
		val retired = database.sourceProjectionStateDao().latestProductLane(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		)
		return retired != null && retired.matchesPressureFenceBinding(bindingGeneration, binding) &&
			retired.hasContainedPressureDisposition(rollout.revision) && retired.hasCompletedPressureDrain()
	}

	private data class PressureFenceIdentity(
		val owner: PressureSourceEraseFenceOwner,
		val generation: Long,
	) {
		init {
			require(generation > 0L)
		}
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
		data class Installed(val identity: PressureFenceIdentity) : PressureFenceInstallResult
		data object CaptureAuthorizationActive : PressureFenceInstallResult
		data object StaleLifecycle : PressureFenceInstallResult
	}
}

private suspend fun AppDatabase.hasActivePressureFenceOwners(): Boolean {
	val projectionDao = sourceProjectionStateDao()
	return projectionDao.allActiveProductLanes().any {
		it.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE
	} || projectionDao.registration(
		SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
	) != null || sourceBrokerDao().currentPhysicalRegistrations(
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
	).isNotEmpty() || sourceBrokerDao().pendingProviderRemovals(
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
	).isNotEmpty()
}

private fun SourceProductProjectionLaneEntity.matchesPressureFenceBinding(
	bindingGeneration: Long,
	binding: ExecutableSourceLaneBinding,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
	bindingGeneration == this.bindingGeneration && projectionId == binding.projectionId &&
	projectionVersion == binding.projectionVersion && captureModeMask == binding.captureModeMask &&
	productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL

private fun SourceProductProjectionLaneEntity.hasContainedPressureDisposition(rolloutRevision: Long): Boolean =
	activatedRolloutRevision > 0L && activatedRolloutRevision <= rolloutRevision && activationOrdinal > 0L &&
		status == SourceProductProjectionLaneEntity.STATUS_RETIRED && !retentionRequired &&
		terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN

private fun SourceProductProjectionLaneEntity.hasCompletedPressureDrain(): Boolean {
	val terminalAt = terminalAtMs ?: return false
	val cutoff = captureAdmissionCutoffOrdinal ?: return false
	return cutoff >= activationOrdinal - 1L && contiguousAdmissionOrdinal == cutoff && installedAtMs >= 0L &&
		updatedAtMs >= installedAtMs && terminalAt >= installedAtMs && updatedAtMs >= terminalAt
}
