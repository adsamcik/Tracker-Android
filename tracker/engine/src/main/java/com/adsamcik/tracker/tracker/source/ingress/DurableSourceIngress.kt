@file:Suppress("TooManyFunctions") // Keeps the atomic Room admission authority checks together.

package com.adsamcik.tracker.tracker.source.ingress

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.markStepsRetentionTruncation
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEventIdentityRow
import com.adsamcik.tracker.shared.base.database.dao.SourceDeliveryUnitIdentityRow
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity.Companion.LEGACY_CHECKSUM_MISMATCH
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity.Companion.LEGACY_CHECKSUM_VERIFIED
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity.Companion.LEGACY_PENDING_CHECKSUM
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.decodeCurrentModelOrNull
import com.adsamcik.tracker.tracker.source.coordinator.isCanonicalCaptureAuthorizedBy
import com.adsamcik.tracker.tracker.source.coordinator.stableLifecycleChecksum
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
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
	suspend fun admit(
		delivery: SourceDeliveryCandidate,
		checkpoint: SensorAdmissionCheckpoint,
	): DeliveryAdmissionResult = DeliveryAdmissionResult.RetryableFailure(
		AdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
	)
}

sealed interface DeliveryAdmissionResult {
	data class Admitted(val units: List<AdmittedUnit>) : DeliveryAdmissionResult
	data class Duplicate(val units: List<AdmittedUnit>) : DeliveryAdmissionResult
	/** No WAL mutation occurred; the caller may rebuild against this exact durable cutoff. */
	data class SessionCutoff(val cutoffElapsedRealtimeNanos: Long) : DeliveryAdmissionResult {
		init {
			require(cutoffElapsedRealtimeNanos >= 0L)
		}
	}
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
		if (candidate.source != SourceKind.STEPS &&
			lifecycle.retainedFromMs?.let { candidate.acquiredAtMs < it } == true
		) {
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
					return@transaction AdmissionResult.RetryableFailure(
						AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS,
					)
				}
				if (candidate.capturedCollectedDataEpoch != state.collectedDataEpoch) {
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
					)
				}
				if (candidate.source != SourceKind.STEPS &&
					state.retainedFromMs?.let { candidate.acquiredAtMs < it } == true
				) {
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
				if (state.retainedFromMs?.let { candidate.acquiredAtMs < it } == true) {
					val captureKey = captureAuthorization?.captureSessionKey(
						source = candidate.source,
						clockDomainId = candidate.clockDomainId,
					)
					markStepsRetentionTruncationIfNeeded(captureKey, state, stateDao)
					return@transaction AdmissionResult.PermanentFailure(
						AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY,
					)
				}

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
		}.getOrElse { failure ->
			if (!failure.isTrackingOperationalFailure()) throw failure
			AdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE)
		}
	}

	/** Called only from an open Room transaction so the structural gate and identity stay atomic. */
	private suspend fun isCaptureAdmissionExecutable(sourceKind: Int): Boolean {
		val dao = database.sourceProjectionStateDao()
		if (!dao.isCaptureAdmissionOpen(sourceKind)) return false
		val source = SourceKind.entries.singleOrNull { it.stableCode == sourceKind } ?: return false
		val rollout = database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
			?: return false
		val lane = dao.activeProductLane(sourceKind) ?: return false
		return lane.isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)
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

	/** Commits loss evidence with a retained-floor rejection before returning the terminal result. */
	private suspend fun markStepsRetentionTruncationIfNeeded(
		captureKey: CaptureSessionKey?,
		evidenceState: SourceEvidenceState,
		stateDao: SourceEvidenceStateDao,
	) {
		val key = captureKey?.takeIf { it.source == SourceKind.STEPS } ?: return
		if (database.markStepsRetentionTruncation(
				logicalTrackingId = key.logicalTrackingId,
				serviceRunId = key.serviceRunId,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				markedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
			)
		) {
			check(stateDao.incrementRevision(System.currentTimeMillis().coerceAtLeast(0L)) == 1) {
				"Unable to publish the Steps retention-truncation marker"
			}
		}
	}

	override suspend fun admit(delivery: SourceDeliveryCandidate): DeliveryAdmissionResult =
		admitDeliveryInternal(delivery, checkpoint = null)

	override suspend fun admit(
		delivery: SourceDeliveryCandidate,
		checkpoint: SensorAdmissionCheckpoint,
	): DeliveryAdmissionResult = admitDeliveryInternal(delivery, checkpoint)

	// The transaction keeps authorization selection, replay, allocation, WAL, and checkpoint in one
	// auditable block. Splitting those phases across helpers would obscure their shared Room boundary.
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun admitDeliveryInternal(
		delivery: SourceDeliveryCandidate,
		checkpoint: SensorAdmissionCheckpoint?,
	): DeliveryAdmissionResult {
		if (!delivery.hasValidObservedTimes()) {
			return DeliveryAdmissionResult.PermanentFailure(
				AdmissionFailureCode.INVALID_OBSERVED_TIME,
			)
		}
		if (checkpoint != null && !checkpoint.matches(delivery)) {
			return DeliveryAdmissionResult.PermanentFailure(
				AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
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
				val walDao = database.sourceEventWalDao()
				var evidenceState = stateDao.get()
				if (evidenceState == null && walDao.countAll() == 0L) {
					stateDao.ensure(
						SourceEvidenceState(
							collectedDataEpoch = lifecycle.epoch,
							retainedFromMs = lifecycle.retainedFromMs,
							updatedAtMs = System.currentTimeMillis(),
						),
					)
					evidenceState = stateDao.get()
				}
				if (evidenceState == null || evidenceState.collectedDataEpoch != lifecycle.epoch ||
					evidenceState.retainedFromMs != lifecycle.retainedFromMs
				) {
					return@transaction DeliveryAdmissionResult.RetryableFailure(
						AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS,
					)
				}
				val brokerDao = database.sourceBrokerDao()
				if (checkpoint != null) {
					val checkpointEvidence = delivery.units.single().evidence
					if (delivery.source != SourceKind.STEPS &&
						evidenceState.retainedFromMs?.let { retainedFromMs ->
							checkpointEvidence.acquiredAtMs < retainedFromMs
						} == true
					) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY,
						)
					}
					val checkpointRegistration = database.sourceRegistrationStateDao().get(
						checkpoint.source.stableCode,
						checkpoint.ownerScope,
					)
					val checkpointMatchesCurrentRegistration = checkpointRegistration != null &&
						checkpointRegistration.sourceInstanceId == checkpoint.sourceInstanceId &&
						checkpointRegistration.clockDomainId == checkpoint.clockDomainId &&
						checkpointRegistration.registrationGeneration == checkpoint.registrationGeneration
					if (!checkpointMatchesCurrentRegistration) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
						)
					}
					delivery.checkpointRegistrationFailure(brokerDao)?.let { failure ->
						return@transaction DeliveryAdmissionResult.PermanentFailure(failure)
					}
				}

				val existing = walDao.deliveryUnits(
					delivery.source.stableCode,
					delivery.capturedCollectedDataEpoch,
					delivery.clockDomainId,
					delivery.identity.value,
				)
				if (existing.isNotEmpty()) {
					val replay = resolveDeliveryReplay(delivery, encodedUnits, existing)
					if (replay is DeliveryAdmissionResult.Duplicate && checkpoint != null) {
						delivery.checkpointReplayFailure(
							checkpoint = checkpoint,
							stored = existing.single(),
							brokerDao = brokerDao,
						)?.let { failure ->
							return@transaction DeliveryAdmissionResult.PermanentFailure(failure)
						}
						persistAtomicCheckpoint(
							checkpoint = checkpoint,
							admissionOrdinal = replay.units.single().admissionOrdinal,
							causalOrderElapsedRealtimeNanos =
								delivery.units.single().evidence.receivedElapsedRealtimeNanos,
						)
					}
					return@transaction replay
				}

				val authorized = mutableListOf<AuthorizedDeliveryUnit>()
				val captureAuthorities = mutableMapOf<CaptureSessionKey, CaptureSessionAuthority>()
				var emptyDeliveryFailure = AdmissionFailureCode.STALE_SOURCE_POLICY
				for (unit in encodedUnits) {
					val evidence = unit.sourceUnit.evidence
					if (evidence.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch) {
						return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
						)
					}
					if (evidence.source != SourceKind.STEPS &&
						evidenceState.retainedFromMs?.let { evidence.acquiredAtMs < it } == true
					) {
						emptyDeliveryFailure = AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY
						continue
					}
					val physicalFingerprint = evidence.physicalConfigurationFingerprint
						?: return@transaction DeliveryAdmissionResult.PermanentFailure(
							AdmissionFailureCode.STALE_REGISTRATION_GENERATION,
						)
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
					val observedTimeAuthorizationRows = brokerDao.authorizationAt(
						evidence.source.stableCode,
						evidence.registrationGeneration,
						evidence.clockDomainId,
						evidence.observedElapsedRealtimeNanos,
					)
					var observedTimeAuthorization = runCatchingNonCancellation {
						observedTimeAuthorizationRows.toAuthorizationSnapshotOrNull()
					}.getOrNull()
					if (evidence.source == SourceKind.WIFI &&
						(observedTimeAuthorization == null ||
							observedTimeAuthorization.isDenied ||
							!observedTimeAuthorization.matchesCapturedEnvelope(evidence))
					) {
						val retiringAuthority = loadRetiringCaptureAuthority(
							evidence = evidence,
							brokerDao = brokerDao,
							currentAuthorization = observedTimeAuthorization,
						)
						if (retiringAuthority != null) {
							when {
								evidence.observedElapsedRealtimeNanos > retiringAuthority.cutoffElapsedNanos ->
									return@transaction DeliveryAdmissionResult.SessionCutoff(
										retiringAuthority.cutoffElapsedNanos,
									)
								evidence.observedElapsedRealtimeNanos == retiringAuthority.cutoffElapsedNanos ->
									retiringAuthority.authorizationAtInclusiveCutoff?.let { authorization ->
										observedTimeAuthorization = authorization
									}
							}
						}
					}
					if (intervalStart < evidence.observedElapsedRealtimeNanos) {
						val startAuthorizationRows = brokerDao.authorizationAt(
							evidence.source.stableCode,
							evidence.registrationGeneration,
							evidence.clockDomainId,
							intervalStart,
						)
						val startAuthorization = runCatchingNonCancellation {
							startAuthorizationRows.toAuthorizationSnapshotOrNull()
						}.getOrNull()
						if (startAuthorization?.authorizationRevision !=
							observedTimeAuthorization?.authorizationRevision
						) {
							return@transaction DeliveryAdmissionResult.PermanentFailure(
								AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED,
							)
						}
					}
					val qualifiedTimeAuthorization = observedTimeAuthorization
					if (qualifiedTimeAuthorization == null || qualifiedTimeAuthorization.isDenied) {
						emptyDeliveryFailure = AdmissionFailureCode.STALE_SOURCE_POLICY
						continue
					}
					if (!qualifiedTimeAuthorization.matchesCapturedEnvelope(evidence)) {
						emptyDeliveryFailure = AdmissionFailureCode.STALE_SOURCE_POLICY
						continue
					}
					val authorization = qualifiedTimeAuthorization.qualifiedForFreshness(
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
					val captureAuthorization = captureMembers.singleOrNull()
					val captureKey = captureAuthorization?.captureSessionKey(
						source = evidence.source,
						clockDomainId = evidence.clockDomainId,
					)
					if (evidence.source == SourceKind.STEPS &&
						evidenceState.retainedFromMs?.let { evidence.acquiredAtMs < it } == true
					) {
						markStepsRetentionTruncationIfNeeded(captureKey, evidenceState, stateDao)
						emptyDeliveryFailure = AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY
						continue
					}
					if (captureAuthorization != null) {
						val key = captureKey
							?: return@transaction DeliveryAdmissionResult.PermanentFailure(
								AdmissionFailureCode.STALE_SESSION_MANIFEST,
							)
						val captureAuthority = captureAuthorities[key] ?: loadCaptureSessionAuthority(key)
							.also { authority -> captureAuthorities[key] = authority }
						when (captureAuthority) {
							CaptureSessionAuthority.Active -> Unit
							CaptureSessionAuthority.Invalid -> {
								return@transaction DeliveryAdmissionResult.PermanentFailure(
									AdmissionFailureCode.STALE_SESSION_MANIFEST,
								)
							}
							is CaptureSessionAuthority.Stopping -> {
								if (evidence.observedElapsedRealtimeNanos > captureAuthority.cutoffElapsedNanos) {
									return@transaction DeliveryAdmissionResult.SessionCutoff(
										captureAuthority.cutoffElapsedNanos,
									)
								}
							}
						}
					}
					authorized += AuthorizedDeliveryUnit(
						unit,
						authorization,
						captureAuthorization,
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
				checkpoint?.let {
					persistAtomicCheckpoint(
						checkpoint = it,
						admissionOrdinal = rowIds.single(),
						causalOrderElapsedRealtimeNanos =
							delivery.units.single().evidence.receivedElapsedRealtimeNanos,
					)
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
		}.getOrElse { failure ->
			if (!failure.isTrackingOperationalFailure()) throw failure
			DeliveryAdmissionResult.RetryableFailure(AdmissionFailureCode.STORAGE_UNAVAILABLE)
		}
	}

	/**
	 * Resolves only an exact durable session-retirement fence captured by this delivery.
	 *
	 * The observed-time authorization may already have rotated at the inclusive stop boundary. This
	 * lookup returns the exact historical authorization only for equality, or a no-mutation cutoff
	 * after it, so the source can rebuild its immutable provider delivery. Any mismatch falls through
	 * to ordinary admission checks.
	 */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun loadRetiringCaptureAuthority(
		evidence: SourceEvidenceCandidate<*>,
		brokerDao: SourceBrokerDao,
		currentAuthorization: SourceAuthorizationSnapshot?,
	): RetiringCaptureAuthority? {
		val physicalFingerprint = evidence.physicalConfigurationFingerprint ?: return null
		val authorizationRevision = evidence.authorizationRevision ?: return null
		val eligibilityFingerprint = evidence.registrationEligibilityFingerprint ?: return null
		val physical = brokerDao.registration(
			evidence.source.stableCode,
			evidence.registrationGeneration,
		) ?: return null
		val providerAcceptedElapsedNanos = physical.acceptedElapsedRealtimeNanos ?: return null
		if (physical.sourceKind != evidence.source.stableCode ||
			physical.registrationGeneration != evidence.registrationGeneration ||
			physical.sourceInstanceId != evidence.sourceInstanceId.value ||
			physical.clockDomainId != evidence.clockDomainId ||
			physical.physicalConfigurationFingerprint != physicalFingerprint ||
			physical.collectedDataEpoch != evidence.capturedCollectedDataEpoch ||
			physical.status !in RETIRING_CUTOFF_PHYSICAL_STATES
		) return null
		val claimedAuthorizationRows = brokerDao.authorizationRevision(
			evidence.source.stableCode,
			evidence.registrationGeneration,
			authorizationRevision,
		)
		val claimedAuthorization = runCatchingNonCancellation {
			claimedAuthorizationRows.toAuthorizationSnapshotOrNull()
		}.getOrNull() ?: return null
		if (claimedAuthorization.isDenied ||
			claimedAuthorization.effectiveBootId != evidence.clockDomainId ||
			claimedAuthorization.authorizationFingerprint != eligibilityFingerprint ||
			claimedAuthorization.purposeEligibilityMask != evidence.registrationPurposeEligibilityMask
		) return null
		val captureAuthorization = claimedAuthorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		} ?: return null
		val demandId = captureAuthorization.demandId ?: return null
		val demand = brokerDao.demandsByIds(listOf(demandId)).singleOrNull() ?: return null
		val retirementCutoff = demand.retireElapsedRealtimeNanos ?: return null
		val observedAgeNanos = evidence.receivedElapsedRealtimeNanos -
			evidence.observedElapsedRealtimeNanos
		if (!demand.matchesImmutableAuthority(captureAuthorization) ||
			captureAuthorization.memberId != "demand:$demandId" ||
			demand.status != SourceDemandEntity.STATUS_RETIRING ||
			demand.requestedBootId != evidence.clockDomainId ||
			demand.retireBootId != evidence.clockDomainId ||
			demand.retiredAtMs == null ||
			demand.requestedElapsedRealtimeNanos > retirementCutoff ||
			claimedAuthorization.effectiveElapsedRealtimeNanos > retirementCutoff ||
			providerAcceptedElapsedNanos > retirementCutoff ||
			observedAgeNanos < 0L ||
			observedAgeNanos > demand.maximumAgeMs.saturatedMillisecondsToNanos()
		) return null
		val key = captureAuthorization.captureSessionKey(
			source = evidence.source,
			clockDomainId = evidence.clockDomainId,
		) ?: return null
		val sessionAuthority = loadCaptureSessionAuthority(key)
		val durableCutoff = (sessionAuthority as? CaptureSessionAuthority.Stopping)
			?.cutoffElapsedNanos
			?.takeIf { cutoff -> cutoff == retirementCutoff }
			?: return null
		val policyAuthority = database.sourcePolicyDao().authority()
		val currentPolicy = database.sourcePolicyDao().policyAtRevision(
			key.sourcePolicyRevision,
			evidence.source.stableCode,
		)
		val unchangedCapturePolicy = policyAuthority?.bootstrapState == SOURCE_POLICY_STATE_ACTIVE &&
			policyAuthority.currentPolicyRevision == key.sourcePolicyRevision &&
			currentPolicy?.enabled == true &&
			currentPolicy.capturePersistenceEligible &&
			currentPolicy.captureConsentEpoch == key.consentEpoch &&
			currentPolicy.effectiveBootId == evidence.clockDomainId &&
			currentPolicy.effectiveElapsedRealtimeNanos <= durableCutoff
		val exactRetirementOnly = currentAuthorization?.isExactRetirementOf(
			claimed = claimedAuthorization,
			retiredCapture = captureAuthorization,
			cutoffElapsedNanos = durableCutoff,
		) == true
		return RetiringCaptureAuthority(
			cutoffElapsedNanos = durableCutoff,
			authorizationAtInclusiveCutoff = claimedAuthorization.takeIf {
				unchangedCapturePolicy && exactRetirementOnly
			},
		)
	}

	/**
	 * Resolves current capture ownership without rewriting observed-time authorization history.
	 * Exact replay is handled before this check, so a stopped session can still acknowledge a
	 * delivery that was already durable. New evidence must still belong to the current run and boot.
	 */
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun loadCaptureSessionAuthority(key: CaptureSessionKey): CaptureSessionAuthority {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(key.logicalTrackingId) ?: return CaptureSessionAuthority.Invalid
		val run = sessionDao.serviceRun(key.serviceRunId) ?: return CaptureSessionAuthority.Invalid
		val manifest = sessionDao.manifestByServiceRunRevision(
			key.serviceRunId,
			key.manifestRevision,
		) ?: return CaptureSessionAuthority.Invalid
		val manifestBindings = sessionDao.manifestSources(
			key.logicalTrackingId,
			key.manifestRevision,
		)
		if (!SessionManifestIntegrity.verify(manifest, manifestBindings)) {
			return CaptureSessionAuthority.Invalid
		}
		val binding = manifestBindings.singleOrNull { candidate ->
			candidate.sourceKind == key.source.stableCode &&
				candidate.purpose == MANIFEST_PURPOSE_CAPTURE
		}?.takeIf { candidate -> candidate.persistenceEligible }
			?: return CaptureSessionAuthority.Invalid
		val currentManifestRevision = session.currentManifestRevision
			?.takeIf { revision -> revision >= key.manifestRevision }
			?: return CaptureSessionAuthority.Invalid
		val currentManifest = if (currentManifestRevision == key.manifestRevision) {
			manifest
		} else {
			sessionDao.manifestByServiceRunRevision(
				key.serviceRunId,
				currentManifestRevision,
			) ?: return CaptureSessionAuthority.Invalid
		}
		val currentManifestBindings = if (currentManifestRevision == key.manifestRevision) {
			manifestBindings
		} else {
			sessionDao.manifestSources(key.logicalTrackingId, currentManifestRevision)
		}
		val currentManifestMatches = currentManifest.logicalTrackingId == key.logicalTrackingId &&
			currentManifest.serviceRunId == key.serviceRunId &&
			currentManifest.manifestRevision == currentManifestRevision &&
			currentManifest.effectiveBootId == key.clockDomainId &&
			SessionManifestIntegrity.verify(currentManifest, currentManifestBindings)
		if (!currentManifestMatches) return CaptureSessionAuthority.Invalid
		val immutableAuthorityMatches = session.currentServiceRunId == key.serviceRunId &&
			session.currentManifestRevision == currentManifestRevision &&
			session.completedAtMs == null &&
			session.clockDomainId == key.clockDomainId &&
			session.lifecycleBootId == key.clockDomainId &&
			session.lifecycleLeaseGeneration == run.leaseGeneration &&
			key.leaseGeneration <= run.leaseGeneration &&
			run.logicalTrackingId == key.logicalTrackingId &&
			run.completedAtMs == null &&
			run.bootId == key.clockDomainId &&
			manifest.logicalTrackingId == key.logicalTrackingId &&
			manifest.serviceRunId == key.serviceRunId &&
			manifest.effectiveBootId == key.clockDomainId &&
			manifest.sourcePolicyRevision == key.sourcePolicyRevision &&
			binding.consentEpoch == key.consentEpoch &&
			binding.persistenceEligible
		if (!immutableAuthorityMatches) return CaptureSessionAuthority.Invalid
		return when {
			session.state in ADMISSION_SESSION_STATES && run.state in ADMISSION_RUN_STATES &&
				session.cutoffAtMs == null && session.cutoffElapsedNanos == null ->
				CaptureSessionAuthority.Active
			session.state == SESSION_STATE_ACTIVE && run.state == SESSION_STATE_STOPPING &&
				session.cutoffAtMs == null && session.cutoffElapsedNanos == null -> {
				loadSuspendingCaptureCutoff(
					key = key,
					session = session,
					run = run,
					currentManifest = currentManifest,
					currentBindings = currentManifestBindings,
				)?.let { cutoff ->
					CaptureSessionAuthority.Stopping(cutoff)
				}
					?: CaptureSessionAuthority.Invalid
			}
			session.state == SESSION_STATE_STOPPING && run.state == SESSION_STATE_STOPPING -> {
				session.cutoffAtMs ?: return CaptureSessionAuthority.Invalid
				val cutoff = session.cutoffElapsedNanos ?: return CaptureSessionAuthority.Invalid
				CaptureSessionAuthority.Stopping(cutoff)
			}
			else -> CaptureSessionAuthority.Invalid
		}
	}

	/**
	 * Resolves the durable service-run suspension boundary without treating the logical session as
	 * terminal. The current recovery intent and its exact source action are the only durable cutoff
	 * authority while the session remains ACTIVE and its current run is STOPPING.
	 */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun loadSuspendingCaptureCutoff(
		key: CaptureSessionKey,
		session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		currentManifest: SessionManifestVersionEntity,
		currentBindings: List<SessionManifestSourceEntity>,
	): Long? {
		val sessionDao = database.sourceSessionDao()
		val currentManifestRevision = currentManifest.manifestRevision
		val currentCaptureBinding = currentBindings.singleOrNull { binding ->
			binding.sourceKind == key.source.stableCode &&
				binding.purpose == MANIFEST_PURPOSE_CAPTURE
		}?.takeIf { binding -> binding.persistenceEligible } ?: return null
		val intentRevision = session.currentIntentRevision ?: return null
		val intent = sessionDao.lifecycleIntent(key.logicalTrackingId, intentRevision) ?: return null
		if (intent.logicalTrackingId != key.logicalTrackingId ||
			intent.manifestRevision != currentManifestRevision ||
			intent.desiredState != LIFECYCLE_DESIRED_ACTIVE ||
			intent.startOrigin != LIFECYCLE_START_ORIGIN_RECOVERY ||
			intent.requestBootId != key.clockDomainId ||
			currentManifest.effectiveElapsedRealtimeNanos > intent.requestedElapsedRealtimeNanos ||
			intent.stopReason.isNullOrBlank() ||
			intent.stopDeadlineBootId != null ||
			intent.stopDeadlineElapsedRealtimeNanos != null ||
			intent.intentChecksum != stableLifecycleChecksum(
				intent.logicalTrackingId,
				intent.intentRevision,
				intent.manifestRevision,
				intent.desiredState,
				intent.stopReason,
				intent.requestBootId,
				intent.requestedElapsedRealtimeNanos,
			)
		) return null
		val currentSourceActions = sessionDao.lifecycleActions(key.logicalTrackingId).filter { action ->
			action.logicalTrackingId == key.logicalTrackingId &&
				action.serviceRunId == key.serviceRunId &&
				action.sourceKind == key.source.stableCode &&
				action.leaseGeneration == session.lifecycleLeaseGeneration &&
				action.status in SUSPENDING_ACTION_STATES
		}
		val action = currentSourceActions.singleOrNull() ?: return null
		if (action.actionId != stableLifecycleChecksum(
				key.logicalTrackingId,
				intent.intentRevision,
				key.source.stableCode,
				LIFECYCLE_ACTION_DESIRED_STOPPED,
			) ||
			action.manifestRevision != currentManifestRevision ||
			action.actionFamily != LIFECYCLE_ACTION_FAMILY_SOURCE_RUNTIME ||
			action.desiredState != LIFECYCLE_ACTION_DESIRED_STOPPED ||
			action.desiredPlanRevision != session.desiredPlanRevision ||
			action.sourcePolicyRevision != currentManifest.sourcePolicyRevision ||
			action.consentEpoch != currentCaptureBinding.consentEpoch ||
			action.startOrigin != LIFECYCLE_START_ORIGIN_POLICY_RECONCILIATION ||
			action.bootId != key.clockDomainId ||
			action.leaseGeneration != run.leaseGeneration ||
			action.requestedAtMs != intent.requestedWallTimeMs ||
			action.requestedElapsedRealtimeNanos != intent.requestedElapsedRealtimeNanos
		) return null
		return action.requestedElapsedRealtimeNanos
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

private data class RetiringCaptureAuthority(
	val cutoffElapsedNanos: Long,
	val authorizationAtInclusiveCutoff: SourceAuthorizationSnapshot?,
)

private data class CaptureSessionKey(
	val source: SourceKind,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val leaseGeneration: Long,
	val sourcePolicyRevision: Long,
	val consentEpoch: Long,
	val clockDomainId: String,
)

private sealed interface CaptureSessionAuthority {
	data object Active : CaptureSessionAuthority
	data class Stopping(val cutoffElapsedNanos: Long) : CaptureSessionAuthority
	data object Invalid : CaptureSessionAuthority
}

// Each nullable term is part of the immutable session authority; partial keys must fail closed.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun SourceAuthorizationEntity.captureSessionKey(
	source: SourceKind,
	clockDomainId: String,
): CaptureSessionKey? {
	if (sourceKind != source.stableCode || purpose != MANIFEST_PURPOSE_CAPTURE ||
		!persistenceEligible || effectiveBootId != clockDomainId
	) return null
	return CaptureSessionKey(
		source = source,
		logicalTrackingId = logicalTrackingId ?: return null,
		serviceRunId = serviceRunId ?: return null,
		manifestRevision = manifestRevision?.takeIf { it > 0L } ?: return null,
		leaseGeneration = lifecycleLeaseGeneration?.takeIf { it > 0L } ?: return null,
		sourcePolicyRevision = sourcePolicyRevision?.takeIf { it > 0L } ?: return null,
		consentEpoch = consentEpoch?.takeIf { it >= 0L } ?: return null,
		clockDomainId = clockDomainId,
	)
}

