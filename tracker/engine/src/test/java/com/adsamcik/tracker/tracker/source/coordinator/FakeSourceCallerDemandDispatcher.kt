package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.runtime.SessionDemandMutation
import com.adsamcik.tracker.tracker.source.runtime.SessionSourceDemandDispatchRequest
import com.adsamcik.tracker.tracker.source.runtime.SessionSourceDemandDispatchResult
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher

internal class FakeSourceCallerDemandDispatcher(
	private val broker: SourceBroker,
) : SourceCallerDemandDispatcher {
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
			reference = request.replayReference ?: SourceCallerReplayReference(
				"test:${request.manifest.logicalTrackingId}:${request.manifest.manifestRevision}",
			),
			permittedDemandIdentities = identities,
		)
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
	): SourceCallerGuardResult = SourceCallerGuardResult.Permitted(
		SourceCallerAcceptanceReceipt(reference, emptySet()),
	)
}
