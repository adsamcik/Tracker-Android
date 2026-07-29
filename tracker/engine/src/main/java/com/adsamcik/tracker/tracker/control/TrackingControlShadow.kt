package com.adsamcik.tracker.tracker.control

import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.LocationIngressDisposition
import com.adsamcik.tracker.shared.base.data.LocationProviderObservation
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.TrackingClockDomain
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import com.adsamcik.tracker.tracker.control.AcquisitionRequest
import com.adsamcik.tracker.tracker.control.ActivityCategory
import com.adsamcik.tracker.tracker.control.ControlEvidence
import com.adsamcik.tracker.tracker.control.ControlInput
import com.adsamcik.tracker.tracker.control.ControlOutput
import com.adsamcik.tracker.tracker.control.GeoPoint
import com.adsamcik.tracker.tracker.control.LogicalTrackingId
import com.adsamcik.tracker.tracker.control.LocationAcquisitionMode as DecisionAcquisitionMode
import com.adsamcik.tracker.tracker.control.TrackingDecisionEngine
import com.adsamcik.tracker.tracker.control.TrackingDecisionSnapshot
import com.adsamcik.tracker.tracker.control.TrackingSessionOrigin

/**
 * Rollout controls for the uncertainty-aware architecture.
 *
 * All production defaults are intentionally false. The flags are constructor-injected into the
 * orchestrator in tests/debug integrations, so promotion of one layer never accidentally enables
 * another. The horizontal estimator is evidence-only; there is deliberately no canonical-output
 * switch until a separately approved canonical integration exists.
 */
internal data class TrackingDecisionFeatureFlags(
	val shadowEnabled: Boolean = false,
	val applyAcquisitionRequests: Boolean = false,
) {
	init {
		require(!applyAcquisitionRequests || shadowEnabled) {
			"Acquisition application requires the shadow decision engine"
		}
	}
}

/** Debug/research boundary; a sink may turn these into V2 research evidence or encrypted export. */
internal fun interface TrackingControlOutputSink {
	fun onDecision(output: ControlOutput)
}

internal object NoOpTrackingControlOutputSink : TrackingControlOutputSink {
	override fun onDecision(output: ControlOutput) = Unit
}

/** Whether an acquisition command came from the reducer or an explicit adapter safety fallback. */
internal enum class ControlAcquisitionCommandOrigin {
	DECISION,
	ADAPTER_PROBE_DEADLINE,
}

/**
 * A reducer request paired with its stable decision correlation. This contains no coordinates;
 * it is the only shape the Android adapter is allowed to apply or report.
 */
internal data class ControlAcquisitionCommand(
	val requestId: String,
	val logicalTrackingId: String,
	val decisionLedgerSequence: Long,
	val request: AcquisitionRequest,
	val decisionEpochMs: Long,
	val decisionElapsedNanos: Long,
	val clockDomainId: String,
	val origin: ControlAcquisitionCommandOrigin = ControlAcquisitionCommandOrigin.DECISION,
) {
	init {
		require(requestId.isNotBlank())
		require(logicalTrackingId.isNotBlank())
		require(decisionLedgerSequence >= 0L)
		require(decisionEpochMs >= 0L)
		require(decisionElapsedNanos >= 0L)
		require(clockDomainId.isNotBlank())
	}
}

internal enum class ControlAcquisitionApplyOutcome {
	APPLIED,
	NO_CHANGE,
	FAILED,
}

/** Actual platform result correlated to [ControlAcquisitionCommand.requestId]. */
internal data class ControlAcquisitionApplyResult(
	val command: ControlAcquisitionCommand,
	val outcome: ControlAcquisitionApplyOutcome,
	val applied: AcquisitionRequest? = null,
	val reason: String,
	val eventEpochMs: Long,
	val eventElapsedNanos: Long,
	val clockDomainId: String,
) {
	init {
		require(reason.isNotBlank())
		require(eventEpochMs >= 0L)
		require(eventElapsedNanos >= 0L)
		require(clockDomainId.isNotBlank())
		require(outcome != ControlAcquisitionApplyOutcome.APPLIED || applied != null)
	}
}

/** Optional debug/research observer for actual Android acquisition application outcomes. */
internal fun interface TrackingControlAcquisitionOutcomeSink {
	fun onAcquisitionOutcome(result: ControlAcquisitionApplyResult)
}