private fun SourceAuthorizationSnapshot.matchesCapturedEnvelope(
	evidence: SourceEvidenceCandidate<*>,
): Boolean {
	if (evidence.authorizationRevision == null) return true
	return authorizationRevision == evidence.authorizationRevision &&
		authorizationFingerprint == evidence.registrationEligibilityFingerprint &&
		purposeEligibilityMask == evidence.registrationPurposeEligibilityMask &&
		effectiveBootId == evidence.clockDomainId
}

private fun SourceAuthorizationSnapshot.isExactRetirementOf(
	claimed: SourceAuthorizationSnapshot,
	retiredCapture: SourceAuthorizationEntity,
	cutoffElapsedNanos: Long,
): Boolean {
	if (authorizationRevision <= claimed.authorizationRevision ||
		effectiveBootId != claimed.effectiveBootId ||
		effectiveElapsedRealtimeNanos != cutoffElapsedNanos
	) return false
	val expectedRemaining = claimed.authorizedMembers
		.filterNot { member -> member.memberId == retiredCapture.memberId }
		.sortedBy(SourceAuthorizationEntity::memberId)
	val actualRemaining = authorizedMembers.sortedBy(SourceAuthorizationEntity::memberId)
	return actualRemaining.size == expectedRemaining.size &&
		actualRemaining.zip(expectedRemaining).all { (actual, expected) ->
			actual.hasSameImmutableDemandAuthority(expected)
		}
}

