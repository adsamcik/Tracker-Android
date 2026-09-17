package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.runtime.SessionDemandMutation
import com.adsamcik.tracker.tracker.source.runtime.RoomSourceCallerAcceptedAuthorityRepository
import com.adsamcik.tracker.tracker.source.runtime.SessionSourceDemandDispatchRequest
import com.adsamcik.tracker.tracker.source.runtime.SessionSourceDemandDispatchResult
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import com.adsamcik.tracker.tracker.source.runtime.StoredSourceCallerAuthority
import com.adsamcik.tracker.tracker.source.runtime.StoredSourceCallerAuthorityLoadResult
import com.adsamcik.tracker.tracker.source.runtime.StoredSourceCallerOrigin
import com.adsamcik.tracker.tracker.source.runtime.TestPurposeSourceCallerDemandDispatcher

internal class FakeSourceCallerDemandDispatcher(
	database: AppDatabase,
	private val broker: SourceBroker,
) : SourceCallerDemandDispatcher by TestPurposeSourceCallerDemandDispatcher(broker) {
	private val repository = RoomSourceCallerAcceptedAuthorityRepository(database)
	override suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult {
		val manifestIdentity = SourceCallerManifestIdentity(
			request.manifest.logicalTrackingId,
			request.manifest.manifestRevision,
		)
		val identities = request.bindings.mapTo(linkedSetOf()) { binding ->
			val source = TrackingSource.fromStableCode(binding.sourceKind)
			val purpose = when (binding.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> TrackingPurpose.SESSION_CAPTURE
				"CONTROL" -> TrackingPurpose.CONTROL
				else -> error("Unsupported test binding purpose ${binding.purpose}")
			}
			SourceCallerDemandIdentity(
				purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
					sourcePurpose = source.forPurpose(purpose),
					policyRevision = request.manifest.sourcePolicyRevision,
					consentEpoch = binding.consentEpoch,
					collectedDataEpoch = 0L,
					rolloutRevision = request.manifest.rolloutRevision,
					executionRevision = request.lifecycleLeaseGeneration,
					ownerCasToken = "test-source-caller",
				),
				manifestIdentity = manifestIdentity.takeIf {
					purpose == TrackingPurpose.SESSION_CAPTURE
				},
			)
		}
		val receipt = SourceCallerAcceptanceReceipt(
			reference = SourceCallerReplayReference(
				"test:${request.manifest.logicalTrackingId}:${request.manifest.manifestRevision}",
			),
			permittedDemandIdentities = identities,
		)
		check(repository.storeIfAbsent(
			receipt.reference,
			StoredSourceCallerAuthority(
				origin = when {
					request.startOrigin == SessionStartOrigin.RECOVERY ->
						StoredSourceCallerOrigin.RECOVERY
					request.sessionMode == SessionMode.MANUAL -> StoredSourceCallerOrigin.MANUAL
					request.sessionMode == SessionMode.AUTOMATIC -> StoredSourceCallerOrigin.AUTOMATIC
					else -> error("Unsupported legacy test session")
				},
				purpose = TrackingPurpose.SESSION_CAPTURE,
				permittedDemandIdentities = identities,
			),
			request.wallTimeMs,
		))
		val demands = broker.buildSessionDemands(
			logicalTrackingId = request.manifest.logicalTrackingId,
			serviceRunId = request.manifest.serviceRunId,
			manifestRevision = request.manifest.manifestRevision,
			lifecycleLeaseGeneration = request.lifecycleLeaseGeneration,
			policyRevision = request.manifest.sourcePolicyRevision,
			bindings = request.bindings,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
			sourceCallerAuthorityReference = receipt.reference.value,
		)
		when (request.mutation) {
			SessionDemandMutation.STAGE_UNTIL_FOREGROUND ->
				broker.stageSessionDemandsInTransaction(
					request.manifest.logicalTrackingId,
					demands,
					request.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
			SessionDemandMutation.REPLACE_ACTIVE ->
				broker.replaceSessionDemandsInTransaction(
					request.manifest.logicalTrackingId,
					demands,
					request.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
		}
		return SessionSourceDemandDispatchResult.Permitted(receipt)
	}

	override suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult = when (val loaded = repository.load(reference)) {
		is StoredSourceCallerAuthorityLoadResult.Available ->
			SourceCallerGuardResult.Permitted(
				SourceCallerAcceptanceReceipt(reference, loaded.authority.permittedDemandIdentities),
			)
		StoredSourceCallerAuthorityLoadResult.Missing,
		StoredSourceCallerAuthorityLoadResult.Corrupt,
		StoredSourceCallerAuthorityLoadResult.Tombstoned,
		-> SourceCallerGuardResult.Rejected(
			SourceCallerGuardRejection(SourceCallerRejectionReason.REPLAY_AUTHORITY_UNAVAILABLE),
		)
	}

	override suspend fun permitsActivation(
		reference: SourceCallerReplayReference,
		manifestIdentity: SourceCallerManifestIdentity,
		demands: List<SourceDemandEntity>,
	): Boolean = repository.load(reference) is StoredSourceCallerAuthorityLoadResult.Available &&
		demands.isNotEmpty() &&
		demands.all {
			it.sourceCallerAuthorityReference == reference.value &&
				it.logicalTrackingId == manifestIdentity.logicalTrackingId &&
				it.manifestRevision == manifestIdentity.manifestRevision
		}
}