internal object NoOpTrackingControlAcquisitionOutcomeSink : TrackingControlAcquisitionOutcomeSink {
	override fun onAcquisitionOutcome(result: ControlAcquisitionApplyResult) = Unit
}

internal fun controlAcquisitionRequestId(logicalTrackingId: String, ledgerSequence: Long): String =
	"$logicalTrackingId:$ledgerSequence"

/**
 * Thin adapter from Android collection objects to the pure [TrackingDecisionEngine]. It never
 * changes the existing trigger, estimator, or session pipeline; callers decide whether to apply a
 * resulting [AcquisitionRequest] only after the shadow gate has passed.
 */
internal class TrackingControlShadow(
	private val flags: TrackingDecisionFeatureFlags = TrackingDecisionFeatureFlags(),
	private val engine: TrackingDecisionEngine = TrackingDecisionEngine(),
	private val outputSink: TrackingControlOutputSink = NoOpTrackingControlOutputSink,
) {
	private var latestSnapshot: TrackingDecisionSnapshot? = null
	private var latestAcquisitionCommand: ControlAcquisitionCommand? = null
	fun begin(
		logicalTrackingId: String,
		isUserInitiated: Boolean,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		clockDomainId: String = TrackingClockDomain.currentId(),
		initialTier: PolicyTier,
		locationEnabled: Boolean = true,
	) {
		if (!flags.shadowEnabled) return
		latestAcquisitionCommand = null
		emit(
			ControlInput(
				wallTimeMs = wallTimeMs.coerceAtLeast(0L),
				elapsedRealtimeNanos = elapsedRealtimeNanos.coerceAtLeast(0L),
				clockDomainId = clockDomainId,
				payload = ControlEvidence.SessionStarted(
					logicalTrackingId = LogicalTrackingId(logicalTrackingId),
					origin = if (isUserInitiated) TrackingSessionOrigin.USER else TrackingSessionOrigin.AUTOMATIC,
				),
			),
		)
		onPolicyTier(
			tier = initialTier,
			wallTimeMs = wallTimeMs,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			clockDomainId = clockDomainId,
			locationEnabled = locationEnabled,
			reason = "INITIAL_TIER",
		)
		flushPending()
	}

	fun onRawLocationObservations(cycle: TrackingCycle) {
		if (!flags.shadowEnabled) return
		cycle.locationObservations.forEach { observation ->
			emit(observation.toControlInput())
		}
	}

	fun onCuratedLocationDecision(
		cycle: TrackingCycle,
		accepted: Boolean,
		reason: String? = null,
	) {
		if (!flags.shadowEnabled) return
		val metadata = cycle.location?.lastFixMetadata ?: return
		val sourceEventId = metadata.toControlSourceEventId()
		emit(
			ControlInput(
				wallTimeMs = cycle.timestampMs.coerceAtLeast(0L),
				elapsedRealtimeNanos = cycle.elapsedRealtimeNanos.coerceAtLeast(0L),
				clockDomainId = metadata.clockDomainId.toControlClockDomainId(),
				payload = ControlEvidence.CuratedLocationDecision(sourceEventId, accepted, reason),
			),
		)
	}

	fun onCycleSignals(cycle: TrackingCycle) {
		if (!flags.shadowEnabled) return
		val wallTimeMs = cycle.timestampMs.coerceAtLeast(0L)
		val elapsedRealtimeNanos = cycle.elapsedRealtimeNanos.coerceAtLeast(0L)
		val clockDomainId = TrackingClockDomain.currentId()
		if (cycle.activityFresh) {
			cycle.activity?.let { activity ->
				emit(
					ControlInput(
						wallTimeMs = wallTimeMs,
						elapsedRealtimeNanos = cycle.activitySourceElapsedRealtimeNanos
							?.coerceAtLeast(0L) ?: elapsedRealtimeNanos,
						clockDomainId = clockDomainId,
						payload = ControlEvidence.ActivityEvidence(
							category = activity.groupedActivity.toControlActivity(),
							confidence = activity.confidence.coerceIn(0, 100),
						),
					),
				)
			}
		}
		cycle.stepDelta?.takeIf { it > 0 }?.let { delta ->
			emit(
				ControlInput(
					wallTimeMs = wallTimeMs,
					elapsedRealtimeNanos = cycle.stepWindowEndElapsedRealtimeNanos
						?.coerceAtLeast(0L) ?: elapsedRealtimeNanos,
					clockDomainId = clockDomainId,
					payload = ControlEvidence.StepEvidence(delta),
				),
			)
		}
		emit(
			ControlInput(
				wallTimeMs = wallTimeMs,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				clockDomainId = clockDomainId,
				payload = ControlEvidence.Tick("COLLECTION_CYCLE"),
			),
		)
	}

	fun onPolicyTier(
		tier: PolicyTier,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		clockDomainId: String = TrackingClockDomain.currentId(),
		locationEnabled: Boolean = tier != PolicyTier.OFF,
		reason: String,
	) {
		if (!flags.shadowEnabled) return
		emit(
			ControlInput(
				wallTimeMs = wallTimeMs.coerceAtLeast(0L),
				elapsedRealtimeNanos = elapsedRealtimeNanos.coerceAtLeast(0L),
				clockDomainId = clockDomainId,
				payload = ControlEvidence.PolicyIntent(
					locationEnabled = locationEnabled,
					preferredMode = tier.toDecisionMode(),
					reason = reason,
				),
			),
		)
		// Policy is an authoritative control barrier. Apply it immediately instead of leaving the
		// live adapter's latest command stranded in the event-time reorder tail.
		flushPending()
	}

	fun onPolicy(
		policy: TrackingPolicy,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		clockDomainId: String = TrackingClockDomain.currentId(),
		locationEnabled: Boolean = true,
	) = onPolicyTier(
		tier = policy.toPolicyTier(),
		wallTimeMs = wallTimeMs,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		clockDomainId = clockDomainId,
		locationEnabled = locationEnabled,
		reason = "LEGACY_POLICY:${policy.name}",
	)

	fun onProviderAvailability(
		available: Boolean,
		reason: String,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		clockDomainId: String = TrackingClockDomain.currentId(),
	) {
		if (!flags.shadowEnabled) return
		emit(
			ControlInput(
				wallTimeMs = wallTimeMs.coerceAtLeast(0L),
				elapsedRealtimeNanos = elapsedRealtimeNanos.coerceAtLeast(0L),
				clockDomainId = clockDomainId,
				payload = ControlEvidence.ProviderAvailability(available, reason),
			),
		)
		// Provider loss can be the final callback before a long outage, so no future location event
		// may arrive to advance the event-time watermark.
		flushPending()
	}

	fun finish(
		reason: String,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		clockDomainId: String = TrackingClockDomain.currentId(),
	) {
		if (!flags.shadowEnabled) return
		emit(
			ControlInput(
				wallTimeMs = wallTimeMs.coerceAtLeast(0L),
				elapsedRealtimeNanos = elapsedRealtimeNanos.coerceAtLeast(0L),
				clockDomainId = clockDomainId,
				payload = ControlEvidence.FinishRequested(reason),
			),
		)
		flushPending()
		latestAcquisitionCommand = null
	}

	fun snapshot(): TrackingDecisionSnapshot? = if (flags.shadowEnabled) latestSnapshot ?: engine.snapshot() else null

	fun latestAcquisitionCommand(): ControlAcquisitionCommand? =
		if (flags.shadowEnabled) latestAcquisitionCommand else null

	private fun emit(input: ControlInput) {
		engine.accept(input).forEach(::recordOutput)
	}

	private fun flushPending() {
		engine.flush().forEach(::recordOutput)
	}

	private fun recordOutput(output: ControlOutput) {
		latestSnapshot = output.snapshot
		if (output.transitions.any { it.kind == ControlTransitionKind.ACQUISITION }) {
			output.snapshot.logicalTrackingId?.value?.let { logicalTrackingId ->
				latestAcquisitionCommand = ControlAcquisitionCommand(
					requestId = controlAcquisitionRequestId(logicalTrackingId, output.ledgerSequence),
					logicalTrackingId = logicalTrackingId,
					decisionLedgerSequence = output.ledgerSequence,
					request = output.snapshot.acquisition,
					decisionEpochMs = output.input.wallTimeMs,
					decisionElapsedNanos = output.input.elapsedRealtimeNanos,
					clockDomainId = output.input.clockDomainId,
				)
			}
		}
		outputSink.onDecision(output)
	}

	private fun LocationProviderObservation.toControlInput(): ControlInput {
		val providerLocation = this.location
		val isValidIngress = ingressDisposition == LocationIngressDisposition.DELIVERED_VALID
		val point = if (isValidIngress) {
			runCatching { GeoPoint(providerLocation.latitude, providerLocation.longitude) }.getOrNull()
		} else {
			null
		}
		return ControlInput(
			wallTimeMs = metadata.receivedAtMs.coerceAtLeast(0L),
			elapsedRealtimeNanos = metadata.receivedElapsedRealtimeNanos.coerceAtLeast(0L),
			clockDomainId = metadata.clockDomainId.toControlClockDomainId(),
			payload = ControlEvidence.LocationObservation(
				sourceEventId = metadata.toControlSourceEventId(),
				position = point,
				horizontalAccuracyMeters = if (providerLocation.hasAccuracy()) providerLocation.accuracy.toDouble() else null,
				speedMetersPerSecond = if (providerLocation.hasSpeed()) providerLocation.speed.toDouble() else null,
				ingressAccepted = isValidIngress && point != null,
				provider = providerLocation.provider,
				acquisitionMode = metadata.requestPriority.toDecisionMode(),
				rejectionReason = ingressDisposition.name,
			),
		)
	}
}

