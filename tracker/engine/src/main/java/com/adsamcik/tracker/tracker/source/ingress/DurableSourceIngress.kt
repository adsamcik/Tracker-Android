package com.adsamcik.tracker.tracker.source.ingress

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventIdentityRow
import com.adsamcik.tracker.shared.base.database.dao.SourceDeliveryUnitIdentityRow
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
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
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

/** Batch admission contract adopted source-by-source without widening legacy ingress mocks. */
interface DurableSourceDeliveryIngress {
	suspend fun admit(delivery: SourceDeliveryCandidate): DeliveryAdmissionResult
}

sealed interface DeliveryAdmissionResult {
	data class Admitted(val units: List<AdmittedUnit>) : DeliveryAdmissionResult
	data class Duplicate(val units: List<AdmittedUnit>) : DeliveryAdmissionResult
	data class RetryableFailure(val code: AdmissionFailureCode) : DeliveryAdmissionResult
	data class PermanentFailure(val code: AdmissionFailureCode) : DeliveryAdmissionResult

	data class AdmittedUnit(
		val unitIndex: Int,
		val eventId: SourceEventId,
		val admissionOrdinal: Long,
	)
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
	private val payloadCodec: SourcePayloadCodec,
) : DurableSourceIngress, DurableSourceDeliveryIngress {
	override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult {
		val lifecycle = lifecycleStore.snapshot()
		if (candidate.capturedCollectedDataEpoch != lifecycle.epoch) {
			return AdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH)
		}
		if (lifecycle.retainedFromMs?.let { candidate.acquiredAtMs < it } == true) {
			return AdmissionResult.PermanentFailure(AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY)
		}
		val encoded = runCatchingNonCancellation {
			payloadCodec.encode(candidate.payload, candidate.payloadVersion)
		}
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

	override suspend fun admit(delivery: SourceDeliveryCandidate): DeliveryAdmissionResult {
		val lifecycle = lifecycleStore.snapshot()
		if (delivery.capturedCollectedDataEpoch != lifecycle.epoch) {
			return DeliveryAdmissionResult.PermanentFailure(
				AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
			)
		}
		val encodedUnits = buildList {
			for (unit in delivery.units) {
				val encoded = runCatchingNonCancellation {
					payloadCodec.encode(unit.evidence.payload, unit.evidence.payloadVersion)
				}.getOrElse {
					return DeliveryAdmissionResult.PermanentFailure(
						AdmissionFailureCode.UNSUPPORTED_PAYLOAD,
					)
				}
				add(EncodedDeliveryUnit(unit, encoded))
			}
		}

		return runCatchingNonCancellation {
			database.withTransaction<DeliveryAdmissionResult> transaction@ {
				val stateDao = database.sourceEvidenceStateDao()
				val evidenceState = stateDao.get()
				if (evidenceState == null || evidenceState.collectedDataEpoch != lifecycle.epoch ||
					evidenceState.retainedFromMs != lifecycle.retainedFromMs
				) {
					return@transaction DeliveryAdmissionResult.RetryableFailure(
						AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS,
					)
				}

				val walDao = database.sourceEventWalDao()
				val existing = walDao.deliveryUnits(
					delivery.source.stableCode,
					delivery.capturedCollectedDataEpoch,
					delivery.clockDomainId,
					delivery.identity.value,
				)
				if (existing.isNotEmpty()) {
					return@transaction resolveDeliveryReplay(delivery, encodedUnits, existing)
				}

				val authorized = mutableListOf<AuthorizedDeliveryUnit>()
				var emptyDeliveryFailure = AdmissionFailureCode.STALE_SOURCE_POLICY
				for (unit in encodedUnits) {
					val evidence = unit.sourceUnit.evidence
					if (evidence.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
						)
					}
					if (evidenceState.retainedFromMs?.let { evidence.acquiredAtMs < it } == true) {
						emptyDeliveryFailure = AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY
						continue
					}
					val physicalFingerprint = evidence.physicalConfigurationFingerprint
						?: return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
						)
					val brokerDao = database.sourceBrokerDao()
					val registration = brokerDao.registrationAtObservedTime(
						evidence.source.stableCode,
						evidence.registrationGeneration,
						evidence.sourceInstanceId.value,
						evidence.clockDomainId,
						physicalFingerprint,
						evidence.observedElapsedRealtimeNanos,
					)
					val intervalStart = unit.sourceUnit.observedIntervalStartElapsedRealtimeNanos
					val startRegistration = if (intervalStart < evidence.observedElapsedRealtimeNanos) {
						brokerDao.registrationAtObservedTime(
							evidence.source.stableCode,
							evidence.registrationGeneration,
							evidence.sourceInstanceId.value,
							evidence.clockDomainId,
							physicalFingerprint,
							intervalStart,
						)
					} else {
						registration
					}
					if ((registration == null) != (startRegistration == null)) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
						)
					}
					if (registration == null) {
						emptyDeliveryFailure = AdmissionFailureCode.STALE_REGISTRATION_GENERATION
						continue
					}
					if (registration.collectedDataEpoch != evidence.capturedCollectedDataEpoch) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
						)
					}
					val authorization = brokerDao.authorizationAt(
						evidence.source.stableCode,
						evidence.registrationGeneration,
						evidence.clockDomainId,
						evidence.observedElapsedRealtimeNanos,
					).toAuthorizationSnapshotOrNull()
					if (intervalStart < evidence.observedElapsedRealtimeNanos) {
						val startAuthorization = brokerDao.authorizationAt(
							evidence.source.stableCode,
							evidence.registrationGeneration,
							evidence.clockDomainId,
							intervalStart,
						).toAuthorizationSnapshotOrNull()
						if (startAuthorization?.authorizationRevision != authorization?.authorizationRevision) {
							return@transaction DeliveryAdmissionResult.PermanentFailure(
								AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
							)
						}
					}
					if (authorization == null || authorization.isDenied) {
						emptyDeliveryFailure = AdmissionFailureCode.STALE_SOURCE_POLICY
						continue
					}
					val captureMembers = authorization.authorizedMembers.filter { member ->
						member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
					}
					if (captureMembers.size > 1) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_SESSION_MANIFEST,
						)
					}
					authorized += AuthorizedDeliveryUnit(
						unit,
						authorization,
						captureMembers.singleOrNull(),
					)
				}
				if (authorized.isEmpty()) {
					return@transaction DeliveryAdmissionResult.PermanentFailure(emptyDeliveryFailure)
				}
				val firstEvidence = authorized.first().encoded.sourceUnit.evidence
				val ownerScope = "source-broker:${delivery.source.stableCode}"
				val registrationState = database.sourceRegistrationStateDao().get(
					delivery.source.stableCode,
					ownerScope,
				) ?: return@transaction DeliveryAdmissionResult.RetryableFailure(
					AdmissionFailureCode.STORAGE_UNAVAILABLE,
				)
				if (registrationState.sourceInstanceId != firstEvidence.sourceInstanceId.value ||
					registrationState.clockDomainId != delivery.clockDomainId ||
					registrationState.collectedDataEpoch != delivery.capturedCollectedDataEpoch
				) {
					return@transaction DeliveryAdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
					)
				}
				val now = System.currentTimeMillis()
				val sequences = database.sourceRegistrationStateDao().allocateSequenceRange(
					delivery.source.stableCode,
					ownerScope,
					authorized.size,
					now,
				).toList()
				val entities = authorized.mapIndexed { index, unit ->
					val eventId = SourceEventId(UUID.randomUUID().toString())
					unit.encoded.sourceUnit.evidence.toEntity(
						eventId = eventId,
						encoded = unit.encoded.payload,
						createdAtMs = now,
						authorization = unit.authorization,
						captureAuthorization = unit.captureAuthorization,
						deliveryIdentity = delivery.identity.value,
						deliveryUnitIndex = unit.encoded.sourceUnit.unitIndex,
						deliveryUnitCount = delivery.units.size,
						observedIntervalStartNanos =
							unit.encoded.sourceUnit.observedIntervalStartElapsedRealtimeNanos,
						allocatedSourceSequence = sequences[index],
						persistProviderDedupKey = false,
					)
				}
				val rowIds = walDao.insertDeliveryUnits(entities)
				check(rowIds.size == entities.size && rowIds.all { it > 0L }) {
					"Unable to append every source delivery unit"
				}
				check(stateDao.incrementRevision(now) == 1) {
					"Unable to advance source-evidence revision after delivery admission"
				}
				DeliveryAdmissionResult.Admitted(
					entities.mapIndexed { index, entity ->
						DeliveryAdmissionResult.AdmittedUnit(
							unitIndex = requireNotNull(entity.deliveryUnitIndex),
							eventId = SourceEventId(entity.eventId),
							admissionOrdinal = rowIds[index],
						)
					},
				)
			}
		}.getOrElse {
			DeliveryAdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE)
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
	deliveryIdentity: String? = null,
	deliveryUnitIndex: Int? = null,
	deliveryUnitCount: Int? = null,
	observedIntervalStartNanos: Long? = null,
	allocatedSourceSequence: Long = sourceSequence,
	persistProviderDedupKey: Boolean = true,
): SourceEventWalEntity {
	val entity = SourceEventWalEntity(
		eventId = eventId.value,
		providerDedupKey = providerDedupKey.takeIf { persistProviderDedupKey },
		deliveryIdentity = deliveryIdentity,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		logicalTrackingId = captureAuthorization?.logicalTrackingId,
		serviceRunId = captureAuthorization?.serviceRunId,
		sourceKind = source.stableCode,
		sourceInstanceId = sourceInstanceId.value,
		registrationGeneration = registrationGeneration,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		authorizationRevision = authorization.authorizationRevision,
		authorizationPurposeEligibilityMask = authorization.purposeEligibilityMask,
		authorizationFingerprint = authorization.authorizationFingerprint,
		sourceSequence = allocatedSourceSequence,
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
		observedIntervalStartNanos = observedIntervalStartNanos,
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

private data class EncodedDeliveryUnit(
	val sourceUnit: SourceDeliveryUnit,
	val payload: EncodedSourcePayload,
)

private data class AuthorizedDeliveryUnit(
	val encoded: EncodedDeliveryUnit,
	val authorization: SourceAuthorizationSnapshot,
	val captureAuthorization: SourceAuthorizationEntity?,
)

private fun resolveDeliveryReplay(
	delivery: SourceDeliveryCandidate,
	encoded: List<EncodedDeliveryUnit>,
	existing: List<SourceDeliveryUnitIdentityRow>,
): DeliveryAdmissionResult {
	val existingIndexes = existing.mapNotNull(SourceDeliveryUnitIdentityRow::deliveryUnitIndex)
	if (existingIndexes.size != existing.size || existingIndexes.distinct().size != existing.size ||
		existing.any { row ->
			row.deliveryUnitCount != delivery.units.size ||
				requireNotNull(row.deliveryUnitIndex) !in encoded.indices
		}
	) {
		return DeliveryAdmissionResult.PermanentFailure(AdmissionFailureCode.IDENTITY_COLLISION)
	}
	val exact = existing.all { stored ->
		val candidate = encoded[requireNotNull(stored.deliveryUnitIndex)]
		val evidence = candidate.sourceUnit.evidence
		stored.deliveryUnitIndex == candidate.sourceUnit.unitIndex &&
			stored.observedElapsedNanos == evidence.observedElapsedRealtimeNanos &&
			stored.observedIntervalStartNanos ==
				candidate.sourceUnit.observedIntervalStartElapsedRealtimeNanos &&
			stored.payloadVersion == evidence.payloadVersion &&
			stored.payloadChecksum == candidate.payload.checksum
	}
	return if (exact) {
		DeliveryAdmissionResult.Duplicate(
			existing.map { stored ->
				DeliveryAdmissionResult.AdmittedUnit(
					unitIndex = requireNotNull(stored.deliveryUnitIndex),
					eventId = SourceEventId(stored.eventId),
					admissionOrdinal = stored.admissionOrdinal,
				)
			},
		)
	} else {
		DeliveryAdmissionResult.PermanentFailure(AdmissionFailureCode.IDENTITY_COLLISION)
	}
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