@Suppress("CyclomaticComplexMethod") // Every immutable authority term must match explicitly.
private fun SourceAuthorizationEntity.hasSameImmutableDemandAuthority(
	other: SourceAuthorizationEntity,
): Boolean = memberId == other.memberId &&
	sourceKind == other.sourceKind &&
	registrationGeneration == other.registrationGeneration &&
	demandId == other.demandId &&
	consumerId == other.consumerId &&
	purpose == other.purpose &&
	sourcePolicyRevision == other.sourcePolicyRevision &&
	consentEpoch == other.consentEpoch &&
	persistenceEligible == other.persistenceEligible &&
	logicalTrackingId == other.logicalTrackingId &&
	serviceRunId == other.serviceRunId &&
	manifestRevision == other.manifestRevision &&
	lifecycleLeaseGeneration == other.lifecycleLeaseGeneration

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
		if (!demand.matchesImmutableAuthority(member)) return@mapNotNull null
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

private fun SourceDemandEntity.matchesImmutableAuthority(
	member: SourceAuthorizationEntity,
): Boolean = sourceKind == member.sourceKind &&
	consumerId == member.consumerId &&
	purpose == member.purpose &&
	sourcePolicyRevision == member.sourcePolicyRevision &&
	consentEpoch == member.consentEpoch &&
	persistenceEligible == member.persistenceEligible &&
	logicalTrackingId == member.logicalTrackingId &&
	serviceRunId == member.serviceRunId &&
	manifestRevision == member.manifestRevision &&
	lifecycleLeaseGeneration == member.lifecycleLeaseGeneration

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
	val hasCanonicalStepsWallTime = source != SourceKind.STEPS || wallTimeMs == acquiredAtMs
	return hasCanonicalStepsWallTime && interval.clockDomainId == clockDomainId &&
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

