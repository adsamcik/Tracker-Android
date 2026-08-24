package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/** Converts exactly one parsed GMS callback into exactly one atomic source delivery. */
class ActivitySourceDeliveryFactory @Inject constructor() {
	fun create(
		batch: ActivityRecognitionEvidenceBatch,
		identity: ActivityRegistrationIdentity,
		automationAuthority: ActivityAutomationEpochEntity,
	): ActivitySourceDelivery {
		require(batch.eventCount > 0)
		val selectedAutomaticTransitionIndex = batch.transitions.withIndex()
			.filter { (_, evidence) ->
				ActivityTransitionData(evidence.activityType, evidence.transitionType) in
					batch.automaticTransitions
			}
			.maxWithOrNull(
				compareBy<IndexedValue<ActivityTransitionEvidence>>(
					{ it.value.providerElapsedRealtimeNanos },
					IndexedValue<ActivityTransitionEvidence>::index,
				),
			)
			?.index
		val events = buildList {
			batch.recognitions.forEachIndexed { index, evidence ->
				add(ActivitySourceEvent.Recognition(index, evidence))
			}
			batch.transitions.forEachIndexed { index, evidence ->
				add(
					ActivitySourceEvent.Transition(
						originalIndex = index,
						evidence = evidence,
						providerSameTimeOrder = batch.transitions.take(index).count { prior ->
							prior.providerElapsedRealtimeNanos == evidence.providerElapsedRealtimeNanos
						},
					),
				)
			}
		}.sortedWith(
			compareBy<ActivitySourceEvent>(
				ActivitySourceEvent::providerElapsedRealtimeNanos,
				ActivitySourceEvent::kindCode,
				ActivitySourceEvent::stableActivityTypeCode,
				ActivitySourceEvent::detailCode,
			),
		)
		val canonicalBytes = ByteBuffer.allocate(HEADER_BYTES + events.size * EVENT_BYTES)
			.order(ByteOrder.BIG_ENDIAN)
			.putInt(CANONICAL_MAGIC)
			.putInt(CANONICAL_VERSION)
			.putInt(events.size)
			.apply {
					events.forEach { event ->
						putLong(event.providerElapsedRealtimeNanos)
						putInt(event.kindCode)
						putInt(event.stableActivityTypeCode)
						putInt(event.detailCode)
						putInt(event.providerSameTimeOrder)
					}
			}
			.array()
		return ActivitySourceDelivery(
			candidate = SourceDeliveryCandidate(
				identity = sourceDeliveryIdentity(canonicalBytes),
				units = events.mapIndexed { unitIndex, event ->
					SourceDeliveryUnit(
						unitIndex = unitIndex,
						evidence = event.toCandidate(
							batch = batch,
							identity = identity,
							automationAuthority = automationAuthority,
							automationEligible = when (event) {
								is ActivitySourceEvent.Recognition -> batch.automaticRecognitionEligible
								is ActivitySourceEvent.Transition ->
									event.originalIndex == selectedAutomaticTransitionIndex
							},
						),
					)
				},
			),
			originalEvents = events.map(ActivitySourceEvent::originalEvent),
		)
	}

