package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.stats.api.PolicyTier
import java.util.UUID

/**
 * The durable lifecycle owned by one logical tracking session.
 *
 * A logical session can span multiple Android service instances.  In particular, an
 * involuntary process/service restart must retain [ActiveTrackingSessionDescriptor.logicalTrackingId]
 * while receiving a new [ActiveTrackingSessionDescriptor.serviceRunId].  The distinction makes
 * recovery and future evidence correlation explicit without making a Room session row the owner
 * of tracker lifetime.
 */
enum class LogicalTrackingLifecycleState {
	/** Collection is allowed and an involuntary teardown may be recovered for user sessions. */
	ACTIVE,

	/** Collection is intentionally suspended; it is never restart-watchdog eligible. */
	PAUSED,

	/**
	 * A stop was requested but teardown has not yet been acknowledged.
	 *
	 * Persisting this state before stopping closes the race where Android destroys the service
	 * between a graceful-stop request and descriptor cleanup.
	 */
	STOP_CANDIDATE,

	/** The logical session has completed and is retained only until the descriptor is cleared. */
	FINISHED,
}

/**
 * Why a tracker lifecycle entered [LogicalTrackingLifecycleState.STOP_CANDIDATE].
 *
 * The values are deliberately stable strings in the proto store rather than Android-specific
 * error codes.  This keeps automatic-stop provenance available to future control/evidence
 * adapters while allowing the current service to remain the Android lifecycle shell.
 */
enum class TrackingStopCandidateReason {
	EXPLICIT_REQUEST,
	AUTOMATIC_ACTIVITY_INCOMPATIBLE,
	DEVICE_LOCKED,
	POWER_SAVER,
	CAPTURE_UNAVAILABLE,
	PERMISSION_UNAVAILABLE,
	INITIALIZATION_FAILURE,
	TIMER_FAILURE,
	INTERNAL_FAILURE,
	UNKNOWN,
}

data class TrackingStopCandidate(
	val reason: TrackingStopCandidateReason,
	/** Optional wall-clock audit time. It is not used for duration calculations. */
	val requestedAtEpochMs: Long? = null,
) {
	init {
		require(requestedAtEpochMs == null || requestedAtEpochMs >= 0L) {
			"requestedAtEpochMs must not be negative"
		}
	}
}

/**
 * Durable source evidence offered for one automatic service start.
 *
 * This proves product intent only. [startContext] records why the immediate Android call was legal;
 * it is never reusable after the callback/foreground context that created it has ended.
 */
data class AutomaticTrackingStartTrigger(
	val triggerId: String,
	val kind: String,
	val bootId: String,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val expiresElapsedRealtimeNanos: Long,
	val automationEpoch: Long,
	val startContext: AutomaticTrackingStartContext,
	/** Distinct even when today's automation epoch happens to equal the policy revision. */
	val sourcePolicyRevision: Long,
	/** Stable SourceKind-bit mask of capture sources requested by the authoritative policy. */
	val intendedCaptureSourceMask: Long,
	/** Configured capture sources before origin/capability degradation. */
	val requestedCaptureSourceMask: Long,
	/** Exact Android foreground-service type union intended for this request. */
	val intendedForegroundServiceTypeMask: Long,
	/** Full-deletion generation. A delayed service intent must match the current Room epoch. */
	val collectedDataEpoch: Long,
) {
	init {
		require(triggerId.isNotBlank()) { "triggerId must not be blank" }
		require(kind.isNotBlank()) { "kind must not be blank" }
		require(bootId.isNotBlank()) { "bootId must not be blank" }
		require(observedElapsedRealtimeNanos >= 0L)
		require(receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos)
		require(expiresElapsedRealtimeNanos >= receivedElapsedRealtimeNanos)
		require(automationEpoch > 0L)
		require(sourcePolicyRevision > 0L)
		require(intendedCaptureSourceMask > 0L)
		require(requestedCaptureSourceMask > 0L)
		require(intendedCaptureSourceMask and requestedCaptureSourceMask == intendedCaptureSourceMask)
		require(intendedForegroundServiceTypeMask >= 0L)
		require(collectedDataEpoch >= 0L)
	}
}

/** Actual, non-replayable platform context in which [AutomaticTrackingStartTrigger] was offered. */
enum class AutomaticTrackingStartContext {
	ACTIVITY_TRANSITION_CALLBACK,
}

