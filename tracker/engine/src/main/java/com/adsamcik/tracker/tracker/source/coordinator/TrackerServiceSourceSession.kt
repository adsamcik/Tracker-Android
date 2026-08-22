package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable source ownership selected once for an Android service run. */
data class TrackingSessionOwnership(
	val rollout: TrackingRolloutState,
	val enabledEventSources: Set<SourceKind>,
) {
	val eventCoordinatorRequired: Boolean get() = enabledEventSources.isNotEmpty()

	companion object {
		fun resolve(rollout: TrackingRolloutState, settings: TrackingParamsState): TrackingSessionOwnership {
			require(rollout.coordinatorMode == CoordinatorMode.EVENT &&
				rollout.projectionMode == ProjectionMode.EVENT_CANONICAL &&
				rollout.sourceOwners.values.all { it == SourceOwner.EVENT }
			) { "Phase 10 sessions require event-canonical ownership" }
			val enabled = settings.enabledSemanticSources()
			return TrackingSessionOwnership(
				rollout = rollout,
				enabledEventSources = enabled,
			)
		}
	}
}

data class SourceSessionPlanInputs(
	val settings: TrackingParamsState,
	val environment: SourcePlanEnvironment,
	val resolutionContext: PlanResolutionContext,
	val demands: List<SourceDemand>,
	val clockDomainId: String,
	val zoneId: String = ZoneId.systemDefault().id,
)

data class SourceSessionStartRequest(
	val rollout: TrackingRolloutState,
	val ownership: TrackingSessionOwnership,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val origin: SessionStartOrigin,
	val foregroundCapabilityFlags: Long,
	val planInputs: SourceSessionPlanInputs,
	val ownerToken: String,
)

sealed interface SourceSessionStartOutcome {
	data class Started(val result: SessionStartResult.Started) : SourceSessionStartOutcome
	data object NotRequired : SourceSessionStartOutcome
	data class Rejected(val result: SessionStartResult) : SourceSessionStartOutcome
}

sealed interface SourceSessionReconfigureOutcome {
	data class Applied(val result: SessionReconfigureResult.Applied) : SourceSessionReconfigureOutcome
	data class Started(val result: SessionStartResult.Started) : SourceSessionReconfigureOutcome
	data object NotActive : SourceSessionReconfigureOutcome
	data object Unchanged : SourceSessionReconfigureOutcome
	data class Rejected(val result: SessionReconfigureResult) : SourceSessionReconfigureOutcome
}

/**
 * TrackerService-facing owner of the event-source session. It snapshots rollout ownership,
 * serializes settings/policy revisions, and delegates durable lifecycle fencing to the
 * authoritative coordinator.
 */