/** Legacy/imported cycles can omit—or occasionally serialize an empty—clock-domain ID. */
private fun String?.toControlClockDomainId(): String =
	takeUnless { it.isNullOrBlank() } ?: TrackingClockDomain.currentId()

/** Preserve raw/curated correlation for older records that predate immutable source IDs. */
private fun com.adsamcik.tracker.shared.base.data.LocationFixMetadata.toControlSourceEventId(): String =
	sourceEventId?.takeUnless(String::isBlank)
		?: "legacy-source:${callbackId?.takeUnless(String::isBlank) ?: receivedElapsedRealtimeNanos}:$batchIndex:$receivedAtMs"

private fun GroupedActivity.toControlActivity(): ActivityCategory = when (this) {
	GroupedActivity.STILL -> ActivityCategory.STATIONARY
	GroupedActivity.ON_FOOT,
	GroupedActivity.IN_VEHICLE,
	-> ActivityCategory.MOVING
	GroupedActivity.UNKNOWN -> ActivityCategory.UNKNOWN
}

private fun com.adsamcik.tracker.shared.base.data.LocationRequestPriority.toDecisionMode(): DecisionAcquisitionMode? =
	when (this) {
		com.adsamcik.tracker.shared.base.data.LocationRequestPriority.PASSIVE -> DecisionAcquisitionMode.PASSIVE
		com.adsamcik.tracker.shared.base.data.LocationRequestPriority.LOW_POWER -> DecisionAcquisitionMode.LOW_POWER
		com.adsamcik.tracker.shared.base.data.LocationRequestPriority.BALANCED -> DecisionAcquisitionMode.BALANCED
		com.adsamcik.tracker.shared.base.data.LocationRequestPriority.HIGH_ACCURACY -> DecisionAcquisitionMode.HIGH_ACCURACY
		com.adsamcik.tracker.shared.base.data.LocationRequestPriority.UNKNOWN -> null
	}

private fun PolicyTier.toDecisionMode(): DecisionAcquisitionMode = when (this) {
	PolicyTier.OFF -> DecisionAcquisitionMode.DISABLED
	PolicyTier.AMBIENT -> DecisionAcquisitionMode.PASSIVE
	PolicyTier.ACTIVE -> DecisionAcquisitionMode.BALANCED
	PolicyTier.PRECISION -> DecisionAcquisitionMode.HIGH_ACCURACY
}

private fun TrackingPolicy.toPolicyTier(): PolicyTier = when (this) {
	TrackingPolicy.PASSIVE_LOW -> PolicyTier.AMBIENT
	TrackingPolicy.MOVEMENT_SUSPECTED,
	TrackingPolicy.ACTIVE_MODERATE,
	-> PolicyTier.ACTIVE
	TrackingPolicy.ACTIVE_ELEVATED,
	TrackingPolicy.USER_INITIATED,
	-> PolicyTier.PRECISION
}