private fun newTrackingCorrelationId(): String = UUID.randomUUID().toString()

/**
 * Durable descriptor for Android recovery and correlation of one logical tracking session.
 *
 * This descriptor intentionally lives outside Room.  It is a small recovery/control record, not
 * a replacement for derived [SessionSegment]-style timeline data.  New fields have defaults so
 * callers compiled against the former three-field descriptor remain source-compatible.
 */
data class ActiveTrackingSessionDescriptor(
	val isUserInitiated: Boolean,
	val isAmbient: Boolean,
	val policyTier: PolicyTier,
	/** Stable over service replacement, restart watchdog delivery, pause, and resume. */
	val logicalTrackingId: String = newTrackingCorrelationId(),
	/** Changes whenever a service instance begins work for [logicalTrackingId]. */
	val serviceRunId: String = newTrackingCorrelationId(),
	val lifecycleState: LogicalTrackingLifecycleState = LogicalTrackingLifecycleState.ACTIVE,
	/** Monotonic descriptor transition counter used to correlate durable control records. */
	val lifecycleRevision: Long = 0L,
	/** Optional wall-clock audit time; never use it to bridge duration across a restart. */
	val lifecycleChangedAtEpochMs: Long? = null,
	val stopCandidate: TrackingStopCandidate? = null,
	/** Boot domain in which watchdog/redelivery recovery was authorized. */
	val restartBootId: String? = null,
	/** Unpredictable token copied only into trusted restart intents. */
	val restartToken: String? = null,
) {
	init {
		require(logicalTrackingId.isNotBlank()) { "logicalTrackingId must not be blank" }
		require(serviceRunId.isNotBlank()) { "serviceRunId must not be blank" }
		require(lifecycleRevision >= 0L) { "lifecycleRevision must not be negative" }
		require(lifecycleChangedAtEpochMs == null || lifecycleChangedAtEpochMs >= 0L) {
			"lifecycleChangedAtEpochMs must not be negative"
		}
		require((restartBootId == null) == (restartToken == null)) {
			"Restart boot identity and token must either both be present or both be absent"
		}
		require(restartBootId == null || restartBootId.isNotBlank()) { "restartBootId must not be blank" }
		require(restartToken == null || restartToken.isNotBlank()) { "restartToken must not be blank" }
		if (lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE) {
			require(stopCandidate != null) {
				"STOP_CANDIDATE descriptors require a stopCandidate"
			}
		} else {
			require(stopCandidate == null) {
				"Only STOP_CANDIDATE descriptors may include a stopCandidate"
			}
		}
	}

	/** Only active user sessions may be restarted after involuntary Android teardown. */
	val isRestartEligible: Boolean
		get() = isUserInitiated && lifecycleState == LogicalTrackingLifecycleState.ACTIVE &&
			restartBootId != null && restartToken != null

	fun isRestartEligibleForBoot(currentBootId: String): Boolean =
		isRestartEligible && restartBootId == currentBootId

	/**
	 * Starts a new Android-service run without creating a new logical session.
	 */
	fun forNewServiceRun(changedAtEpochMs: Long? = null): ActiveTrackingSessionDescriptor =
		transition(
			serviceRunId = newTrackingCorrelationId(),
			changedAtEpochMs = changedAtEpochMs,
		)

	fun pause(changedAtEpochMs: Long? = null): ActiveTrackingSessionDescriptor = when (lifecycleState) {
		LogicalTrackingLifecycleState.ACTIVE -> transition(
			lifecycleState = LogicalTrackingLifecycleState.PAUSED,
			changedAtEpochMs = changedAtEpochMs,
		)
		LogicalTrackingLifecycleState.PAUSED -> this
		LogicalTrackingLifecycleState.STOP_CANDIDATE,
		LogicalTrackingLifecycleState.FINISHED -> this
	}

	fun resume(changedAtEpochMs: Long? = null): ActiveTrackingSessionDescriptor = when (lifecycleState) {
		LogicalTrackingLifecycleState.PAUSED -> transition(
			lifecycleState = LogicalTrackingLifecycleState.ACTIVE,
			changedAtEpochMs = changedAtEpochMs,
		)
		LogicalTrackingLifecycleState.ACTIVE -> this
		LogicalTrackingLifecycleState.STOP_CANDIDATE,
		LogicalTrackingLifecycleState.FINISHED -> this
	}

	/** Records an idempotent, durable stop proposal before service teardown starts. */
	fun proposeStop(
		reason: TrackingStopCandidateReason,
		changedAtEpochMs: Long? = null,
	): ActiveTrackingSessionDescriptor = when (lifecycleState) {
		LogicalTrackingLifecycleState.FINISHED -> this
		LogicalTrackingLifecycleState.STOP_CANDIDATE -> this
		LogicalTrackingLifecycleState.ACTIVE,
		LogicalTrackingLifecycleState.PAUSED -> transition(
			lifecycleState = LogicalTrackingLifecycleState.STOP_CANDIDATE,
			changedAtEpochMs = changedAtEpochMs,
			stopCandidate = TrackingStopCandidate(
				reason = reason,
				requestedAtEpochMs = changedAtEpochMs,
			),
		)
	}

	/** Cancels a pending stop deliberately; a finished session cannot be resumed. */
	fun withdrawStopCandidate(changedAtEpochMs: Long? = null): ActiveTrackingSessionDescriptor = when (lifecycleState) {
		LogicalTrackingLifecycleState.STOP_CANDIDATE -> transition(
			lifecycleState = LogicalTrackingLifecycleState.ACTIVE,
			changedAtEpochMs = changedAtEpochMs,
			stopCandidate = null,
		)
		else -> this
	}

	fun finish(changedAtEpochMs: Long? = null): ActiveTrackingSessionDescriptor = when (lifecycleState) {
		LogicalTrackingLifecycleState.FINISHED -> this
		else -> transition(
			lifecycleState = LogicalTrackingLifecycleState.FINISHED,
			changedAtEpochMs = changedAtEpochMs,
			stopCandidate = null,
		)
	}

	private fun transition(
		serviceRunId: String = this.serviceRunId,
		lifecycleState: LogicalTrackingLifecycleState = this.lifecycleState,
		changedAtEpochMs: Long?,
		stopCandidate: TrackingStopCandidate? = this.stopCandidate,
	): ActiveTrackingSessionDescriptor = copy(
		serviceRunId = serviceRunId,
		lifecycleState = lifecycleState,
		lifecycleRevision = lifecycleRevision + 1L,
		lifecycleChangedAtEpochMs = changedAtEpochMs,
		stopCandidate = stopCandidate,
	)
}

