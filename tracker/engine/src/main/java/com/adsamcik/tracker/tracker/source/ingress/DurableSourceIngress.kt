package com.adsamcik.tracker.tracker.source.ingress

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventIdentityRow
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface DurableSourceIngress {
	suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult
	/**
	 * Admits one provider delivery as a single transaction. If any candidate cannot be admitted,
	 * no candidate in the batch is committed and every returned entry describes that failure.
	 */
	suspend fun admitBatch(candidates: List<SourceEvidenceCandidate<*>>): List<AdmissionResult>
	suspend fun committedBatch(afterOrdinal: Long, limit: Int): List<AdmittedSourceEvent<out SourcePayload>>
	suspend fun checkpoint(consumer: String, ordinal: Long)
}

sealed interface AdmissionResult {
	val eventId: SourceEventId?

	data class Admitted(
		override val eventId: SourceEventId,
		val admissionOrdinal: Long,
	) : AdmissionResult

	data class Duplicate(
		override val eventId: SourceEventId,
		val existingAdmissionOrdinal: Long,
	) : AdmissionResult

	data class RetryableFailure(
		val code: AdmissionFailureCode,
		override val eventId: SourceEventId? = null,
	) : AdmissionResult

	data class PermanentFailure(
		val code: AdmissionFailureCode,
		override val eventId: SourceEventId? = null,
	) : AdmissionResult
}

enum class AdmissionFailureCode {
	STALE_COLLECTED_DATA_EPOCH,
	BEFORE_RETENTION_BOUNDARY,
	LIFECYCLE_BARRIER_IN_PROGRESS,
	IDENTITY_COLLISION,
	UNSUPPORTED_PAYLOAD,
	STORAGE_UNAVAILABLE,
}

@Singleton
class RoomDurableSourceIngress @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val payloadCodec: DefaultSourcePayloadCodec,
) : DurableSourceIngress {
	override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult =
		admitBatch(listOf(candidate)).single()

	override suspend fun admitBatch(
		candidates: List<SourceEvidenceCandidate<*>>,
	): List<AdmissionResult> {
		if (candidates.isEmpty()) return emptyList()
		val lifecycle = lifecycleStore.snapshot()
		val encodedCandidates = candidates.map { candidate ->
			if (candidate.capturedCollectedDataEpoch != lifecycle.epoch) {
				return repeatedFailure(
					candidates.size,
					AdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH),
				)
			}
			if (lifecycle.retainedFromMs?.let { candidate.acquiredAtMs < it } == true) {
				return repeatedFailure(
					candidates.size,
					AdmissionResult.PermanentFailure(AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY),
				)
			}
			val encoded = runCatching { payloadCodec.encode(candidate.payload, candidate.payloadVersion) }
				.getOrElse {
					return repeatedFailure(
						candidates.size,
						AdmissionResult.PermanentFailure(AdmissionFailureCode.UNSUPPORTED_PAYLOAD),
					)
				}
			EncodedCandidate(candidate, encoded)
		}

		return try {
			database.withTransaction {
				val stateDao = database.sourceEvidenceStateDao()
				val walDao = database.sourceEventWalDao()
				var state = stateDao.get()
				if (state == null && walDao.countAll() == 0L) {
					stateDao.ensure(
						SourceEvidenceState(
							collectedDataEpoch = lifecycle.epoch,
							retainedFromMs = lifecycle.retainedFromMs,
							updatedAtMs = System.currentTimeMillis(),
						),
					)
					state = stateDao.get()
				}
				if (state == null || state.collectedDataEpoch != lifecycle.epoch ||
					state.retainedFromMs != lifecycle.retainedFromMs
				) {
					abortBatch(
						AdmissionResult.RetryableFailure(AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS),
					)
				}
				val guardedState = requireNotNull(state)
				val results = encodedCandidates.map { (candidate, encoded) ->
					if (candidate.capturedCollectedDataEpoch != guardedState.collectedDataEpoch) {
						abortBatch(AdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH))
					}
					if (guardedState.retainedFromMs?.let { candidate.acquiredAtMs < it } == true) {
						abortBatch(AdmissionResult.PermanentFailure(AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY))
					}

					val existing = candidate.providerDedupKey?.let { key ->
						walDao.identityByProviderDedupKey(candidate.source.stableCode, key)
					} ?: walDao.identityBySourceSequence(
						candidate.source.stableCode,
						candidate.sourceInstanceId.value,
						candidate.sourceSequence,
					)
					if (existing != null) {
						val duplicate = existing.resolveDuplicate(candidate, encoded)
						if (duplicate is AdmissionResult.PermanentFailure) abortBatch(duplicate)
						return@map duplicate
					}

					val eventId = SourceEventId(UUID.randomUUID().toString())
					val rowId = walDao.insertIgnoringDuplicate(
						candidate.toEntity(eventId, encoded, System.currentTimeMillis()),
					)
					val admitted = if (rowId < 0L) {
						candidate.providerDedupKey?.let { key ->
							walDao.identityByProviderDedupKey(candidate.source.stableCode, key)
						} ?: walDao.identityBySourceSequence(
							candidate.source.stableCode,
							candidate.sourceInstanceId.value,
							candidate.sourceSequence,
						)
					} else null
					if (rowId < 0L && admitted == null) {
						abortBatch(AdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE))
					}
					if (admitted != null) {
						val duplicate = admitted.resolveDuplicate(candidate, encoded)
						if (duplicate is AdmissionResult.PermanentFailure) abortBatch(duplicate)
						return@map duplicate
					}

					AdmissionResult.Admitted(eventId, rowId)
				}
				if (results.any { it is AdmissionResult.Admitted }) {
					check(stateDao.incrementRevision(System.currentTimeMillis()) == 1) {
						"Unable to advance source-evidence revision after WAL admission"
					}
				}
				results
			}
		} catch (aborted: BatchAdmissionAborted) {
			repeatedFailure(candidates.size, aborted.result)
		} catch (_: Throwable) {
			repeatedFailure(
				candidates.size,
				AdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE),
			)
		}
	}

	override suspend fun committedBatch(
		afterOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> {
		require(afterOrdinal >= 0L)
		require(limit > 0)
		return database.sourceEventWalDao().eventsAfter(afterOrdinal, limit).map { row -> row.toAdmittedEvent() }
	}

	override suspend fun checkpoint(consumer: String, ordinal: Long) {
		require(consumer.isNotBlank())
		require(ordinal >= 0L)
		database.sourceProjectionStateDao().saveCheckpoint(
			com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity(
				projectionId = consumer,
				projectionVersion = 1,
				contiguousAdmissionOrdinal = ordinal,
				stateVersion = 1,
				updatedAtMs = System.currentTimeMillis(),
			),
		)
	}

	private fun SourceEventWalEntity.toAdmittedEvent(): AdmittedSourceEvent<out SourcePayload> {
		val source = SourceKind.entries.single { kind -> kind.stableCode == sourceKind }
		val payload = payloadCodec.decode(source, payloadVersion, payload)
		return AdmittedSourceEvent(
			eventId = SourceEventId(eventId),
			admissionOrdinal = admissionOrdinal,
			evidence = SourceEvidenceCandidate(
				providerDedupKey = providerDedupKey,
				logicalTrackingId = logicalTrackingId?.let(::LogicalTrackingId),
				serviceRunId = serviceRunId?.let(::ServiceRunId),
				source = source,
				sourceInstanceId = SourceInstanceId(sourceInstanceId),
				registrationGeneration = registrationGeneration,
				sourceSequence = sourceSequence,
				configRevision = configRevision,
				planAttribution = PlanAttribution.entries[planAttribution],
				clockDomainId = clockDomainId,
				observedElapsedRealtimeNanos = observedElapsedNanos,
				receivedElapsedRealtimeNanos = receivedElapsedNanos,
				wallTimeMs = wallTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = capturedCollectedDataEpoch,
				acquiredAtMs = acquiredAtMs,
				quality = sourceQualityFromStableFlags(qualityFlags, qualityConfidence),
				payloadVersion = payloadVersion,
				payload = payload,
			),
		)
	}
}

