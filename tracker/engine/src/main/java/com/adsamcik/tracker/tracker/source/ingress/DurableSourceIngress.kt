package com.adsamcik.tracker.tracker.source.ingress

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventIdentityRow
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity.Companion.LEGACY_CHECKSUM_MISMATCH
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity.Companion.LEGACY_CHECKSUM_VERIFIED
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity.Companion.LEGACY_PENDING_CHECKSUM
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import kotlinx.coroutines.CancellationException
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface DurableSourceIngress {
	suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult
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
	STALE_REGISTRATION_GENERATION,
	AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
	STALE_SOURCE_POLICY,
	STALE_SESSION_MANIFEST,
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
	override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult {
		val lifecycle = lifecycleStore.snapshot()
		if (candidate.capturedCollectedDataEpoch != lifecycle.epoch) {
			return AdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH)
		}
		if (lifecycle.retainedFromMs?.let { candidate.acquiredAtMs < it } == true) {
			return AdmissionResult.PermanentFailure(AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY)
		}
		val encoded = runCatching { payloadCodec.encode(candidate.payload, candidate.payloadVersion) }
			.getOrElse {
				return AdmissionResult.PermanentFailure(AdmissionFailureCode.UNSUPPORTED_PAYLOAD)
			}

		return runCatchingNonCancellation {
			database.withTransaction<AdmissionResult> transaction@ {
				val stateDao = database.sourceEvidenceStateDao()
				val state = stateDao.get()
				if (state == null || state.collectedDataEpoch != lifecycle.epoch ||
					state.retainedFromMs != lifecycle.retainedFromMs
				) {
					return@transaction AdmissionResult.RetryableFailure(
						AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS,
					)
				}
				if (candidate.capturedCollectedDataEpoch != state.collectedDataEpoch) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
					)
				}
				if (state.retainedFromMs?.let { candidate.acquiredAtMs < it } == true) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY,
					)
				}
				val physicalFingerprint = candidate.physicalConfigurationFingerprint
				if (physicalFingerprint == null) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
					)
				}
				val brokerDao = database.sourceBrokerDao()
				val registration = brokerDao.registrationAtObservedTime(
					candidate.source.stableCode,
					candidate.registrationGeneration,
					candidate.sourceInstanceId.value,
					candidate.clockDomainId,
					physicalFingerprint,
					candidate.observedElapsedRealtimeNanos,
				) ?: return@transaction AdmissionResult.PermanentFailure(
					AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
				)
				if (registration.collectedDataEpoch != candidate.capturedCollectedDataEpoch) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
					)
				}
				val authorization = brokerDao.authorizationAt(
					candidate.source.stableCode,
					candidate.registrationGeneration,
					candidate.clockDomainId,
					candidate.observedElapsedRealtimeNanos,
				).toAuthorizationSnapshotOrNull()
				if (authorization == null || authorization.isDenied) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_SOURCE_POLICY,
					)
				}
				val intervalStart = candidate.observedIntervalStartElapsedRealtimeNanos()
				if (intervalStart < candidate.observedElapsedRealtimeNanos) {
					val startAuthorization = brokerDao.authorizationAt(
						candidate.source.stableCode,
						candidate.registrationGeneration,
						candidate.clockDomainId,
						intervalStart,
					).toAuthorizationSnapshotOrNull()
					if (startAuthorization?.authorizationRevision != authorization.authorizationRevision) {
						return@transaction AdmissionResult.PermanentFailure(
							AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
						)
					}
				}
				val captureMembers = authorization.authorizedMembers.filter { member ->
					member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
				}
				if (captureMembers.size > 1) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_SESSION_MANIFEST,
					)
				}
				val captureAuthorization = captureMembers.singleOrNull()

				val walDao = database.sourceEventWalDao()
				val existing = candidate.providerDedupKey?.let { key ->
					walDao.identityByProviderDedupKey(candidate.source.stableCode, key)
				} ?: walDao.identityBySourceSequence(
					candidate.source.stableCode,
					candidate.sourceInstanceId.value,
					candidate.sourceSequence,
				)
				if (existing != null) {
					return@transaction existing.resolveDuplicate(candidate, encoded, authorization, captureAuthorization)
				}

				val eventId = SourceEventId(UUID.randomUUID().toString())
				val rowId = walDao.insertIgnoringDuplicate(
					candidate.toEntity(
						eventId,
						encoded,
						System.currentTimeMillis(),
						authorization,
						captureAuthorization,
					),
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
					return@transaction AdmissionResult.RetryableFailure(
						AdmissionFailureCode.STORAGE_UNAVAILABLE,
					)
				}
				if (admitted != null) {
					return@transaction admitted.resolveDuplicate(
						candidate,
						encoded,
						authorization,
						captureAuthorization,
					)
				}
				check(stateDao.incrementRevision(System.currentTimeMillis()) == 1) {
					"Unable to advance source-evidence revision after WAL admission"
				}
				AdmissionResult.Admitted(eventId, rowId)
			}
		}.getOrElse {
			AdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE)
		}
	}

	override suspend fun committedBatch(
		afterOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> {
		require(afterOrdinal >= 0L)
		require(limit > 0)
		val committed = mutableListOf<AdmittedSourceEvent<out SourcePayload>>()
		val walDao = database.sourceEventWalDao()
		for (row in walDao.eventsAfter(afterOrdinal, limit)) {
			val integrityValid = when {
				row.hasQualifiedIntegrity() || row.hasVerifiedLegacyPayload() -> true
				row.integrityIdentity == LEGACY_PENDING_CHECKSUM -> {
					val classification = if (row.hasPendingLegacyPayload()) {
						LEGACY_CHECKSUM_VERIFIED
					} else {
						LEGACY_CHECKSUM_MISMATCH
					}
					walDao.classifyPendingLegacyPayload(row.admissionOrdinal, classification)
					classification == LEGACY_CHECKSUM_VERIFIED
				}
				else -> false
			}
			if (!integrityValid) {
				if (committed.isEmpty()) {
					throw CorruptSourceEventException(
						admissionOrdinal = row.admissionOrdinal,
						sourceKind = row.sourceKind,
						failureCode = RAW_PAYLOAD_INTEGRITY_FAILURE,
					)
				}
				break
			}
			val event = try {
				row.toAdmittedEvent()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				if (committed.isEmpty()) {
					throw CorruptSourceEventException(
						admissionOrdinal = row.admissionOrdinal,
						sourceKind = row.sourceKind,
						failureCode = RAW_PAYLOAD_DECODE_FAILURE,
					)
				}
				break
			}
			committed += event
		}
		return committed
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
				physicalConfigurationFingerprint = physicalConfigurationFingerprint,
				authorizationRevision = authorizationRevision,
				registrationPurposeEligibilityMask = authorizationPurposeEligibilityMask,
				registrationEligibilityFingerprint = authorizationFingerprint,
				sourceSequence = sourceSequence,
				configRevision = configRevision,
				planAttribution = PlanAttribution.entries[planAttribution],
				clockDomainId = clockDomainId,
				observedElapsedRealtimeNanos = observedElapsedNanos,
				receivedElapsedRealtimeNanos = receivedElapsedNanos,
				wallTimeMs = wallTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = capturedCollectedDataEpoch,
				sourcePolicyRevision = sourcePolicyRevision,
				captureConsentEpoch = captureConsentEpoch,
				sessionManifestRevision = sessionManifestRevision,
				lifecycleLeaseGeneration = lifecycleLeaseGeneration,
				acquiredAtMs = acquiredAtMs,
				quality = sourceQualityFromStableFlags(qualityFlags, qualityConfidence),
				payloadVersion = payloadVersion,
				payload = payload,
			),
		)
	}
}