private fun SensorAdmissionCheckpoint.matches(delivery: SourceDeliveryCandidate): Boolean =
	delivery.units.singleOrNull()?.evidence?.let { evidence -> matches(evidence) } == true

private suspend fun SourceDeliveryCandidate.checkpointRegistrationFailure(
	brokerDao: SourceBrokerDao,
): AdmissionFailureCode? {
	val unit = units.singleOrNull() ?: return AdmissionFailureCode.STALE_REGISTRATION_GENERATION
	val evidence = unit.evidence
	val physicalFingerprint = evidence.physicalConfigurationFingerprint
		?: return AdmissionFailureCode.STALE_REGISTRATION_GENERATION
	val interval = evidence.observedTimeInterval()
	val endRegistration = brokerDao.registrationAtObservedTime(
		evidence.source.stableCode,
		evidence.registrationGeneration,
		evidence.sourceInstanceId.value,
		evidence.clockDomainId,
		physicalFingerprint,
		interval.endElapsedRealtimeNanos,
	)
	val startRegistration = if (interval.startElapsedRealtimeNanos == interval.endElapsedRealtimeNanos) {
		endRegistration
	} else {
		brokerDao.registrationAtObservedTime(
			evidence.source.stableCode,
			evidence.registrationGeneration,
			evidence.sourceInstanceId.value,
			evidence.clockDomainId,
			physicalFingerprint,
			interval.startElapsedRealtimeNanos,
		)
	}
	return when {
		startRegistration == null && endRegistration == null ->
			AdmissionFailureCode.STALE_REGISTRATION_GENERATION
		startRegistration != endRegistration ->
			AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
		else -> null
	}
}

