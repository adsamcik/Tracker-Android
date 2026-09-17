package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticFailureCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticFailureReason
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityVector
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.matchesAuthority
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

@Singleton
@Suppress("LongParameterList")
internal class CurrentTrackingPurposeAvailabilityProjection @Inject constructor(
	private val publishedReader: TrackingPurposeAvailabilityReader,
	sourcePolicyRepository: SourcePolicyRepository,
	collectedDataLifecycleStore: CollectedDataLifecycleStore,
	rolloutStateStore: TrackingRolloutStateStore,
	private val executionRevisionRegistry: TrackingPurposeExecutionRevisionRegistry,
	private val exactAuthorityReader: TrackingPurposeAuthorityReader,
	@ApplicationScope applicationScope: CoroutineScope,
) : CurrentTrackingPurposeAvailabilityReader {
	private val authorityState: StateFlow<CurrentTrackingPurposeAuthorityState> = combine(
		sourcePolicyRepository.states,
		collectedDataLifecycleStore.snapshots,
		rolloutStateStore.states,
	) { policy, lifecycle, rollout ->
		val snapshot = (policy as? SourcePolicyAuthorityState.Active)?.snapshot
		if (snapshot == null) {
			CurrentTrackingPurposeAuthorityState.Unavailable
		} else {
			CurrentTrackingPurposeAuthorityState.Available(snapshot, lifecycle, rollout)
		}
	}.failClosedOnObservationError()
		.stateIn(
			applicationScope,
			SharingStarted.Eagerly,
			CurrentTrackingPurposeAuthorityState.Unavailable,
		)

	override val authorityRevision: StateFlow<TrackingPurposeAuthorityRevision> =
		authorityState.map { it.revision }
			.distinctUntilChanged()
			.stateIn(
				applicationScope,
				SharingStarted.Eagerly,
				TrackingPurposeAuthorityRevision.UNAVAILABLE,
			)

	private val currentAuthorities:
		StateFlow<Map<TrackingSourcePurposeIdentity, TrackingPurposeAuthorityVector>> = combine(
		authorityState,
		executionRevisionRegistry.revisions,
	) { authority, executions ->
		authority.currentAuthorities(executions)
	}.stateIn(
		applicationScope,
		SharingStarted.Eagerly,
		emptyMap(),
	)

	override val availability: StateFlow<CurrentTrackingPurposeAvailability> = combine(
		publishedReader.availability,
		currentAuthorities,
	) { published, current ->
		CurrentTrackingPurposeAvailability(published, current)
	}.stateIn(
		applicationScope,
		SharingStarted.Eagerly,
		CurrentTrackingPurposeAvailability.SAFE_DEFAULT,
	)

	override suspend fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean {
		val registeredExecution =
			executionRevisionRegistry.revisions.value[identity.sourcePurpose] ?: 0L
		if (registeredExecution != identity.executionRevision) return false
		val observedAuthority = authorityState.value.currentAuthorities(
			executionRevisionRegistry.revisions.value,
		)[identity.sourcePurpose] ?: return false
		if (!identity.matchesAuthority(observedAuthority)) return false
		if (!publishedReader.availability.value.containsOperational(identity)) return false
		val exactMatches = exactAuthorityReader.read(identity.sourcePurpose, registeredExecution)
			?.toAuthorityVector()
			?.let(identity::matchesAuthority) == true
		return exactMatches &&
			executionRevisionRegistry.revisions.value[identity.sourcePurpose] == registeredExecution &&
			publishedReader.availability.value.containsOperational(identity)
	}
}

private sealed interface CurrentTrackingPurposeAuthorityState {
	val revision: TrackingPurposeAuthorityRevision

	data object Unavailable : CurrentTrackingPurposeAuthorityState {
		override val revision: TrackingPurposeAuthorityRevision =
			TrackingPurposeAuthorityRevision.UNAVAILABLE
	}

	data class Available(
		val policy: SourcePolicySnapshot,
		val lifecycle: CollectedDataLifecycleSnapshot,
		val rollout: TrackingRolloutState,
	) : CurrentTrackingPurposeAuthorityState {
		override val revision = TrackingPurposeAuthorityRevision(
			policyRevision = policy.revision,
			collectedDataEpoch = lifecycle.epoch,
			rolloutRevision = rollout.revision,
		)
	}

	fun currentAuthorities(
		executions: Map<TrackingSourcePurposeIdentity, Long>,
	): Map<TrackingSourcePurposeIdentity, TrackingPurposeAuthorityVector> = when (this) {
		Unavailable -> emptyMap()
		is Available -> APPROVED_PUBLISHED_PURPOSES.mapNotNull { sourcePurpose ->
			val sourcePolicy = policy[sourcePurpose.source]
			val consentEpoch = sourcePolicy.consentEpoch(sourcePurpose.purpose)
				?: return@mapNotNull null
			val registeredExecution = executions[sourcePurpose] ?: 0L
			val executionRevision = registeredExecution.takeIf {
				sourcePolicy.persistenceEligible(sourcePurpose.purpose) &&
					rollout.supportsExecution(sourcePurpose)
			} ?: 0L
			sourcePurpose to TrackingPurposeAuthorityVector(
				sourcePurpose = sourcePurpose,
				policyRevision = policy.revision,
				consentEpoch = consentEpoch,
				collectedDataEpoch = lifecycle.epoch,
				rolloutRevision = rollout.revision,
				executionRevision = executionRevision,
			)
		}.toMap()
	}
}

private fun Flow<CurrentTrackingPurposeAuthorityState>.failClosedOnObservationError():
	Flow<CurrentTrackingPurposeAuthorityState> = retryWhen { _, _ ->
	currentCoroutineContext().ensureActive()
	emit(CurrentTrackingPurposeAuthorityState.Unavailable)
	TrackerDiagnosticLog.failure(
		TrackerDiagnosticFailureCode.SOURCE_POLICY_OBSERVATION_FAILED,
		TrackingDiagnosticFailureReason.POLICY_READ_FAILURE,
	)
	delay(AUTHORITY_OBSERVATION_RETRY_MILLIS)
	true
}

private val APPROVED_PUBLISHED_PURPOSES = buildList {
	add(TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL))
	listOf(
		TrackingSource.STEPS,
		TrackingSource.LOCATION,
		TrackingSource.WIFI,
		TrackingSource.CELL,
	).forEach { source ->
		add(source.forPurpose(TrackingPurpose.AMBIENT_PRODUCT))
	}
}

private const val AUTHORITY_OBSERVATION_RETRY_MILLIS = 250L

private fun TrackingPurposeAvailabilitySnapshot.containsOperational(
	identity: TrackingPurposeLeaseIdentity,
): Boolean = when (identity.purpose) {
	TrackingPurpose.CONTROL ->
		(automaticControl as? AutomaticTrackingOperationalAvailability.Ready)?.identity == identity
	TrackingPurpose.AMBIENT_PRODUCT -> {
		val source = AmbientTrackingSource.entries.singleOrNull {
			it.canonicalSource == identity.source
		}
		source != null && ambientSources.getValue(source).operationalIdentity == identity
	}
	TrackingPurpose.SESSION_CAPTURE -> false
}

private fun TrackingPurposeAuthoritySnapshot.toAuthorityVector() =
	TrackingPurposeAuthorityVector(
		sourcePurpose = sourcePurpose,
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		rolloutRevision = rolloutRevision,
		executionRevision = executionRevision,
	)
