package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
	data class RolledBack(val result: SessionReconfigureResult.RolledBack) : SourceSessionReconfigureOutcome
	data class Started(val result: SessionStartResult.Started) : SourceSessionReconfigureOutcome
	data object NotActive : SourceSessionReconfigureOutcome
	data object Unchanged : SourceSessionReconfigureOutcome
	data class Rejected(val result: SessionReconfigureResult) : SourceSessionReconfigureOutcome
}

sealed interface SourceSessionStopOutcome {
	data object Stopped : SourceSessionStopOutcome
	data object NotActive : SourceSessionStopOutcome
	data class Retryable(val code: SourceSessionStopRetryCode) : SourceSessionStopOutcome
}

enum class SourceSessionStopRetryCode {
	COORDINATOR_BUSY,
	DRAIN_PENDING,
	STORAGE_UNAVAILABLE,
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

	suspend fun start(request: SourceSessionStartRequest): SourceSessionStartOutcome = mutex.withLock {
		require(request.rollout == request.ownership.rollout) { "Ownership must use the supplied rollout snapshot" }
		require(request.logicalTrackingId.isNotBlank())
		require(request.serviceRunId.isNotBlank())
		val session = ActiveSession(
			rollout = request.rollout,
			ownerToken = request.ownerToken,
			logicalTrackingId = request.logicalTrackingId,
			serviceRunId = request.serviceRunId,
			origin = request.origin,
			foregroundCapabilityFlags = request.foregroundCapabilityFlags,
			lastInputs = request.planInputs,
			coordinatorStarted = false,
		)
		active = session
		if (!request.ownership.eventCoordinatorRequired) {
			settingsStatusProvider.publishActivePreview(
				request.planInputs.settings,
				request.rollout,
				request.planInputs,
			)
			return@withLock SourceSessionStartOutcome.NotRequired
		}
		check(request.rollout.coordinatorMode == CoordinatorMode.EVENT) {
			"Event-owned sources require event coordinator mode"
		}
		val result = try {
			startCoordinator(session, request.planInputs)
		} catch (cancelled: CancellationException) {
			active = null
			settingsStatusProvider.publishFailure("SESSION_START_CANCELLED")
			settingsStatusProvider.publishInactive()
			throw cancelled
		} catch (failure: Exception) {
			active = null
			settingsStatusProvider.publishFailure("SESSION_START_EXCEPTION")
			settingsStatusProvider.publishInactive()
			if (failure.isTrackingOperationalFailure()) {
				return@withLock SourceSessionStartOutcome.Rejected(
					SessionStartResult.Failed(
						request.logicalTrackingId,
						request.serviceRunId,
						emptyList(),
						"STORAGE_UNAVAILABLE",
					),
				)
			}
			throw failure
		}
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
		val session = active ?: return@withLock SourceSessionReconfigureOutcome.NotActive
		if (session.lastInputs == inputs) return@withLock SourceSessionReconfigureOutcome.Unchanged
		if (!session.coordinatorStarted) {
			val ownership = TrackingSessionOwnership.resolve(session.rollout, inputs.settings)
			if (!ownership.eventCoordinatorRequired) {
				session.lastInputs = inputs
				settingsStatusProvider.publishActivePreview(inputs.settings, session.rollout, inputs)
				return@withLock SourceSessionReconfigureOutcome.Unchanged
			}
			val started = try {
				startCoordinator(session, inputs)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				if (!failure.isTrackingOperationalFailure()) throw failure
				settingsStatusProvider.publishFailure("SESSION_START_STORAGE_UNAVAILABLE")
				return@withLock SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState("START_STORAGE_UNAVAILABLE"),
				)
			}
			return@withLock if (started is SessionStartResult.Started) {
				session.coordinatorStarted = true
				session.lastInputs = inputs
				settingsStatusProvider.publishApplied(started.applied)
				SourceSessionReconfigureOutcome.Started(started)
			} else {
				settingsStatusProvider.publishFailure("SESSION_START_REJECTED")
				SourceSessionReconfigureOutcome.Rejected(
					SessionReconfigureResult.InvalidState("START_REJECTED:$started"),
				)
			}
		}
		val result = try {
			val plan = buildPlan(session.rollout, inputs, requireEnabled = false)
			coordinator.reconfigure(
				SessionReconfigureRequest(
					ownerToken = session.ownerToken,
					plan = plan,
					wallTimeMs = Time.nowMillis,
					elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			if (!failure.isTrackingOperationalFailure()) throw failure
			settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_STORAGE_UNAVAILABLE")
			return@withLock SourceSessionReconfigureOutcome.Rejected(
				SessionReconfigureResult.InvalidState("STORAGE_UNAVAILABLE"),
			)
		}
		when (result) {
			is SessionReconfigureResult.Applied -> {
				session.lastInputs = inputs
				settingsStatusProvider.publishApplied(result.applied)
				SourceSessionReconfigureOutcome.Applied(result)
			}
			is SessionReconfigureResult.RolledBack -> {
				settingsStatusProvider.publishApplied(result.restored)
				settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_ROLLED_BACK:${result.code}")
				SourceSessionReconfigureOutcome.RolledBack(result)
			}
			is SessionReconfigureResult.Failed -> {
				active = null
				settingsStatusProvider.publishFailure(result.code)
				SourceSessionReconfigureOutcome.Rejected(result)
			}
			else -> {
				settingsStatusProvider.publishFailure("PLAN_RECONFIGURE_REJECTED")
				SourceSessionReconfigureOutcome.Rejected(result)
			}
		}
	}

	suspend fun stop(
		reason: String,
		preserveLogicalSession: Boolean,
	): SourceSessionStopOutcome = mutex.withLock {
		val session = active ?: run {
			settingsStatusProvider.publishInactive()
			return@withLock SourceSessionStopOutcome.NotActive
		}
		if (!session.coordinatorStarted) {
			active = null
			settingsStatusProvider.publishInactive()
			return@withLock SourceSessionStopOutcome.Stopped
		}
		val outcome = try {
			if (preserveLogicalSession) {
				when (val result = coordinator.suspendForRestart(
					SessionSuspendRequest(
						ownerToken = session.ownerToken,
						reason = reason,
						wallTimeMs = Time.nowMillis,
						elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
					),
				)) {
					is SessionSuspendResult.Suspended,
					SessionSuspendResult.NoActiveSession,
					-> SourceSessionStopOutcome.Stopped.also { active = null }
					is SessionSuspendResult.DrainPending ->
						SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.DRAIN_PENDING)
					SessionSuspendResult.Busy ->
						SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.COORDINATOR_BUSY)
				}
			} else {
				when (val result = coordinator.stop(
					SessionStopRequest(
						ownerToken = session.ownerToken,
						reason = reason,
						wallTimeMs = Time.nowMillis,
						elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
					),
				)) {
					is SessionStopResult.Stopped,
					SessionStopResult.NoActiveSession,
					-> SourceSessionStopOutcome.Stopped.also { active = null }
					is SessionStopResult.DrainPending ->
						SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.DRAIN_PENDING)
					SessionStopResult.Busy ->
						SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.COORDINATOR_BUSY)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			if (!failure.isTrackingOperationalFailure()) throw failure
			SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.STORAGE_UNAVAILABLE)
		}
		if (outcome == SourceSessionStopOutcome.Stopped) settingsStatusProvider.publishInactive()
		outcome
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

private fun TrackingParamsState.enabledSemanticSources(): Set<SourceKind> = buildSet {
	val frequency = sourceCollectionSettings
	if (frequency.location != SourceCollectionFrequency.OFF && locationEnabled) add(SourceKind.LOCATION)
	if (frequency.activity != SourceCollectionFrequency.OFF && activityEnabled) add(SourceKind.ACTIVITY)
	if (frequency.steps != SourceCollectionFrequency.OFF && stepsEnabled) add(SourceKind.STEPS)
	if (frequency.pressure != SourceCollectionFrequency.OFF && barometerEnabled) add(SourceKind.PRESSURE)
	if (frequency.wifi != SourceCollectionFrequency.OFF && wifiEnabled) add(SourceKind.WIFI)
	if (frequency.cell != SourceCollectionFrequency.OFF && cellEnabled) add(SourceKind.CELL)
}