	private fun ActivitySourceEvent.toCandidate(
		batch: ActivityRecognitionEvidenceBatch,
		identity: ActivityRegistrationIdentity,
		automationAuthority: ActivityAutomationEpochEntity,
		automationEligible: Boolean,
	): SourceEvidenceCandidate<out SourcePayload> {
		val observedNanos = providerElapsedRealtimeNanos
		require(observedNanos <= batch.receivedElapsedRealtimeNanos) {
			"Provider observation time cannot be later than callback receipt time"
		}
		val delayNanos = batch.receivedElapsedRealtimeNanos - observedNanos
		val acquiredAtMs = (batch.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND)
			.coerceAtLeast(0L)
		val qualityFlags = buildSet {
			if (delayNanos >= BATCHED_AFTER_NANOS) add(SourceQualityFlag.BATCHED)
		}
		return SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId(identity.sourceInstanceId),
			registrationGeneration = identity.registrationGeneration,
			physicalConfigurationFingerprint = identity.physicalConfigurationFingerprint,
			authorizationRevision = null,
			registrationPurposeEligibilityMask = 0L,
			registrationEligibilityFingerprint = null,
			sourceSequence = 0L,
			configRevision = identity.registrationGeneration,
			planAttribution = PlanAttribution.RECEIVE_TIME_ONLY,
			clockDomainId = identity.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos,
			receivedElapsedRealtimeNanos = batch.receivedElapsedRealtimeNanos,
			wallTimeMs = acquiredAtMs,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = identity.collectedDataEpoch,
			activityAutomationEpoch = if (automationEligible) {
				automationAuthority.eligibleEpoch(identity.clockDomainId, observedNanos)
			} else {
				null
			},
			acquiredAtMs = acquiredAtMs,
			quality = SourceQuality(confidence = confidence, flags = qualityFlags),
			payloadVersion = 1,
			payload = payload,
		)
	}

	private fun ActivityAutomationEpochEntity.eligibleEpoch(
		observationClockDomainId: String,
		observedElapsedRealtimeNanos: Long,
	): Long? = epoch.takeIf {
		bootClockDomainId == observationClockDomainId &&
			observedElapsedRealtimeNanos >= effectiveElapsedRealtimeNanos &&
			automaticControlEnabled &&
			!lockSuppressed &&
			!powerSaverSuppressed
	}

	private sealed interface ActivitySourceEvent {
		val providerElapsedRealtimeNanos: Long
		val kindCode: Int
		val stableActivityTypeCode: Int
		val detailCode: Int
		val providerSameTimeOrder: Int
		val confidence: Float
		val payload: SourcePayload
		val originalEvent: ActivityOriginalEvent

		data class Recognition(
			val originalIndex: Int,
			val evidence: ActivityRecognitionEvidence,
		) : ActivitySourceEvent {
			override val providerElapsedRealtimeNanos = evidence.providerElapsedRealtimeNanos
			override val kindCode = KIND_RECOGNITION
			override val stableActivityTypeCode = stableActivityCode(evidence.activityType)
			override val detailCode = evidence.confidencePercent
			override val providerSameTimeOrder = 0
			override val confidence = evidence.confidencePercent / 100f
			override val payload = ActivityRecognitionPayload(
				activityType = stableActivityTypeCode,
				confidencePercent = evidence.confidencePercent,
				providerElapsedRealtimeNanos = providerElapsedRealtimeNanos,
			)
			override val originalEvent = ActivityOriginalEvent.Recognition(originalIndex)
		}

		data class Transition(
			val originalIndex: Int,
			val evidence: ActivityTransitionEvidence,
			override val providerSameTimeOrder: Int,
		) : ActivitySourceEvent {
			override val providerElapsedRealtimeNanos = evidence.providerElapsedRealtimeNanos
			override val kindCode = KIND_TRANSITION
			override val stableActivityTypeCode = stableActivityCode(evidence.activityType)
			override val detailCode = evidence.transitionType.value
			override val confidence = 1f
			override val payload = ActivityTransitionPayload(
				activityType = stableActivityTypeCode,
				transitionType = evidence.transitionType.value,
				providerElapsedRealtimeNanos = providerElapsedRealtimeNanos,
			)
			override val originalEvent = ActivityOriginalEvent.Transition(originalIndex)
		}
	}

	private companion object {
		const val CANONICAL_MAGIC = 0x41435456 // ACTV
		const val CANONICAL_VERSION = 2
		const val HEADER_BYTES = Int.SIZE_BYTES * 3
		const val EVENT_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES * 4
		const val KIND_RECOGNITION = 0
		const val KIND_TRANSITION = 1
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5L * 1_000L * NANOS_PER_MILLISECOND
	}
}

data class ActivitySourceDelivery(
	val candidate: SourceDeliveryCandidate,
	val originalEvents: List<ActivityOriginalEvent>,
)

sealed interface ActivityOriginalEvent {
	data class Recognition(val index: Int) : ActivityOriginalEvent
	data class Transition(val index: Int) : ActivityOriginalEvent
}

internal fun stableActivityCode(activityType: DetectedActivityType): Int = when (activityType) {
	DetectedActivityType.STILL -> StableActivityTypeCode.STILL
	DetectedActivityType.WALKING -> StableActivityTypeCode.WALKING
	DetectedActivityType.RUNNING -> StableActivityTypeCode.RUNNING
	DetectedActivityType.ON_BICYCLE -> StableActivityTypeCode.ON_BICYCLE
	DetectedActivityType.IN_VEHICLE -> StableActivityTypeCode.IN_VEHICLE
	DetectedActivityType.ON_FOOT -> StableActivityTypeCode.ON_FOOT
	DetectedActivityType.TILTING -> StableActivityTypeCode.TILTING
	DetectedActivityType.UNKNOWN -> StableActivityTypeCode.UNKNOWN
}