private suspend fun SourceDeliveryCandidate.checkpointReplayFailure(
	checkpoint: SensorAdmissionCheckpoint,
	stored: SourceDeliveryUnitIdentityRow,
	brokerDao: SourceBrokerDao,
): AdmissionFailureCode? {
	val evidence = units.single().evidence
	if (!stored.matchesCheckpointEnvelope(checkpoint, evidence)) {
		return AdmissionFailureCode.STALE_REGISTRATION_GENERATION
	}
	return evidence.checkpointReplayAuthorizationFailure(stored, brokerDao)
}

private fun SourceDeliveryUnitIdentityRow.matchesCheckpointEnvelope(
	checkpoint: SensorAdmissionCheckpoint,
	evidence: SourceEvidenceCandidate<*>,
): Boolean {
	val matchesCheckpoint = sourceInstanceId == checkpoint.sourceInstanceId &&
		registrationGeneration == checkpoint.registrationGeneration
	val matchesEvidence = sourceInstanceId == evidence.sourceInstanceId.value &&
		registrationGeneration == evidence.registrationGeneration &&
		physicalConfigurationFingerprint == evidence.physicalConfigurationFingerprint
	return matchesCheckpoint && matchesEvidence
}

private suspend fun SourceEvidenceCandidate<*>.checkpointReplayAuthorizationFailure(
	stored: SourceDeliveryUnitIdentityRow,
	brokerDao: SourceBrokerDao,
): AdmissionFailureCode? {
	val authorization = authorizationAcrossIntrinsicInterval(brokerDao)
	val endAuthorization = authorization.end ?: return AdmissionFailureCode.STALE_SOURCE_POLICY
	if (endAuthorization.isDenied) return AdmissionFailureCode.STALE_SOURCE_POLICY
	val startAuthorization = authorization.start
		?: return AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
	return when {
		startAuthorization.authorizationRevision != endAuthorization.authorizationRevision ->
			AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED
		startAuthorization.isDenied -> AdmissionFailureCode.STALE_SOURCE_POLICY
		authorizationRevision != endAuthorization.authorizationRevision ||
			stored.authorizationRevision != endAuthorization.authorizationRevision ->
			AdmissionFailureCode.STALE_SOURCE_POLICY
		else -> null
	}
}