class TrackerServiceSourceSession @Inject constructor(
	private val database: AppDatabase,
	private val coordinator: AuthoritativeSessionCoordinator,
	private val planFactory: SemanticAcquisitionPlanFactory,
	private val planResolver: SourcePlanResolver,
	private val telemetry: TrackingCoordinatorTelemetry,
	private val settingsStatusProvider: TrackingSettingsStatusProvider,
) {
	private val mutex = Mutex()
	private var active: ActiveSession? = null
	private var pendingInputs: SourceSessionPlanInputs? = null

	suspend fun start(request: SourceSessionStartRequest): SourceSessionStartOutcome = mutex.withLock {
		require(request.rollout == request.ownership.rollout) { "Ownership must use the supplied rollout snapshot" }
		require(request.logicalTrackingId.isNotBlank())
		require(request.serviceRunId.isNotBlank())
		val planInputs = pendingInputs
			?.takeIf { pending -> pending.isAtLeastAsCurrentAs(request.planInputs) }
			?: request.planInputs
		pendingInputs = null
		val ownership = TrackingSessionOwnership.resolve(request.rollout, planInputs.settings)
		val session = ActiveSession(
			rollout = request.rollout,
			ownerToken = request.ownerToken,
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			origin = request.origin,
			foregroundCapabilityFlags = request.foregroundCapabilityFlags,
			lastInputs = planInputs,
			coordinatorStarted = false,
		)
		active = session
		if (!ownership.eventCoordinatorRequired) {
			settingsStatusProvider.publishActivePreview(
				planInputs.settings,
				request.rollout,
				planInputs,
			)
			return@withLock SourceSessionStartOutcome.NotRequired
		}
		check(request.rollout.coordinatorMode == CoordinatorMode.EVENT) {
			"Event-owned sources require event coordinator mode"
		}
		val result = startCoordinator(session, planInputs)
		if (result is SessionStartResult.Started) {
			session.coordinatorStarted = true
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionStartOutcome.Started(result)
		} else {
			settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
			active = null
			settingsStatusProvider.publishInactive()
			SourceSessionStartOutcome.Rejected(result)
		}
	}

	suspend fun reconfigure(inputs: SourceSessionPlanInputs): SourceSessionReconfigureOutcome = mutex.withLock {
		val session = active ?: run {
			pendingInputs = pendingInputs
				?.takeIf { current -> current.isNewerThan(inputs) }
				?: inputs
			return@withLock SourceSessionReconfigureOutcome.NotActive
		}
		if (session.lastInputs == inputs) return@withLock SourceSessionReconfigureOutcome.Unchanged
		if (!session.coordinatorStarted) {
			val ownership = TrackingSessionOwnership.resolve(session.rollout, inputs.settings)
			session.lastInputs = inputs
			if (!ownership.eventCoordinatorRequired) {
				settingsStatusProvider.publishActivePreview(inputs.settings, session.rollout, inputs)
				return@withLock SourceSessionReconfigureOutcome.Unchanged
			}
			val started = startCoordinator(session, inputs)
			return@withLock if (started is SessionStartResult.Started) {
				session.coordinatorStarted = true
				settingsStatusProvider.publishApplied(started.applied)
				SourceSessionReconfigureOutcome.Started(started)
			} else {
				settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
				SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState("START_REJECTED:$started"),
				)
			}
		}
		val plan = buildPlan(session.rollout, inputs, requireEnabled = false)
		val result = coordinator.reconfigure(
			SessionReconfigureRequest(
				ownerToken = session.ownerToken,
				plan = plan,
				wallTimeMs = Time.nowMillis,
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				clockDomainId = inputs.clockDomainId,
				zoneId = inputs.zoneId,
				foregroundCapabilityFlags = session.foregroundCapabilityFlags,
				controlDependencies = controlDependencies(session.origin, inputs.settings),
			),
		)
		if (result is SessionReconfigureResult.Applied) {
			session.lastInputs = inputs
			settingsStatusProvider.publishApplied(result.applied)
			SourceSessionReconfigureOutcome.Applied(result)
		} else {
			settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
			SourceSessionReconfigureOutcome.Rejected(result)
		}
	}

	suspend fun stop(reason: String, preserveLogicalSession: Boolean) = mutex.withLock {
		val session = active ?: run {
			pendingInputs = null
			settingsStatusProvider.publishInactive()
			return@withLock
		}
		if (!session.coordinatorStarted) {
			active = null
			settingsStatusProvider.publishInactive()
			return@withLock
		}
		if (preserveLogicalSession) {
			when (val result = coordinator.suspendForRestart(
				SessionSuspendRequest(
					ownerToken = session.ownerToken,
					reason = reason,
					wallTimeMs = Time.nowMillis,
					elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
					clockDomainId = session.lastInputs.clockDomainId,
				),
			)) {
				is SessionSuspendResult.Suspended,
				SessionSuspendResult.NoActiveSession,
				-> active = null
				is SessionSuspendResult.DrainPending -> error(
					"Event-source suspension drain pending through ${result.requiredOrdinal}",
				)
				SessionSuspendResult.Busy -> error("Event-source session coordinator is busy")
			}
		} else {
			when (val result = coordinator.stop(
				SessionStopRequest(
					ownerToken = session.ownerToken,
					reason = reason,
					wallTimeMs = Time.nowMillis,
					elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
					clockDomainId = session.lastInputs.clockDomainId,
				),
			)) {
				is SessionStopResult.Stopped,
				SessionStopResult.NoActiveSession,
				-> active = null
				is SessionStopResult.DrainPending -> error(
					"Event-source shutdown drain pending through ${result.requiredOrdinal}",
				)
				SessionStopResult.Busy -> error("Event-source session coordinator is busy")
			}
		}
		settingsStatusProvider.publishInactive()
	}

	private suspend fun startCoordinator(
		session: ActiveSession,
		inputs: SourceSessionPlanInputs,
	): SessionStartResult {
		val plan = buildPlan(session.rollout, inputs, requireEnabled = true)
		return coordinator.start(
			SessionStartRequest(
				ownerToken = session.ownerToken,
				origin = session.origin,
				plan = plan,
				rolloutRevision = session.rollout.revision,
				clockDomainId = inputs.clockDomainId,
				foregroundCapabilityFlags = session.foregroundCapabilityFlags,
				wallTimeMs = Time.nowMillis,
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				zoneId = inputs.zoneId,
				controlDependencies = controlDependencies(session.origin, inputs.settings),
				logicalTrackingId = session.logicalTrackingId,
				serviceRunId = session.serviceRunId,
			),
		)
	}

	private suspend fun buildPlan(
		rollout: TrackingRolloutState,
		inputs: SourceSessionPlanInputs,
		requireEnabled: Boolean,
	): AcquisitionPlanRevision {
		val revision = (database.sourcePlanStateDao().latestRevision()?.revision ?: 0L) + 1L
		val desired = planFactory.create(inputs.settings, revision, Time.nowMillis, inputs.environment)
		val resolved = planResolver.resolve(desired, inputs.demands, inputs.resolutionContext)
		settingsStatusProvider.publishResolved(inputs.settings, rollout, resolved)
		telemetry.recordPlanRevision()
		val eventPlans = resolved.applicablePlans.filterKeys { source ->
			rollout.sourceOwners.getValue(source) == SourceOwner.EVENT
		}
		if (requireEnabled) {
			check(eventPlans.values.any { plan -> plan.enabled }) {
				"Event session requires an enabled event-owned source"
			}
		}
		return AcquisitionPlanRevision(
			revision = revision,
			planId = "${desired.planId}-event-rollout-${rollout.revision}",
			createdAtMs = desired.createdAtMs,
			plans = eventPlans,
			sourcePolicyRevision = desired.sourcePolicyRevision,
		)
	}

	private data class ActiveSession(
		val rollout: TrackingRolloutState,
		val ownerToken: String,
		val logicalTrackingId: String,
		val serviceRunId: String,
		val origin: SessionStartOrigin,
		val foregroundCapabilityFlags: Long,
		var lastInputs: SourceSessionPlanInputs,
		var coordinatorStarted: Boolean,
	)
}

