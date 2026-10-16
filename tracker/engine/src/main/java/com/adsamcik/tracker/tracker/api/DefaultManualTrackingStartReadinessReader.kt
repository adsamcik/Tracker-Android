package com.adsamcik.tracker.tracker.api

import android.content.Context
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.service.manualTrackingSourceCapabilities
import com.adsamcik.tracker.tracker.service.ManualTrackingSourceCapabilities
import com.adsamcik.tracker.tracker.service.ProviderStepsSessionLifecycle
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetup
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Current manual-start preflight; service preparation repeats all authority checks before commit. */
@Singleton
class DefaultManualTrackingStartReadinessReader @Inject constructor(
	@ApplicationContext private val context: Context,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val sourcePolicyRepositoryProvider: Provider<SourcePolicyRepository>,
	private val chosenSourceWriterSetupProvider: Provider<ChosenSourceWriterSetup>,
	private val trackingParamsRepositoryProvider: Provider<TrackingParamsRepository>,
	private val providerStepsSessions: ProviderStepsSessionLifecycle? = null,
) : ManualTrackingStartReadinessReader {
	override suspend fun read(): ManualTrackingStartReadiness = readPreflight().readiness

	override fun changes(): Flow<Unit> = combine(sourcePolicyRepositoryProvider.get().states,
		chosenSourceWriterSetupProvider.get().stateChanges(),
		trackingParamsRepositoryProvider.get().data.map { it.sourcePolicyRevision }.distinctUntilChanged()) { _, _, _ ->
            Unit }

	override suspend fun preparePreflight(): ManualTrackingStartPreflight {
		// An explicit Start may follow a fully handled End whose Ready publication is still stale.
		// Reconcile before setup and outside any Ready-generation operation or persistence lease.
		if (startupGateProvider.get().reconcile() !is TrackingStartupResult.Ready) {
			return ManualTrackingStartPreflight(ManualTrackingStartReadiness.TrackingUnavailable, null)
		}
		val policy = sourcePolicyRepositoryProvider.get().currentState() as? SourcePolicyAuthorityState.Active
		if (policy != null) {
			chosenSourceWriterSetupProvider.get().ensure(policy.snapshot.policies.values
			.filter { it.capturePersistenceEligible }.mapTo(linkedSetOf()) { it.source.toSourceKind() })
		}
		return readPreflight()
	}

	override suspend fun readPreflight(): ManualTrackingStartPreflight {
		val startupGate = startupGateProvider.get()
		val startupGeneration = startupGate.currentGeneration
		if (!startupGate.isReadyGeneration(startupGeneration)) {
			return ManualTrackingStartPreflight(ManualTrackingStartReadiness.TrackingUnavailable, null)
		}
		// Initial settings publication may need the same serialized startup boundary. Await it
		// before entering that boundary; current policy and generation are still checked inside.
		val providerStepsAvailable = providerStepsSessions?.isAvailable() == true
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			val policyState = sourcePolicyRepositoryProvider.get().currentState()
			if (policyState !is SourcePolicyAuthorityState.Active) {
				return@withReadyGenerationOperation ManualTrackingStartPreflight(
					ManualTrackingStartReadiness.TrackingUnavailable, null,
				)
			}
			val enabledSources = policyState.snapshot.policies.values
				.filter { policy -> policy.enabled && policy.capturePersistenceEligible }
				.mapTo(linkedSetOf()) { policy -> policy.source.toSourceKind() }
			val setup = chosenSourceWriterSetupProvider.get().readReadiness(enabledSources)
			val rolloutRevision = setup.rolloutRevision ?:
                return@withReadyGenerationOperation ManualTrackingStartPreflight(
				if (enabledSources.isEmpty()) {
					ManualTrackingStartReadiness.NoAvailableCaptureSource
				}
				else {
					ManualTrackingStartReadiness.SetupPending
				}, policyState.snapshot.manualStartSelection())
			// READY already proves the exact canonical binding, both prospective capture modes and owner in that snapshot.
			val reachableSources = setup.readySources
			val capabilities = context.manualTrackingSourceCapabilities()
			val providerSources = availableProviderSources(enabledSources, providerStepsAvailable)
			val available = capabilities.availableSources + providerSources
			val supported = capabilities.supportedSources + providerSources
			val reachable = reachableSources + providerSources
			val readiness = resolveReadiness(
				rolloutRevision, enabledSources, reachable, supported, available, capabilities,
			)
			val pending = setup.pendingSources.any(capabilities.supportedSources::contains) &&
				(readiness == ManualTrackingStartReadiness.TrackingUnavailable ||
                    readiness == ManualTrackingStartReadiness.NoAvailableCaptureSource)
			ManualTrackingStartPreflight(if (pending) {
				ManualTrackingStartReadiness.SetupPending
			} else {
				readiness
			},
				policyState.snapshot.manualStartSelection())
		} ?: ManualTrackingStartPreflight(ManualTrackingStartReadiness.TrackingUnavailable, null)
	}

	private fun availableProviderSources(
		enabledSources: Set<SourceKind>,
		providerStepsAvailable: Boolean,
	): Set<SourceKind> = if (providerStepsAvailable && SourceKind.STEPS in enabledSources) {
		setOf(SourceKind.STEPS)
	} else {
		emptySet()
	}

	private fun resolveReadiness(
		rolloutRevision: Long,
		enabledSources: Set<SourceKind>,
		reachable: Set<SourceKind>,
		supported: Set<SourceKind>,
		available: Set<SourceKind>,
		capabilities: ManualTrackingSourceCapabilities,
	): ManualTrackingStartReadiness {
		return resolveManualTrackingStartReadiness(
			rolloutRevision = rolloutRevision,
			enabledSources = enabledSources.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
			reachableSources = reachable.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
			supportedSources = supported
				.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
			availableSources = available
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
	}
}

/** Capture consent changes when a source is turned off and on; quality edits do not change it. */
internal fun SourcePolicySnapshot.manualStartSelection() =
	ManualTrackingStartSelection(policies.values.filter { it.enabled && it.capturePersistenceEligible }
		.associate { it.source.toSourceKind().toApiCaptureSource() to requireNotNull(it.captureConsentEpoch) })

private fun TrackingSourceComponent.toSourceKind(): SourceKind = when (this) {
	TrackingSourceComponent.LOCATION -> SourceKind.LOCATION
	TrackingSourceComponent.ACTIVITY -> SourceKind.ACTIVITY
	TrackingSourceComponent.STEPS -> SourceKind.STEPS
	TrackingSourceComponent.PRESSURE -> SourceKind.PRESSURE
	TrackingSourceComponent.WIFI -> SourceKind.WIFI
	TrackingSourceComponent.CELL -> SourceKind.CELL
}