private suspend fun SourceEvidenceCandidate<*>.authorizationAcrossIntrinsicInterval(
	brokerDao: SourceBrokerDao,
): IntrinsicIntervalAuthorization {
	val interval = observedTimeInterval()
	val endAuthorization = brokerDao.authorizationAt(
		source.stableCode,
		registrationGeneration,
		clockDomainId,
		interval.endElapsedRealtimeNanos,
	).toAuthorizationSnapshotOrNull()
	val startAuthorization = if (interval.startElapsedRealtimeNanos == interval.endElapsedRealtimeNanos) {
		endAuthorization
	} else {
		brokerDao.authorizationAt(
			source.stableCode,
			registrationGeneration,
			clockDomainId,
			interval.startElapsedRealtimeNanos,
		).toAuthorizationSnapshotOrNull()
	}
	return IntrinsicIntervalAuthorization(startAuthorization, endAuthorization)
}

private data class IntrinsicIntervalAuthorization(
	val start: SourceAuthorizationSnapshot?,
	val end: SourceAuthorizationSnapshot?,
)

private fun SourceDeliveryCandidate.hasValidObservedTimes(): Boolean = units.all { unit ->
	val evidence = unit.evidence
	evidence.hasValidObservedTime() &&
		unit.preservesIntrinsicSensorInterval() &&
		unit.observedIntervalStartElapsedRealtimeNanos >= 0L &&
		unit.observedIntervalStartElapsedRealtimeNanos <= evidence.observedElapsedRealtimeNanos &&
		evidence.observedElapsedRealtimeNanos <= evidence.receivedElapsedRealtimeNanos
}

