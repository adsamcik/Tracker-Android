package com.adsamcik.tracker.tracker.api

import android.content.Context
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.service.manualTrackingSourceCapabilities
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Current manual-start preflight; service preparation repeats all authority checks before commit. */
@Singleton
class DefaultManualTrackingStartReadinessReader @Inject constructor(
	@ApplicationContext private val context: Context,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val rolloutStateStoreProvider: Provider<TrackingRolloutStateStore>,
	private val sourcePolicyRepositoryProvider: Provider<SourcePolicyRepository>,
) : ManualTrackingStartReadinessReader {
	override suspend fun read(): ManualTrackingStartReadiness {
		val startupGate = startupGateProvider.get()
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			val rollout = rolloutStateStoreProvider.get().load()
			val policyState = sourcePolicyRepositoryProvider.get().currentState()
			if (policyState !is SourcePolicyAuthorityState.Active) {
				return@withReadyGenerationOperation ManualTrackingStartReadiness.TrackingUnavailable
			}
			val enabledSources = policyState.snapshot.policies.values
				.filter { policy -> policy.enabled && policy.capturePersistenceEligible }
				.mapTo(linkedSetOf()) { policy -> policy.source.toSourceKind() }
			val reachableSources = SourceKind.entries.filterTo(linkedSetOf()) { source ->
				rollout.isCaptureReachable(
					source,
					CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
				)
			}
			val capabilities = context.manualTrackingSourceCapabilities()
			resolveManualTrackingStartReadiness(
				rolloutRevision = rollout.revision,
				enabledSources = enabledSources.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				reachableSources = reachableSources.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				supportedSources = capabilities.supportedSources
					.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				availableSources = capabilities.availableSources
					.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				sourcesMissingPreciseLocationPermission =
					capabilities.sourcesMissingPreciseLocationPermission
						.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				sourcesMissingActivityRecognitionPermission =
					capabilities.sourcesMissingActivityRecognitionPermission
						.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				sourcesMissingReadPhoneStatePermission =
					capabilities.sourcesMissingReadPhoneStatePermission
						.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
				sourcesBlockedByLocationServices = capabilities.sourcesBlockedByLocationServices
					.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
			)
		} ?: ManualTrackingStartReadiness.TrackingUnavailable
	}
}

private fun TrackingSourceComponent.toSourceKind(): SourceKind = when (this) {
	TrackingSourceComponent.LOCATION -> SourceKind.LOCATION
	TrackingSourceComponent.ACTIVITY -> SourceKind.ACTIVITY
	TrackingSourceComponent.STEPS -> SourceKind.STEPS
	TrackingSourceComponent.PRESSURE -> SourceKind.PRESSURE
	TrackingSourceComponent.WIFI -> SourceKind.WIFI
	TrackingSourceComponent.CELL -> SourceKind.CELL
}
