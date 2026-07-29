package com.adsamcik.tracker.tracker.control

/**
 * A stable identity for one logical tracking intent. It survives service recreation and may own
 * several persisted [SessionSegment](../engine/src/main/java/com/adsamcik/tracker/tracker/component/consumer/SessionTrackerComponent.kt)
 * rows. The control engine deliberately does not own Room entities.
 */
@JvmInline
value class LogicalTrackingId(val value: String) {
	init {
		require(value.isNotBlank()) { "Logical tracking id must not be blank" }
	}
}

/** Who requested the logical tracking session. */
enum class TrackingSessionOrigin {
	USER,
	AUTOMATIC,
}

/** Lifecycle is intentionally independent from motion, quality, and acquisition decisions. */
enum class LogicalLifecycleState {
	IDLE,
	ACTIVE,
	PAUSED,
	STOP_CANDIDATE,
	FINISHED,
}

/** Motion is an evidence interpretation, never a session-lifetime decision by itself. */
enum class MotionState {
	UNKNOWN,
	STATIONARY,
	MOVING,
	UNCERTAIN,
}

/** Whether usable location evidence is currently observable. */
enum class LocationObservabilityState {
	UNKNOWN,
	AVAILABLE,
	DEGRADED,
	UNAVAILABLE,
}

/**
 * The request actually desired by the decision engine. Android adapters map this to Fused or
 * LocationManager APIs; keeping the enum Android-free makes replays deterministic.
 */
enum class LocationAcquisitionMode {
	DISABLED,
	PASSIVE,
	LOW_POWER,
	BALANCED,
	HIGH_ACCURACY,
	PROBE,
}

/** Continuity is explicit so downstream distance code cannot silently bridge a known gap. */
enum class TrackingContinuity {
	CONTINUOUS,
	GAP_OPEN,
}

/** A provider-independent 2-D point. */
data class GeoPoint(
	val latitude: Double,
	val longitude: Double,
) {
	init {
		require(latitude.isFinite() && longitude.isFinite()) { "Coordinates must be finite" }
		require(latitude in -90.0..90.0) { "Latitude is out of range" }
		require(longitude in -180.0..180.0) { "Longitude is out of range" }
	}
}

/** Canonical control input timestamped in one monotonic clock domain. */
data class ControlInput(
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val clockDomainId: String,
	val payload: ControlEvidence,
) {
	init {
		require(wallTimeMs >= 0L) { "Wall time must be non-negative" }
		require(elapsedRealtimeNanos >= 0L) { "Elapsed realtime must be non-negative" }
		require(clockDomainId.isNotBlank()) { "Clock domain id must not be blank" }
	}
}

/**
 * Inputs accepted by the shared evidence ledger. No payload doubles as a command to Android;
 * requests are outputs of the acquisition reducer.
 */
sealed interface ControlEvidence {
	data class SessionStarted(
		val logicalTrackingId: LogicalTrackingId,
		val origin: TrackingSessionOrigin,
	) : ControlEvidence

	data class PauseRequested(
		val reason: String,
	) : ControlEvidence

	data class ResumeRequested(
		val reason: String,
	) : ControlEvidence

	/** A user/API stop is authoritative. */
	data class FinishRequested(
		val reason: String,
	) : ControlEvidence

	/**
	 * An automatic classification can only create a candidate. A later ledger event must observe
	 * the grace period before an automatic session finishes.
	 */
	data class AutomaticStopEvidence(
		val reason: String,
		val confidence: Int,
	) : ControlEvidence {
		init {
			require(confidence in 0..100) { "Confidence must be in 0..100" }
		}
	}

	data class ActivityEvidence(
		val category: ActivityCategory,
		val confidence: Int,
	) : ControlEvidence {
		init {
			require(confidence in 0..100) { "Confidence must be in 0..100" }
		}
	}

	data class StepEvidence(
		val delta: Int,
	) : ControlEvidence {
		init {
			require(delta >= 0) { "Step delta must be non-negative" }
		}
	}

	/** Raw provider delivery. Rejected ingress samples remain auditable but are never measured. */
	data class LocationObservation(
		val sourceEventId: String,
		val position: GeoPoint?,
		val horizontalAccuracyMeters: Double?,
		val speedMetersPerSecond: Double? = null,
		val ingressAccepted: Boolean,
		val provider: String? = null,
		val acquisitionMode: LocationAcquisitionMode? = null,
		val rejectionReason: String? = null,
	) : ControlEvidence {
		init {
			require(sourceEventId.isNotBlank()) { "Source event id must not be blank" }
			horizontalAccuracyMeters?.let {
				require(it.isFinite() && it >= 0.0) { "Horizontal accuracy must be finite and non-negative" }
			}
			speedMetersPerSecond?.let {
				require(it.isFinite() && it >= 0.0) { "Speed must be finite and non-negative" }
			}
			if (ingressAccepted) {
				require(position != null) { "Accepted location observations require a position" }
			}
		}
	}

	data class CuratedLocationDecision(
		val sourceEventId: String,
		val accepted: Boolean,
		val reason: String? = null,
	) : ControlEvidence {
		init {
			require(sourceEventId.isNotBlank()) { "Source event id must not be blank" }
		}
	}

	/** Legacy policy intent is evidence only; the control engine can choose a safer request. */
	data class PolicyIntent(
		val locationEnabled: Boolean,
		val preferredMode: LocationAcquisitionMode,
		val reason: String,
	) : ControlEvidence