private fun SourceDeliveryUnit.preservesIntrinsicSensorInterval(): Boolean = when (evidence.payload) {
	is StepCounterWindowPayload, is PressureWindowPayload ->
		observedIntervalStartElapsedRealtimeNanos ==
			evidence.observedTimeInterval().startElapsedRealtimeNanos
	else -> true
}

private fun SourceEvidenceCandidate<*>.observedIntervalStartElapsedRealtimeNanos(): Long =
	observedTimeInterval().startElapsedRealtimeNanos

private const val MANIFEST_PURPOSE_CAPTURE = "SESSION_CAPTURE"
private const val SESSION_STATE_ACTIVE = "ACTIVE"
private const val SESSION_STATE_STOPPING = "STOPPING"
private const val LIFECYCLE_DESIRED_ACTIVE = "ACTIVE"
private const val LIFECYCLE_START_ORIGIN_RECOVERY = "RECOVERY"
private const val LIFECYCLE_START_ORIGIN_POLICY_RECONCILIATION = "POLICY_RECONCILIATION"
private const val LIFECYCLE_ACTION_FAMILY_SOURCE_RUNTIME = "SOURCE_RUNTIME"
private const val LIFECYCLE_ACTION_DESIRED_STOPPED = "STOPPED"
private const val SOURCE_POLICY_STATE_ACTIVE = "ACTIVE"
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private val SUSPENDING_ACTION_STATES = setOf(
	"PENDING",
	"APPLYING",
	"CLEANUP_REQUIRED",
	"TEMPORARILY_ILLEGAL",
)
private val RETIRING_CUTOFF_PHYSICAL_STATES = setOf(
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	ProviderRegistrationGenerationEntity.STATUS_RETIRING,
	ProviderRegistrationGenerationEntity.STATUS_RETIRED,
)
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