private fun SourceEvidenceCandidate<*>.toEntity(
	eventId: SourceEventId,
	encoded: EncodedSourcePayload,
	createdAtMs: Long,
	authorization: SourceAuthorizationSnapshot,
	captureAuthorization: SourceAuthorizationEntity?,
): SourceEventWalEntity {
	val entity = SourceEventWalEntity(
		eventId = eventId.value,
		providerDedupKey = providerDedupKey,
		logicalTrackingId = captureAuthorization?.logicalTrackingId,
		serviceRunId = captureAuthorization?.serviceRunId,
		sourceKind = source.stableCode,
		sourceInstanceId = sourceInstanceId.value,
		registrationGeneration = registrationGeneration,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		authorizationRevision = authorization.authorizationRevision,
		authorizationPurposeEligibilityMask = authorization.purposeEligibilityMask,
		authorizationFingerprint = authorization.authorizationFingerprint,
		sourceSequence = sourceSequence,
		configRevision = configRevision,
		planAttribution = if (captureAuthorization != null) {
			PlanAttribution.CAPTURED_REGISTRATION.ordinal
		} else {
			// Provider callbacks may carry stale caller hints. Product eligibility comes only
			// from the observed-time authorization revision persisted above.
			PlanAttribution.RECEIVE_TIME_ONLY.ordinal
		},
		clockDomainId = clockDomainId,
		observedElapsedNanos = observedElapsedRealtimeNanos,
		receivedElapsedNanos = receivedElapsedRealtimeNanos,
		wallTimeMs = wallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		capturedCollectedDataEpoch = capturedCollectedDataEpoch,
		sourcePolicyRevision = captureAuthorization?.sourcePolicyRevision,
		captureConsentEpoch = captureAuthorization?.consentEpoch,
		sessionManifestRevision = captureAuthorization?.manifestRevision,
		lifecycleLeaseGeneration = captureAuthorization?.lifecycleLeaseGeneration,
		acquiredAtMs = acquiredAtMs,
		qualityFlags = quality.toStableFlags(),
		qualityConfidence = quality.confidence,
		payloadVersion = payloadVersion,
		payload = encoded.bytes,
		payloadChecksum = encoded.checksum,
		createdAtMs = createdAtMs,
	)
	return entity.copy(integrityIdentity = entity.calculatedIntegrityIdentity())
}

