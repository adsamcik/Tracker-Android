package com.adsamcik.tracker.tracker.source.ingress

import androidx.room.withTransaction
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressResult
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import dev.tracebox.Tracebox
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Durable adapter for the app-scoped activity callback PendingIntent. */
@Singleton
class RoomActivityRecognitionEventIngress @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val sourceIngress: DurableSourceIngress,
	private val sourcePipelineRecovery: SourcePipelineRecovery,
	private val motionController: CollectionMotionController,
) : ActivityRecognitionEventIngress {
	override suspend fun admit(batch: ActivityRecognitionEvidenceBatch): ActivityIngressResult {
		if (batch.eventCount == 0) return ActivityIngressResult.durable(0, 0)
		val capturedIdentity = batch.registrationIdentity
			?: return ActivityIngressResult.rejected(0, 0, "MISSING_REGISTRATION_IDENTITY")

		val result = runCatching {
			val lifecycle = lifecycleStore.snapshot()
			if (capturedIdentity.collectedDataEpoch != lifecycle.epoch) {
				return@runCatching ActivityIngressResult.rejected(0, 0, "STALE_COLLECTED_DATA_EPOCH")
			}
			val evidence = buildList {
				batch.recognitions.forEach { recognition ->
					add(ActivityEvidence.Recognition(recognition))
				}
				batch.transitions.forEach { transition ->
					add(ActivityEvidence.Transition(transition))
				}
			}.sortedBy(ActivityEvidence::observedElapsedRealtimeNanos)

			val admissionContexts = reserveSequences(
				capturedIdentity,
				lifecycle.epoch,
				batch.receivedWallTimeMs,
				evidence.size,
			) ?: return@runCatching ActivityIngressResult.rejected(
				0,
				0,
				"STALE_REGISTRATION_GENERATION",
			)
			val candidates = evidence.zip(admissionContexts) { event, context ->
				event.toCandidate(batch, lifecycle.epoch, context)
			}
			val admissionResults = sourceIngress.admitBatch(candidates)
			val admittedCount = admissionResults.count { it is AdmissionResult.Admitted }
			val duplicateCount = admissionResults.count { it is AdmissionResult.Duplicate }
			val permanentFailure = admissionResults.filterIsInstance<AdmissionResult.PermanentFailure>()
				.firstOrNull()
			val retryableFailure = admissionResults.filterIsInstance<AdmissionResult.RetryableFailure>()
				.firstOrNull()

			when {
				permanentFailure != null -> ActivityIngressResult.rejected(
					0,
					0,
					permanentFailure.code.name,
				)
				retryableFailure != null -> ActivityIngressResult.retryable(
					0,
					0,
					retryableFailure.code.name,
				)
				else -> {
					candidates.forEach { candidate -> motionController.onDurableEvidence(candidate) }
					ActivityIngressResult.durable(admittedCount, duplicateCount)
				}
			}
		}.getOrElse { failure ->
			if (failure is CancellationException) throw failure
			ActivityIngressResult.retryable(0, 0, failure.javaClass.simpleName.ifBlank { "storage_unavailable" })
		}
		if (result.isDurable) {
			try {
				val recovered = sourcePipelineRecovery.drainCommittedWork()
				if (recovered.drain !is com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult.Complete) {
					Tracebox.log.warn("Activity source projection recovery was deferred")
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Throwable) {
				Tracebox.log.error(error, "Activity source projection recovery failed")
			}
		}
		return result
	}

	private suspend fun reserveSequences(
		identity: com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity,
		collectedDataEpoch: Long,
		updatedAtMs: Long,
		count: Int,
	): List<ActivityAdmissionContext>? = database.withTransaction {
		val dao = database.sourceRegistrationStateDao()
		val current = dao.get(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE) ?: return@withTransaction null
		if (current.sourceInstanceId != identity.sourceInstanceId ||
			current.registrationGeneration != identity.registrationGeneration ||
			current.collectedDataEpoch != collectedDataEpoch ||
			current.collectedDataEpoch != identity.collectedDataEpoch ||
			current.appliedRevision != identity.appliedRevision
		) return@withTransaction null
		val activeSession = database.sourceSessionDao().activeSession()
		val serviceRunId = activeSession?.let { session ->
			database.sourceSessionDao().latestServiceRun(session.logicalTrackingId)?.serviceRunId
		}
		List(count) {
			val allocated = dao.allocateSequence(SourceKind.ACTIVITY.stableCode, OWNER_SCOPE, updatedAtMs)
			ActivityAdmissionContext(allocated, activeSession?.logicalTrackingId, serviceRunId)
		}
	}

	private fun ActivityEvidence.toCandidate(
		batch: ActivityRecognitionEvidenceBatch,
		collectedDataEpoch: Long,
		admissionContext: ActivityAdmissionContext,
	): SourceEvidenceCandidate<out SourcePayload> {
		val registration = admissionContext.registration
		val observedNanos = observedElapsedRealtimeNanos
		val clockUncertain = observedNanos > batch.receivedElapsedRealtimeNanos
		val delayNanos = if (clockUncertain) 0L else batch.receivedElapsedRealtimeNanos - observedNanos
		val acquiredAtMs = (batch.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
		val qualityFlags = buildSet {
			if (clockUncertain) add(SourceQualityFlag.CLOCK_UNCERTAIN)
			if (delayNanos >= BATCHED_AFTER_NANOS) add(SourceQualityFlag.BATCHED)
		}
		val attribution = if (registration.appliedRevision == null) {
			PlanAttribution.RECEIVE_TIME_ONLY
		} else {
			PlanAttribution.CAPTURED_REGISTRATION
		}
		return SourceEvidenceCandidate(
			providerDedupKey = providerDedupKey(registration),
			logicalTrackingId = admissionContext.logicalTrackingId?.let(::LogicalTrackingId),
			serviceRunId = admissionContext.serviceRunId?.let(::ServiceRunId),
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId(registration.sourceInstanceId),
			registrationGeneration = registration.registrationGeneration,
			sourceSequence = registration.nextSequence,
			configRevision = registration.appliedRevision,
			planAttribution = attribution,
			clockDomainId = registration.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos,
			receivedElapsedRealtimeNanos = batch.receivedElapsedRealtimeNanos,
			wallTimeMs = acquiredAtMs,
			wallTimeUncertaintyMs = if (clockUncertain) null else 1L,
			capturedCollectedDataEpoch = collectedDataEpoch,
			acquiredAtMs = acquiredAtMs,
			quality = SourceQuality(confidence = confidence, flags = qualityFlags),
			payloadVersion = 1,
			payload = payload,
		)
	}

	private data class ActivityAdmissionContext(
		val registration: SourceRegistrationStateEntity,
		val logicalTrackingId: String?,
		val serviceRunId: String?,
	)

	private sealed interface ActivityEvidence {
		val observedElapsedRealtimeNanos: Long
		val confidence: Float
		val payload: SourcePayload
		fun providerDedupKey(registration: SourceRegistrationStateEntity): String

		data class Recognition(val evidence: ActivityRecognitionEvidence) : ActivityEvidence {
			override val observedElapsedRealtimeNanos: Long = evidence.providerElapsedRealtimeNanos
			override val confidence: Float = evidence.confidencePercent / 100f
			override val payload: SourcePayload = ActivityRecognitionPayload(
				activityType = stableActivityCode(evidence.activityType),
				confidencePercent = evidence.confidencePercent,
				providerElapsedRealtimeNanos = evidence.providerElapsedRealtimeNanos,
			)

			override fun providerDedupKey(registration: SourceRegistrationStateEntity): String =
			"${registration.sourceInstanceId}:recognition:${evidence.providerElapsedRealtimeNanos}:" +
				"${evidence.activityType.name}:${evidence.confidencePercent}"
		}

		data class Transition(val evidence: ActivityTransitionEvidence) : ActivityEvidence {
			override val observedElapsedRealtimeNanos: Long = evidence.providerElapsedRealtimeNanos
			override val confidence: Float = 1f
			override val payload: SourcePayload = ActivityTransitionPayload(
				activityType = stableActivityCode(evidence.activityType),
				transitionType = evidence.transitionType.value,
				providerElapsedRealtimeNanos = evidence.providerElapsedRealtimeNanos,
			)

			override fun providerDedupKey(registration: SourceRegistrationStateEntity): String =
			"${registration.sourceInstanceId}:transition:${evidence.providerElapsedRealtimeNanos}:" +
				"${evidence.activityType.name}:${evidence.transitionType.name}"
		}
	}

	private companion object {
		const val OWNER_SCOPE = "activity-registration-arbiter"
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5L * 1_000L * NANOS_PER_MILLISECOND
	}
}

private fun stableActivityCode(activityType: DetectedActivityType): Int = when (activityType) {
	DetectedActivityType.STILL -> StableActivityTypeCode.STILL
	DetectedActivityType.WALKING -> StableActivityTypeCode.WALKING
	DetectedActivityType.RUNNING -> StableActivityTypeCode.RUNNING
	DetectedActivityType.ON_BICYCLE -> StableActivityTypeCode.ON_BICYCLE
	DetectedActivityType.IN_VEHICLE -> StableActivityTypeCode.IN_VEHICLE
	DetectedActivityType.ON_FOOT -> StableActivityTypeCode.ON_FOOT
	DetectedActivityType.TILTING -> StableActivityTypeCode.TILTING
	DetectedActivityType.UNKNOWN -> StableActivityTypeCode.UNKNOWN
}