private data class EncodedCandidate(
	val candidate: SourceEvidenceCandidate<*>,
	val encoded: EncodedSourcePayload,
)

private class BatchAdmissionAborted(val result: AdmissionResult) : RuntimeException()

private fun abortBatch(result: AdmissionResult): Nothing = throw BatchAdmissionAborted(result)

private fun repeatedFailure(size: Int, failure: AdmissionResult): List<AdmissionResult> =
	List(size) { failure }

private fun SourceEvidenceCandidate<*>.toEntity(
	eventId: SourceEventId,
	encoded: EncodedSourcePayload,
	createdAtMs: Long,
): SourceEventWalEntity = SourceEventWalEntity(
	eventId = eventId.value,
	providerDedupKey = providerDedupKey,
	logicalTrackingId = logicalTrackingId?.value,
	serviceRunId = serviceRunId?.value,
	sourceKind = source.stableCode,
	sourceInstanceId = sourceInstanceId.value,
	registrationGeneration = registrationGeneration,
	sourceSequence = sourceSequence,
	configRevision = configRevision,
	planAttribution = planAttribution.ordinal,
	clockDomainId = clockDomainId,
	observedElapsedNanos = observedElapsedRealtimeNanos,
	receivedElapsedNanos = receivedElapsedRealtimeNanos,
	wallTimeMs = wallTimeMs,
	wallTimeUncertaintyMs = wallTimeUncertaintyMs,
	capturedCollectedDataEpoch = capturedCollectedDataEpoch,
	acquiredAtMs = acquiredAtMs,
	qualityFlags = quality.toStableFlags(),
	qualityConfidence = quality.confidence,
	payloadVersion = payloadVersion,
	payload = encoded.bytes,
	payloadChecksum = encoded.checksum,
	createdAtMs = createdAtMs,
)

private fun SourceEventIdentityRow.resolveDuplicate(
	candidate: SourceEvidenceCandidate<*>,
	encoded: EncodedSourcePayload,
): AdmissionResult {
	val retryIdentityMatches = if (candidate.providerDedupKey != null) {
		providerDedupKey == candidate.providerDedupKey &&
			sourceInstanceId == candidate.sourceInstanceId.value &&
			registrationGeneration == candidate.registrationGeneration
	} else {
		sourceInstanceId == candidate.sourceInstanceId.value &&
			sourceSequence == candidate.sourceSequence
	}
	return if (
		retryIdentityMatches &&
		payloadVersion == candidate.payloadVersion &&
		payloadChecksum == encoded.checksum
	) {
	AdmissionResult.Duplicate(SourceEventId(eventId), admissionOrdinal)
	} else {
	AdmissionResult.PermanentFailure(AdmissionFailureCode.IDENTITY_COLLISION, SourceEventId(eventId))
	}
}