sealed interface ActiveTrackingSessionStoreResult {
	data class Success(
		val descriptor: ActiveTrackingSessionDescriptor?,
	) : ActiveTrackingSessionStoreResult

	data class Failure(
		val cause: Throwable,
	) : ActiveTrackingSessionStoreResult
}

interface ActiveTrackingSessionStore {
	suspend fun read(): ActiveTrackingSessionStoreResult

	suspend fun save(descriptor: ActiveTrackingSessionDescriptor): ActiveTrackingSessionStoreResult

	suspend fun clear(): ActiveTrackingSessionStoreResult

	/**
	 * Clears only if the durable descriptor still belongs to [descriptor]'s service run.
	 *
	 * The default keeps existing lightweight/fake implementations source-compatible.  The Android
	 * store overrides it atomically, which prevents a completed old teardown from clearing a newer
	 * service run that has already started for the same logical session.
	 */
	suspend fun clearIfCurrent(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = when (val current = read()) {
		is ActiveTrackingSessionStoreResult.Failure -> current
		is ActiveTrackingSessionStoreResult.Success -> {
			val currentDescriptor = current.descriptor
			if (
				currentDescriptor?.logicalTrackingId == descriptor.logicalTrackingId &&
				currentDescriptor.serviceRunId == descriptor.serviceRunId &&
				currentDescriptor.lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE &&
				currentDescriptor.lifecycleRevision == descriptor.lifecycleRevision
			) {
				clear()
			} else {
				current
			}
		}
	}

	/**
	 * Clears the complete descriptor only when every persisted identity and lifecycle field still
	 * equals [descriptor]. Unlike [clearIfCurrent], this is not limited to STOP_CANDIDATE and is
	 * intended for a recovery owner that has already finalized that exact stale lifecycle in Room.
	 */
	suspend fun clearExact(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = when (val current = read()) {
		is ActiveTrackingSessionStoreResult.Failure -> current
		is ActiveTrackingSessionStoreResult.Success ->
			if (current.descriptor == descriptor) clear() else current
	}
}