private fun controlDependencies(
	origin: SessionStartOrigin,
	settings: TrackingParamsState,
): Set<SourceKind> = if (origin != SessionStartOrigin.AUTOMATIC_BACKGROUND_START) {
	emptySet()
} else {
	buildSet {
		add(SourceKind.ACTIVITY)
		if (!settings.transitionDetectionEnabled) add(SourceKind.STEPS)
	}
}

private fun SourceSessionPlanInputs.isNewerThan(other: SourceSessionPlanInputs): Boolean {
	val candidateRevision = settings.sourcePolicyRevision ?: return false
	val otherRevision = other.settings.sourcePolicyRevision
	return otherRevision == null || candidateRevision > otherRevision
}

private fun SourceSessionPlanInputs.isAtLeastAsCurrentAs(other: SourceSessionPlanInputs): Boolean {
	val candidateRevision = settings.sourcePolicyRevision ?: return false
	val otherRevision = other.settings.sourcePolicyRevision
	return otherRevision == null || candidateRevision >= otherRevision
}

private fun TrackingParamsState.enabledSemanticSources(): Set<SourceKind> = buildSet {
	val frequency = sourceCollectionSettings
	if (frequency.location != SourceCollectionFrequency.OFF && locationEnabled) add(SourceKind.LOCATION)
	if (frequency.activity != SourceCollectionFrequency.OFF && activityEnabled) add(SourceKind.ACTIVITY)
	if (frequency.steps != SourceCollectionFrequency.OFF && stepsEnabled) add(SourceKind.STEPS)
	if (frequency.pressure != SourceCollectionFrequency.OFF && barometerEnabled) add(SourceKind.PRESSURE)
	if (frequency.wifi != SourceCollectionFrequency.OFF && wifiEnabled) add(SourceKind.WIFI)
	if (frequency.cell != SourceCollectionFrequency.OFF && cellEnabled) add(SourceKind.CELL)
}