private fun SourceEventIdentityRow.resolveDuplicate(
	candidate: SourceEvidenceCandidate<*>,
	encoded: EncodedSourcePayload,
	authorization: SourceAuthorizationSnapshot,
	captureAuthorization: SourceAuthorizationEntity?,
): AdmissionResult {
	val candidateEntity = candidate.toEntity(
		SourceEventId(eventId),
		encoded,
		createdAtMs = 0L,
		authorization,
		captureAuthorization,
	)
	val candidateIntegrity = if (candidate.providerDedupKey != null) {
		// A stable provider identity wins over a locally allocated retry sequence. Preserve the
		// originally admitted sequence when comparing every other immutable envelope field.
		candidateEntity.copy(sourceSequence = sourceSequence).calculatedIntegrityIdentity()
	} else {
		candidateEntity.integrityIdentity
	}
	val retryIdentityMatches = if (candidate.providerDedupKey != null) {
		providerDedupKey == candidate.providerDedupKey &&
			sourceInstanceId == candidate.sourceInstanceId.value &&
			registrationGeneration == candidate.registrationGeneration &&
			physicalConfigurationFingerprint == candidate.physicalConfigurationFingerprint &&
			authorizationRevision == authorization.authorizationRevision &&
			authorizationPurposeEligibilityMask == authorization.purposeEligibilityMask &&
			authorizationFingerprint == authorization.authorizationFingerprint &&
			sourcePolicyRevision == captureAuthorization?.sourcePolicyRevision &&
			captureConsentEpoch == captureAuthorization?.consentEpoch &&
			sessionManifestRevision == captureAuthorization?.manifestRevision &&
			lifecycleLeaseGeneration == captureAuthorization?.lifecycleLeaseGeneration
	} else {
		sourceInstanceId == candidate.sourceInstanceId.value &&
			sourceSequence == candidate.sourceSequence &&
			physicalConfigurationFingerprint == candidate.physicalConfigurationFingerprint &&
			authorizationRevision == authorization.authorizationRevision &&
			authorizationPurposeEligibilityMask == authorization.purposeEligibilityMask &&
			authorizationFingerprint == authorization.authorizationFingerprint &&
			sourcePolicyRevision == captureAuthorization?.sourcePolicyRevision &&
			captureConsentEpoch == captureAuthorization?.consentEpoch &&
			sessionManifestRevision == captureAuthorization?.manifestRevision &&
			lifecycleLeaseGeneration == captureAuthorization?.lifecycleLeaseGeneration
	}
	return if (
		retryIdentityMatches &&
		payloadVersion == candidate.payloadVersion &&
		payloadChecksum == encoded.checksum &&
		integrityIdentity == candidateIntegrity
	) {
		AdmissionResult.Duplicate(SourceEventId(eventId), admissionOrdinal)
	} else {
		AdmissionResult.PermanentFailure(AdmissionFailureCode.IDENTITY_COLLISION, SourceEventId(eventId))
	}
}

private fun SourceEvidenceCandidate<*>.observedIntervalStartElapsedRealtimeNanos(): Long = when (val value = payload) {
	is StepCounterWindowPayload -> value.windowStartElapsedRealtimeNanos
	is PressureWindowPayload -> value.windowStartElapsedRealtimeNanos
	else -> observedElapsedRealtimeNanos
}

private const val MANIFEST_PURPOSE_CAPTURE = "SESSION_CAPTURE"
private const val SESSION_STATE_STOPPING = "STOPPING"
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private const val RAW_PAYLOAD_INTEGRITY_FAILURE = "RAW_PAYLOAD_INTEGRITY"
private const val RAW_PAYLOAD_DECODE_FAILURE = "RAW_PAYLOAD_DECODE"

class CorruptSourceEventException(
	val admissionOrdinal: Long,
	val sourceKind: Int,
	val failureCode: String,
) : IllegalStateException(
	"Source event $admissionOrdinal for source $sourceKind failed persisted payload verification",
)
