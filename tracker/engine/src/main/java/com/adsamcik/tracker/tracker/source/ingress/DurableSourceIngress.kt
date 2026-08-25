package com.adsamcik.tracker.tracker.source.ingress

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
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
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
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
import com.adsamcik.tracker.tracker.source.runtime.SensorAdmissionCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.mergeSensorRuntimeStates
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface DurableSourceIngress {
	suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult
	suspend fun admit(
		candidate: SourceEvidenceCandidate<*>,
		checkpoint: SensorAdmissionCheckpoint,
	): AdmissionResult = AdmissionResult.RetryableFailure(
		AdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
	)
	suspend fun committedBatch(afterOrdinal: Long, limit: Int): List<AdmittedSourceEvent<out SourcePayload>>
	suspend fun committedSourceBatch(
		source: SourceKind,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>>
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
	INVALID_OBSERVED_TIME,
	STALE_OBSERVATION,
	STALE_SOURCE_POLICY,
	CAPTURE_ADMISSION_CLOSED,
	STALE_SESSION_MANIFEST,
	STALE_COLLECTED_DATA_EPOCH,
	BEFORE_RETENTION_BOUNDARY,
	LIFECYCLE_BARRIER_IN_PROGRESS,
	IDENTITY_COLLISION,
	UNSUPPORTED_PAYLOAD,
	STORAGE_UNAVAILABLE,
	STARTUP_RECOVERY_NOT_READY,
	ATOMIC_CHECKPOINT_UNSUPPORTED,
}

@Singleton
class RoomDurableSourceIngress @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val payloadCodec: SourcePayloadCodec,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
	private val trackingStartupGateProvider: Provider<TrackingStartupGate>,
) : DurableSourceIngress, DurableSourceDeliveryIngress {
	override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult =
		admitInternal(candidate, checkpoint = null)

	override suspend fun admit(
		candidate: SourceEvidenceCandidate<*>,
		checkpoint: SensorAdmissionCheckpoint,
	): AdmissionResult = admitInternal(candidate, checkpoint)

	private suspend fun admitInternal(
		candidate: SourceEvidenceCandidate<*>,
		checkpoint: SensorAdmissionCheckpoint?,
	): AdmissionResult {
		if (!candidate.hasValidObservedTime()) {
			return AdmissionResult.PermanentFailure(AdmissionFailureCode.INVALID_OBSERVED_TIME)
		}
		if (checkpoint != null && !checkpoint.matches(candidate)) {
			return AdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_REGISTRATION_GENERATION)
		}
		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcileAdmission() !is
			TrackingAdmissionStartupResult.Ready
		) {
			return AdmissionResult.RetryableFailure(AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY)
		}
		val startupGeneration = startupGate.currentGeneration
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
				if (!startupGate.isReadyGeneration(startupGeneration)) {
					return@transaction AdmissionResult.RetryableFailure(
						AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
					)
				}
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
				if (checkpoint != null) {
					val checkpointRegistration = database.sourceRegistrationStateDao().get(
						checkpoint.source.stableCode,
						checkpoint.ownerScope,
					)
					if (checkpointRegistration == null ||
						checkpointRegistration.sourceInstanceId != checkpoint.sourceInstanceId ||
						checkpointRegistration.clockDomainId != checkpoint.clockDomainId ||
						checkpointRegistration.registrationGeneration != checkpoint.registrationGeneration
					) {
						return@transaction AdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
						)
					}
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
				val observedTimeAuthorization = brokerDao.authorizationAt(
					candidate.source.stableCode,
					candidate.registrationGeneration,
					candidate.clockDomainId,
					candidate.observedElapsedRealtimeNanos,
				).toAuthorizationSnapshotOrNull()
				if (observedTimeAuthorization == null || observedTimeAuthorization.isDenied) {
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
					if (startAuthorization?.authorizationRevision !=
						observedTimeAuthorization.authorizationRevision
					) {
						return@transaction AdmissionResult.PermanentFailure(
							AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
						)
					}
				}
				val authorization = observedTimeAuthorization.qualifiedForFreshness(
					brokerDao = brokerDao,
					observedElapsedRealtimeNanos = candidate.observedElapsedRealtimeNanos,
					receivedElapsedRealtimeNanos = candidate.receivedElapsedRealtimeNanos,
				) ?: return@transaction AdmissionResult.PermanentFailure(
					AdmissionFailureCode.STALE_OBSERVATION,
				)
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
					val duplicate = existing.resolveDuplicate(
						candidate,
						encoded,
						authorization,
						captureAuthorization,
					)
					if (duplicate is AdmissionResult.Duplicate && checkpoint != null) {
						persistAtomicCheckpoint(
							checkpoint,
							duplicate.existingAdmissionOrdinal,
							candidate.receivedElapsedRealtimeNanos,
						)
					}
					return@transaction duplicate
				}
				if (authorization.hasCapturePurpose() &&
					!isCaptureAdmissionExecutable(candidate.source.stableCode)
				) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
					)
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
					val duplicate = admitted.resolveDuplicate(
						candidate,
						encoded,
						authorization,
						captureAuthorization,
					)
					if (duplicate is AdmissionResult.Duplicate && checkpoint != null) {
						persistAtomicCheckpoint(
							checkpoint,
							duplicate.existingAdmissionOrdinal,
							candidate.receivedElapsedRealtimeNanos,
						)
					}
					return@transaction duplicate
				}
				checkpoint?.let {
					persistAtomicCheckpoint(it, rowId, candidate.receivedElapsedRealtimeNanos)
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

	/** Called only from an open Room transaction so the structural gate and identity stay atomic. */
	private suspend fun isCaptureAdmissionExecutable(sourceKind: Int): Boolean {
		val dao = database.sourceProjectionStateDao()
		if (!dao.isCaptureAdmissionOpen(sourceKind)) return false
		return dao.activeProductLane(sourceKind)?.let(executableLaneCatalog::owns) == true
	}

	private suspend fun persistAtomicCheckpoint(
		checkpoint: SensorAdmissionCheckpoint,
		admissionOrdinal: Long,
		causalOrderElapsedRealtimeNanos: Long,
	) {
		val dao = database.sourceRuntimeStateDao()
		val incoming = checkpoint.toRuntimeState(
			admissionOrdinal,
			causalOrderElapsedRealtimeNanos,
		)
		val stored = mergeSensorRuntimeStates(
			current = dao.get(incoming.sourceKind, incoming.ownerScope),
			incoming = incoming,
			legacyComponentStateVersion = checkpoint.componentStateVersion,
		)
		dao.save(stored)
		check(stored.sourceKind == checkpoint.source.stableCode &&
			stored.ownerScope == checkpoint.ownerScope &&
			stored.sourceInstanceId == checkpoint.sourceInstanceId &&
			stored.clockDomainId == checkpoint.clockDomainId &&
			stored.registrationGeneration == checkpoint.registrationGeneration &&
			stored.lastProviderSequence >= checkpoint.providerSequenceThrough
		) { "Atomic sensor checkpoint was superseded by an incompatible runtime generation" }
		if (stored.lastProviderSequence == checkpoint.providerSequenceThrough) {
			check((stored.lastAdmissionOrdinal ?: Long.MIN_VALUE) >= admissionOrdinal) {
				"Atomic sensor checkpoint did not retain its admission ordinal"
			}
		}
	}

	override suspend fun admit(delivery: SourceDeliveryCandidate): DeliveryAdmissionResult {
		if (!delivery.hasValidObservedTimes()) {
			return DeliveryAdmissionResult.PermanentFailure(
				AdmissionFailureCode.INVALID_OBSERVED_TIME,
			)
		}
		val startupGate = trackingStartupGateProvider.get()
		if (startupGate.reconcileAdmission() !is
			TrackingAdmissionStartupResult.Ready
		) {
			return DeliveryAdmissionResult.RetryableFailure(
				AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
			)
		}
		val startupGeneration = startupGate.currentGeneration
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
				if (!startupGate.isReadyGeneration(startupGeneration)) {
					return@transaction DeliveryAdmissionResult.RetryableFailure(
						AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
					)
				}
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
					val observedTimeAuthorization = brokerDao.authorizationAt(
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
						if (startAuthorization?.authorizationRevision !=
							observedTimeAuthorization?.authorizationRevision
						) {
							return@transaction DeliveryAdmissionResult.PermanentFailure(
								AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
							)
						}
					}
					if (observedTimeAuthorization == null || observedTimeAuthorization.isDenied) {
						emptyDeliveryFailure = AdmissionFailureCode.STALE_SOURCE_POLICY
						continue
					}
					val authorization = observedTimeAuthorization.qualifiedForFreshness(
						brokerDao = brokerDao,
						observedElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
						receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
					)
					if (authorization == null) {
						emptyDeliveryFailure = AdmissionFailureCode.STALE_OBSERVATION
						continue
					}
					if (authorization.hasCapturePurpose() &&
						!isCaptureAdmissionExecutable(evidence.source.stableCode)
					) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED,
						)
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
		return decodeCommittedRows(database.sourceEventWalDao().eventsAfter(afterOrdinal, limit))
	}

	override suspend fun committedSourceBatch(
		source: SourceKind,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> {
		require(afterOrdinal >= 0L)
		require(throughOrdinal >= afterOrdinal)
		require(limit > 0)
		return decodeCommittedRows(
			database.sourceEventWalDao().sourceEventsAfterThrough(
				sourceKind = source.stableCode,
				afterOrdinal = afterOrdinal,
				throughOrdinal = throughOrdinal,
				limit = limit,
			),
		)
	}

	private suspend fun decodeCommittedRows(
		rows: List<SourceEventWalEntity>,
	): List<AdmittedSourceEvent<out SourcePayload>> {
		val committed = mutableListOf<AdmittedSourceEvent<out SourcePayload>>()
		val walDao = database.sourceEventWalDao()
		for (row in rows) {
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
				activityAutomationEpoch = activityAutomationEpoch,
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
		activityAutomationEpoch = activityAutomationEpoch,
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

private fun SourceAuthorizationSnapshot.hasCapturePurpose(): Boolean =
	authorizedMembers.any { member ->
		member.purpose in setOf(
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
	}

/**
 * Narrows an observed-time authorization to demands for which this callback is still fresh.
 *
 * Authorization history answers whether the provider was allowed to emit the observation at its
 * observed time. Freshness is an immutable per-demand product constraint, so it is evaluated from
 * the referenced demand row without consulting that demand's current reconciliation status. This
 * lets a delayed callback remain auditable while preventing a stale capture member from borrowing
 * a more permissive control member's maximum age.
 */
private suspend fun SourceAuthorizationSnapshot.qualifiedForFreshness(
	brokerDao: SourceBrokerDao,
	observedElapsedRealtimeNanos: Long,
	receivedElapsedRealtimeNanos: Long,
): SourceAuthorizationSnapshot? {
	val ageNanos = receivedElapsedRealtimeNanos - observedElapsedRealtimeNanos
	if (ageNanos < 0L) return null
	val memberDemandIds = authorizedMembers.mapNotNull(SourceAuthorizationEntity::demandId)
	if (memberDemandIds.isEmpty()) return null
	val demandsById = brokerDao.demandsByIds(memberDemandIds).associateBy { demand -> demand.demandId }
	val qualifiedMembers = authorizedMembers.mapNotNull { member ->
		val demandId = member.demandId ?: return@mapNotNull null
		val demand = demandsById[demandId] ?: return@mapNotNull null
		val immutableAuthorityMatches = demand.sourceKind == member.sourceKind &&
			demand.consumerId == member.consumerId &&
			demand.purpose == member.purpose &&
			demand.sourcePolicyRevision == member.sourcePolicyRevision &&
			demand.consentEpoch == member.consentEpoch &&
			demand.persistenceEligible == member.persistenceEligible &&
			demand.logicalTrackingId == member.logicalTrackingId &&
			demand.serviceRunId == member.serviceRunId &&
			demand.manifestRevision == member.manifestRevision &&
			demand.lifecycleLeaseGeneration == member.lifecycleLeaseGeneration
		if (!immutableAuthorityMatches) return@mapNotNull null
		val maximumAgeNanos = demand.maximumAgeMs.saturatedMillisecondsToNanos()
		member.takeIf { ageNanos <= maximumAgeNanos }
	}
	if (qualifiedMembers.isEmpty()) return null
	val qualifiedPurposeMask = qualifiedMembers.fold(0L) { mask, member ->
		mask or SourceBrokerPurpose.mask(requireNotNull(member.purpose))
	}
	return copy(
		purposeEligibilityMask = qualifiedPurposeMask,
		members = qualifiedMembers.map { member ->
			member.copy(purposeEligibilityMask = qualifiedPurposeMask)
		},
	)
}

private fun Long.saturatedMillisecondsToNanos(): Long =
	if (this > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		this * NANOS_PER_MILLISECOND
	}

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
			activityAutomationEpoch == candidate.activityAutomationEpoch &&
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
			activityAutomationEpoch == candidate.activityAutomationEpoch &&
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

private data class ObservedTimeInterval(
	val clockDomainId: String,
	val startElapsedRealtimeNanos: Long,
	val endElapsedRealtimeNanos: Long,
)

private fun SourceEvidenceCandidate<*>.observedTimeInterval(): ObservedTimeInterval =
	when (val value = payload) {
		is StepCounterWindowPayload -> ObservedTimeInterval(
			clockDomainId = value.bootClockDomainId,
			startElapsedRealtimeNanos = value.windowStartElapsedRealtimeNanos,
			endElapsedRealtimeNanos = value.windowEndElapsedRealtimeNanos,
		)
		is PressureWindowPayload -> ObservedTimeInterval(
			clockDomainId = clockDomainId,
			startElapsedRealtimeNanos = value.windowStartElapsedRealtimeNanos,
			endElapsedRealtimeNanos = value.windowEndElapsedRealtimeNanos,
		)
		else -> ObservedTimeInterval(
			clockDomainId = clockDomainId,
			startElapsedRealtimeNanos = observedElapsedRealtimeNanos,
			endElapsedRealtimeNanos = observedElapsedRealtimeNanos,
		)
	}

private fun SourceEvidenceCandidate<*>.hasValidObservedTime(): Boolean {
	val interval = observedTimeInterval()
	return interval.clockDomainId == clockDomainId &&
		interval.endElapsedRealtimeNanos == observedElapsedRealtimeNanos &&
		interval.startElapsedRealtimeNanos >= 0L &&
		interval.startElapsedRealtimeNanos <= interval.endElapsedRealtimeNanos &&
		interval.endElapsedRealtimeNanos <= receivedElapsedRealtimeNanos
}

private fun SensorAdmissionCheckpoint.matches(candidate: SourceEvidenceCandidate<*>): Boolean {
	val payloadProviderSequence = when (val value = candidate.payload) {
		is StepCounterWindowPayload -> value.lastProviderSequence
		is PressureWindowPayload -> value.lastProviderSequence
		else -> return false
	}
	return source == candidate.source &&
		sourceInstanceId == candidate.sourceInstanceId.value &&
		clockDomainId == candidate.clockDomainId &&
		registrationGeneration == candidate.registrationGeneration &&
		providerSequenceThrough == payloadProviderSequence
}

private fun SourceDeliveryCandidate.hasValidObservedTimes(): Boolean = units.all { unit ->
	val evidence = unit.evidence
	evidence.hasValidObservedTime() &&
		unit.observedIntervalStartElapsedRealtimeNanos >= 0L &&
		unit.observedIntervalStartElapsedRealtimeNanos <= evidence.observedElapsedRealtimeNanos &&
		evidence.observedElapsedRealtimeNanos <= evidence.receivedElapsedRealtimeNanos
}

private fun SourceEvidenceCandidate<*>.observedIntervalStartElapsedRealtimeNanos(): Long =
	observedTimeInterval().startElapsedRealtimeNanos

private const val MANIFEST_PURPOSE_CAPTURE = "SESSION_CAPTURE"
private const val SESSION_STATE_STOPPING = "STOPPING"
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private const val RAW_PAYLOAD_INTEGRITY_FAILURE = "RAW_PAYLOAD_INTEGRITY"
private const val RAW_PAYLOAD_DECODE_FAILURE = "RAW_PAYLOAD_DECODE"
private const val NANOS_PER_MILLISECOND = 1_000_000L

class CorruptSourceEventException(
	val admissionOrdinal: Long,
	val sourceKind: Int,
	val failureCode: String,
) : IllegalStateException(
	"Source event $admissionOrdinal for source $sourceKind failed persisted payload verification",
)