	data class ProviderAvailability(
		val available: Boolean,
		val reason: String,
	) : ControlEvidence

	data class ProbeResult(
		val succeeded: Boolean,
		val reason: String,
	) : ControlEvidence

	/** Allows time-based expiry (observability/stop grace/probe) without inventing sensor data. */
	data class Tick(
		val reason: String = "HEARTBEAT",
	) : ControlEvidence
}

enum class ActivityCategory {
	MOVING,
	STATIONARY,
	UNKNOWN,
}

/** A concrete request that an Android adapter may apply, or record as shadow-only. */
data class AcquisitionRequest(
	val mode: LocationAcquisitionMode,
	val intervalMs: Long,
	val minDistanceMeters: Int,
	val probeDurationMs: Long? = null,
	val reason: String,
) {
	init {
		require(intervalMs >= 0L) { "Interval must not be negative" }
		require(minDistanceMeters >= 0) { "Minimum distance must not be negative" }
		probeDurationMs?.let { require(it > 0L) { "Probe duration must be positive" } }
		if (mode == LocationAcquisitionMode.PROBE) {
			require(probeDurationMs != null) { "Probe requests require a duration" }
		}
	}
}

enum class HorizontalEstimatorDecision {
	INITIALIZED,
	ACCEPTED,
	REJECTED_OUTLIER,
	REJECTED_OUT_OF_ORDER,
	GAP_RESET,
	IGNORED,
}

/** Result of the shadow covariance-carrying horizontal estimator. */
data class HorizontalEstimatorResult(
	val sourceEventId: String,
	val decision: HorizontalEstimatorDecision,
	val position: GeoPoint? = null,
	val horizontalUncertaintyMeters: Double? = null,
	val speedMetersPerSecond: Double? = null,
	val normalizedInnovationSquared: Double? = null,
	val gapOpened: Boolean = false,
	val reason: String? = null,
)

enum class ControlTransitionKind {
	LIFECYCLE,
	MOTION,
	OBSERVABILITY,
	ACQUISITION,
	CONTINUITY,
	ESTIMATOR,
	LATE_INPUT_REJECTED,
	DUPLICATE_INPUT_REJECTED,
}

/** Named state transition; this is the explainability boundary used by trace export/replay. */
data class ControlTransition(
	val kind: ControlTransitionKind,
	val from: String?,
	val to: String?,
	val reason: String,
)

data class TrackingDecisionSnapshot(
	val logicalTrackingId: LogicalTrackingId?,
	val sessionOrigin: TrackingSessionOrigin?,
	val lifecycle: LogicalLifecycleState,
	val motion: MotionState,
	val observability: LocationObservabilityState,
	val acquisition: AcquisitionRequest,
	val continuity: TrackingContinuity,
)

/** One ordered ledger decision. All outputs are deterministic functions of prior evidence. */
data class ControlOutput(
	val ledgerSequence: Long,
	val input: ControlInput,
	val transitions: List<ControlTransition>,
	val snapshot: TrackingDecisionSnapshot,
	val estimator: HorizontalEstimatorResult? = null,
)

/** Explicit outcome for an input outside the bounded reordering window. */
data class LateControlInput(
	val input: ControlInput,
	val ledgerSequence: Long,
	val latestSeenElapsedRealtimeNanos: Long,
	val allowedReorderNanos: Long,
)

/** Explicit outcome when a source-identified provider observation has already entered the ledger. */
data class DuplicateControlInput(
	val input: ControlInput,
	val ledgerSequence: Long,
	val originalLedgerSequence: Long,
)

/**
 * Conservative defaults. They are deliberately configuration rather than product claims; replay
 * traces and on-device battery experiments must tune them before any production promotion.
 */
data class TrackingDecisionConfig(
	val maxReorderNanos: Long = 5_000_000_000L,
	/** Bounded idempotence window for immutable provider source-event IDs. */
	val maxDuplicateLocationSourceIds: Int = 10_000,
	val continuityGapNanos: Long = 120_000_000_000L,
	val observabilityTimeoutNanos: Long = 90_000_000_000L,
	val evidenceTtlNanos: Long = 45_000_000_000L,
	val automaticStopGraceNanos: Long = 90_000_000_000L,
	val minimumAcquisitionDwellNanos: Long = 15_000_000_000L,
	val probeDurationNanos: Long = 20_000_000_000L,
	val probeCooldownNanos: Long = 120_000_000_000L,
	val dutyWindowNanos: Long = 15 * 60_000_000_000L,
	val maximumActiveDutyNanos: Long = 5 * 60_000_000_000L,
	val estimatorGapNanos: Long = 120_000_000_000L,
	val estimatorNisThreshold: Double = 16.0,
) {
	init {
		require(maxReorderNanos >= 0L)
		require(maxDuplicateLocationSourceIds > 0)
		require(continuityGapNanos > 0L)
		require(observabilityTimeoutNanos > 0L)
		require(evidenceTtlNanos > 0L)
		require(automaticStopGraceNanos > 0L)
		require(minimumAcquisitionDwellNanos >= 0L)
		require(probeDurationNanos > 0L)
		require(probeCooldownNanos >= 0L)
		require(dutyWindowNanos > 0L)
		require(maximumActiveDutyNanos in 0L..dutyWindowNanos)
		require(estimatorGapNanos > 0L)
		require(estimatorNisThreshold > 0.0 && estimatorNisThreshold.isFinite())
	}
}
