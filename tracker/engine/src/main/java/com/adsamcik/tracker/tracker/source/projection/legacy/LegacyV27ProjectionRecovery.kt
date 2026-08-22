package com.adsamcik.tracker.tracker.source.projection.legacy

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameEffectCodec
import com.adsamcik.tracker.tracker.source.projection.releasedV1EventTrackingFrame
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains the immutable projection interval captured while a released v27 database becomes v28.
 *
 * This is deliberately a one-version recovery adapter, not a general projection runtime. It
 * bridges only the v27 Steps/Pressure frame into the existing typed tables, suppresses effects
 * that are unsafe or had no production consumer, and never invokes the current Location writer.
 */
@Singleton
class LegacyV27ProjectionRecovery @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val clock: Clock,
	private val payloadCodec: SourcePayloadCodec,
	private val eventFrameBridge: LegacyV27EventFrameBridge,
) {
	private val mutex = Mutex()
	private val ownerToken = "legacy-v27-${UUID.randomUUID()}"

	suspend fun recover(batchSize: Int = DEFAULT_BATCH_SIZE): LegacyV27ProjectionRecoveryResult {
		require(batchSize > 0)
		return mutex.withLock { recoverSingleFlight(batchSize) }
	}

	private suspend fun recoverSingleFlight(batchSize: Int): LegacyV27ProjectionRecoveryResult {
		val dao = database.legacyV27ProjectionDrainDao()
		val initial = dao.get() ?: return LegacyV27ProjectionRecoveryResult.NotRequired
		val lifecycle = lifecycleStore.snapshot()
		if (lifecycle.epoch != initial.collectedDataEpoch) {
			return LegacyV27ProjectionRecoveryResult.LifecycleSuperseded
		}
		initial.terminalResultOrNull()?.let { return it }

		val bootId = bootClockDomainProvider.current()
		val acquiredAtElapsed = clock.elapsedRealtimeNanos()
		val acquiredLeaseExpiry = acquiredAtElapsed + LEASE_DURATION_NANOS
		if (dao.acquireLease(
				bootId = bootId,
				ownerToken = ownerToken,
				nowElapsedNanos = acquiredAtElapsed,
				leaseExpiresElapsedNanos = acquiredLeaseExpiry,
				startedAtMs = clock.currentTimeMillis(),
			) != 1
		) {
			return LegacyV27ProjectionRecoveryResult.LeaseUnavailable
		}
		val owned = dao.get() ?: return LegacyV27ProjectionRecoveryResult.LifecycleSuperseded
		val lease = OwnedLease(
			bootId = bootId,
			ownerToken = ownerToken,
			generation = owned.leaseGeneration,
			expiresElapsedRealtimeNanos = requireNotNull(owned.leaseExpiresElapsedNanos),
			collectedDataEpoch = owned.collectedDataEpoch,
		)

		return try {
			validateFrozenContract(owned, dao.targets(), lease)
			for (target in dao.targets()) {
				when (target.projectionId) {
					ACTIVITY_AUTOMATION -> suppressTarget(
						target = target,
						disposition = LegacyV27ProjectionTargetEntity
							.DISPOSITION_SUPPRESSED_STALE_CONTROL,
						lease = lease,
						deleteFailures = true,
					)
					EXPLICIT_TRACKING_JOINS -> suppressTarget(
						target = target,
						disposition = LegacyV27ProjectionTargetEntity
							.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
						lease = lease,
						deleteFailures = true,
					)
					LOCATION_DOMAIN -> preserveLocationShadow(target, lease)
					EVENT_TRACKING_FRAME -> bridgeEventFrames(target, lease, batchSize)
				}
			}
			completeDrain(lease)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: LifecycleSupersededException) {
			LegacyV27ProjectionRecoveryResult.LifecycleSuperseded
		} catch (blocked: UnsupportedLegacyContractException) {
			val blockedAt = clock.elapsedRealtimeNanos()
			val persisted = dao.blockUnsupportedTarget(
				failureCode = blocked.failureCode,
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = blockedAt,
			)
			if (persisted == 1) {
				LegacyV27ProjectionRecoveryResult.Blocked(blocked.failureCode)
			} else {
				LegacyV27ProjectionRecoveryResult.LifecycleSuperseded
			}
		} catch (failure: Exception) {
			val failureCode = failure.retryableFailureCode()
			val persisted = dao.markRetryableFailure(
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = clock.elapsedRealtimeNanos(),
				failureCode = failureCode,
			)
			if (persisted == 1) {
				LegacyV27ProjectionRecoveryResult.FailedRetryable(failureCode)
			} else {
				LegacyV27ProjectionRecoveryResult.LifecycleSuperseded
			}
		}
	}

	private suspend fun validateFrozenContract(
		drain: LegacyV27ProjectionDrainEntity,
		targets: List<LegacyV27ProjectionTargetEntity>,
		lease: OwnedLease,
	) {
		if (drain.sourceSchemaVersion != LegacyV27ProjectionDrainEntity.SOURCE_SCHEMA_VERSION ||
			drain.contractVersion != LegacyV27ProjectionDrainEntity.CONTRACT_VERSION ||
			drain.cutoffAdmissionOrdinal < 0L || drain.collectedDataEpoch < 0L
		) {
			throw UnsupportedLegacyContractException(FAILURE_UNSUPPORTED_CONTRACT)
		}
		if (targets.any { target ->
				target.projectionVersion != RELEASED_PROJECTION_VERSION ||
					target.projectionId !in SUPPORTED_PROJECTIONS ||
					target.initialRegistrationStatus !in SUPPORTED_INITIAL_REGISTRATION_STATUSES ||
					target.initialActivationOrdinal <= 0L ||
					target.initialActivationOrdinal - 1L > drain.cutoffAdmissionOrdinal ||
					target.initialCheckpointOrdinal < target.initialActivationOrdinal - 1L ||
					target.initialCheckpointOrdinal > drain.cutoffAdmissionOrdinal ||
					target.lastCompletedOrdinal < 0L ||
					target.lastCompletedOrdinal > target.requiredThroughOrdinal ||
					target.requiredThroughOrdinal != drain.cutoffAdmissionOrdinal ||
					!target.hasSupportedDisposition()
			}
		) {
			throw UnsupportedLegacyContractException(FAILURE_UNSUPPORTED_TARGET)
		}
		if (targets.map { it.projectionId to it.projectionVersion }.toSet() !=
			SUPPORTED_PROJECTIONS.map { it to RELEASED_PROJECTION_VERSION }.toSet()
		) {
			throw UnsupportedLegacyContractException(FAILURE_INCOMPLETE_TARGET_SET)
		}
		assertLifecycleCurrent(lease)
		if (database.legacyV27ProjectionDrainDao().unknownPendingOutboxGenerations().isNotEmpty()) {
			throw UnsupportedLegacyContractException(FAILURE_UNKNOWN_OUTBOX_GENERATION)
		}
	}

	private fun LegacyV27ProjectionTargetEntity.hasSupportedDisposition(): Boolean {
		val allowed = when (projectionId) {
			ACTIVITY_AUTOMATION -> setOf(
				LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_STALE_CONTROL,
			)
			EVENT_TRACKING_FRAME -> setOf(
				LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
				LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS,
				LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
			)
			EXPLICIT_TRACKING_JOINS -> setOf(
				LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
			)
			LOCATION_DOMAIN -> setOf(
				LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_RETAINED,
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
			)
			else -> emptySet()
		}
		if (disposition !in allowed) return false
		return if (disposition == LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
			completedAtMs == null
		} else {
			completedAtMs != null && lastCompletedOrdinal == requiredThroughOrdinal
		}
	}

	private suspend fun suppressTarget(
		target: LegacyV27ProjectionTargetEntity,
		disposition: String,
		lease: OwnedLease,
		deleteFailures: Boolean,
	) {
		if (target.disposition != LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
			terminalizeStrandedOutbox(target, lease)
			return
		}
		withOwnedTransaction(lease) { nowElapsed ->
			val current = requireTarget(target)
			if (current.disposition != LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
				return@withOwnedTransaction
			}
			if (current.lastCompletedOrdinal < current.requiredThroughOrdinal) {
				requireOwnedMutation(
					database.legacyV27ProjectionDrainDao().advanceTarget(
						projectionId = current.projectionId,
						projectionVersion = current.projectionVersion,
						expectedCompletedOrdinal = current.lastCompletedOrdinal,
						newCompletedOrdinal = current.requiredThroughOrdinal,
						bootId = lease.bootId,
						ownerToken = lease.ownerToken,
						leaseGeneration = lease.generation,
						nowElapsedNanos = nowElapsed,
					),
				)
			}
			terminalizeOutbox(current, disposition, lease, nowElapsed)
			cleanupLegacyProjectionState(current.projectionId, lease, nowElapsed, deleteFailures)
			requireOwnedMutation(
				database.legacyV27ProjectionDrainDao().terminalizeTarget(
					projectionId = current.projectionId,
					projectionVersion = current.projectionVersion,
					expectedCompletedOrdinal = current.requiredThroughOrdinal,
					disposition = disposition,
					completedAtMs = clock.currentTimeMillis(),
					failureCode = current.failureCode,
					bootId = lease.bootId,
					ownerToken = lease.ownerToken,
					leaseGeneration = lease.generation,
					nowElapsedNanos = nowElapsed,
				),
			)
		}
	}

	private suspend fun preserveLocationShadow(
		target: LegacyV27ProjectionTargetEntity,
		lease: OwnedLease,
	) {
		if (target.initialRegistrationStatus != REGISTRATION_ACTIVE) {
			suppressTarget(
				target,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
				lease,
				deleteFailures = true,
			)
			return
		}
		val completeShadow = target.initialCheckpointOrdinal >= target.requiredThroughOrdinal
		val disposition = if (completeShadow) {
			LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_RETAINED
		} else {
			LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL
		}
		if (!completeShadow && target.disposition == LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
			markTargetPartial(target, FAILURE_LOCATION_SHADOW_PARTIAL, lease)
		}
		suppressTarget(target, disposition, lease, deleteFailures = true)
	}

	private suspend fun bridgeEventFrames(
		initialTarget: LegacyV27ProjectionTargetEntity,
		lease: OwnedLease,
		batchSize: Int,
	) {
		if (initialTarget.disposition != LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
			terminalizeStrandedOutbox(initialTarget, lease)
			return
		}
		var target = requireTarget(initialTarget)
		val hasReleasedWriterActivation = target.initialRegistrationStatus == REGISTRATION_ACTIVE
		if (!hasReleasedWriterActivation) {
			if (target.lastCompletedOrdinal < target.requiredThroughOrdinal) {
				advanceTarget(target, target.requiredThroughOrdinal, lease)
			}
			target = requireTarget(target)
		} else {
			val inactiveThrough = minOf(
				target.requiredThroughOrdinal,
				(target.initialActivationOrdinal - 1L).coerceAtLeast(0L),
			)
			if (target.lastCompletedOrdinal < inactiveThrough) {
				advanceTarget(target, inactiveThrough, lease)
				target = requireTarget(target)
			}
		}

		while (hasReleasedWriterActivation &&
			target.lastCompletedOrdinal < target.requiredThroughOrdinal
		) {
			assertLifecycleCurrent(lease)
			val rows = database.sourceEventWalDao().eventsAfterThrough(
				afterOrdinal = target.lastCompletedOrdinal,
				throughOrdinal = target.requiredThroughOrdinal,
				limit = batchSize,
			)
			if (rows.isEmpty()) {
				recordGapAndAdvance(
					target = target,
					newCompletedOrdinal = target.requiredThroughOrdinal,
					lease = lease,
				)
				break
			}
			for (row in rows) {
				target = requireTarget(target)
				if (row.admissionOrdinal > target.lastCompletedOrdinal + 1L) {
					recordGapAndAdvance(target, row.admissionOrdinal - 1L, lease)
					target = requireTarget(target)
				}
				processEventFrameRow(target, row, lease)
			}
			target = requireTarget(target)
		}

		target = requireTarget(target)
		recoverEventFrameOutbox(target, lease, batchSize, hasReleasedWriterActivation)
		target = requireTarget(target)
		withOwnedTransaction(lease) { nowElapsed ->
			val current = requireTarget(target)
			check(current.lastCompletedOrdinal == current.requiredThroughOrdinal) {
				"Legacy event-frame target was finalized before its immutable cutoff"
			}
			val disposition = when {
				current.failureCode != null ->
					LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL
				hasReleasedWriterActivation ->
					LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS
				else -> LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT
			}
			terminalizeOutbox(current, disposition, lease, nowElapsed)
			cleanupLegacyProjectionState(
				projectionId = current.projectionId,
				lease = lease,
				nowElapsed = nowElapsed,
				deleteFailures = false,
			)
			requireOwnedMutation(
				database.legacyV27ProjectionDrainDao().terminalizeTarget(
					projectionId = current.projectionId,
					projectionVersion = current.projectionVersion,
					expectedCompletedOrdinal = current.requiredThroughOrdinal,
					disposition = disposition,
					completedAtMs = clock.currentTimeMillis(),
					failureCode = current.failureCode,
					bootId = lease.bootId,
					ownerToken = lease.ownerToken,
					leaseGeneration = lease.generation,
					nowElapsedNanos = nowElapsed,
				),
			)
		}
	}

	/**
	 * Recovers the v27 crash window where projection output committed and raw WAL was pruned before
	 * the process-local typed destination acknowledged the effect. The frozen effect has the metric
	 * fact but not the original receipt clock, so outbox-only recovery is durably marked partial.
	 */
	private suspend fun recoverEventFrameOutbox(
		target: LegacyV27ProjectionTargetEntity,
		lease: OwnedLease,
		batchSize: Int,
		hasReleasedWriterActivation: Boolean,
	) {
		val dao = database.legacyV27ProjectionDrainDao()
		while (true) {
			assertLifecycleCurrent(lease)
			val pending = dao.pendingOutbox(
				projectionId = target.projectionId,
				projectionVersion = target.projectionVersion,
				limit = batchSize,
			)
			if (pending.isEmpty()) return
			for (outbox in pending) {
				val lifecycle = assertLifecycleCurrent(lease)
				var materializedRetention: FactRetentionIdentity? = null
				withOwnedTransaction(lease) { nowElapsed ->
					val current = requireTarget(target)
					val currentOutbox = dao.pendingOutbox(
						projectionId = current.projectionId,
						projectionVersion = current.projectionVersion,
						limit = batchSize,
					).firstOrNull { it.stableId == outbox.stableId }
						?: return@withOwnedTransaction
					if (currentOutbox.admissionOrdinal <= 0L ||
						(hasReleasedWriterActivation &&
							currentOutbox.admissionOrdinal < current.initialActivationOrdinal)
					) {
						recordOutboxFailure(
							current,
							currentOutbox.admissionOrdinal,
							FAILURE_OUTBOX_OUTSIDE_ACTIVATION,
							lease,
							nowElapsed,
						)
						terminalizeExactOutbox(
							currentOutbox.stableId,
							LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
							lease,
							nowElapsed,
						)
						return@withOwnedTransaction
					}
					val rawEventId = currentOutbox.stableId
						.takeIf { it.startsWith(EVENT_TRACKING_FRAME_STABLE_PREFIX) }
						?.removePrefix(EVENT_TRACKING_FRAME_STABLE_PREFIX)
						?.takeIf(String::isNotBlank)
					val delivery = try {
						if (currentOutbox.effectKind != EVENT_TRACKING_FRAME_OUTBOX_KIND ||
							currentOutbox.payloadVersion != RELEASED_OUTBOX_PAYLOAD_VERSION
						) null else EventTrackingFrameEffectCodec.decode(
							currentOutbox.payload,
							currentOutbox.payloadVersion,
						)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (_: Exception) {
						null
					}
					if (rawEventId == null || delivery == null ||
						delivery.cycle.persistenceSignalId != "source-event:$rawEventId"
					) {
						recordOutboxFailure(
							current,
							currentOutbox.admissionOrdinal,
							FAILURE_OUTBOX_DECODE,
							lease,
							nowElapsed,
						)
						terminalizeExactOutbox(
							currentOutbox.stableId,
							LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
							lease,
							nowElapsed,
						)
						return@withOwnedTransaction
					}

					val raw = database.sourceEventWalDao().getByEventId(rawEventId)
					if (raw != null && (
							currentOutbox.admissionOrdinal != raw.admissionOrdinal ||
								delivery.logicalTrackingId != raw.logicalTrackingId
						)
					) {
						recordOutboxFailure(
							current,
							currentOutbox.admissionOrdinal,
							FAILURE_OUTBOX_IDENTITY_MISMATCH,
							lease,
							nowElapsed,
						)
						terminalizeExactOutbox(
							currentOutbox.stableId,
							LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
							lease,
							nowElapsed,
						)
						return@withOwnedTransaction
					}

					val retention = FactRetentionIdentity(
						capturedEpoch = raw?.capturedCollectedDataEpoch ?: lease.collectedDataEpoch,
						acquiredAtMs = raw?.acquiredAtMs ?: currentOutbox.createdAtMs,
						destinationTimeMs = delivery.cycle.timestampMs,
					)
					val roomLifecycle = requireNotNull(database.sourceEvidenceStateDao().get())
					val retainedFromMs = listOfNotNull(
						lifecycle.retainedFromMs,
						roomLifecycle.retainedFromMs,
					).maxOrNull()
					if (!lifecycle.accepts(retention) ||
						roomLifecycle.collectedDataEpoch != retention.capturedEpoch ||
						(retainedFromMs != null && (
								retention.acquiredAtMs < retainedFromMs ||
									retention.destinationTimeMs < retainedFromMs
							))
					) {
						recordOutboxFailure(
							current,
							currentOutbox.admissionOrdinal,
							FAILURE_RETENTION_REJECTED,
							lease,
							nowElapsed,
						)
						terminalizeExactOutbox(
							currentOutbox.stableId,
							LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
							lease,
							nowElapsed,
						)
						return@withOwnedTransaction
					}

					val rawFailure = if (raw == null) null else {
						database.sourceProjectionStateDao().failure(
							current.projectionId,
							current.projectionVersion,
							currentOutbox.admissionOrdinal,
						)
					}
					val rawSemanticMatches = if (raw == null || rawFailure != null) {
						null
					} else {
						try {
							check(raw.payloadVersion == RELEASED_PAYLOAD_VERSION)
							val rawPayload = payloadCodec.decode(
								SourceKind.entries.single { it.stableCode == raw.sourceKind },
								raw.payloadVersion,
								raw.payload,
							)
							releasedV1EventTrackingFrame(
								eventId = raw.eventId,
								timestampMs = raw.wallTimeMs ?: raw.acquiredAtMs,
								payload = rawPayload,
							) == delivery.cycle
						} catch (cancelled: CancellationException) {
							throw cancelled
						} catch (_: Exception) {
							false
						}
					}
					if (rawSemanticMatches == false) {
						recordOutboxFailure(
							current,
							currentOutbox.admissionOrdinal,
							FAILURE_OUTBOX_SEMANTIC_MISMATCH,
							lease,
							nowElapsed,
						)
						terminalizeExactOutbox(
							currentOutbox.stableId,
							LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
							lease,
							nowElapsed,
						)
						return@withOwnedTransaction
					}
					if (hasReleasedWriterActivation && raw != null && rawFailure == null) {
						// The WAL destination and cursor committed atomically earlier in this drain.
						// Replaying the less-informative outbox would only discard receipt provenance.
						terminalizeExactOutbox(
							currentOutbox.stableId,
							LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS,
							lease,
							nowElapsed,
						)
						return@withOwnedTransaction
					}

					val bridgeResult = eventFrameBridge.bridge(currentOutbox)
					if (bridgeResult is LegacyV27EventFrameBridgeResult.Applied) {
						materializedRetention = retention
					}
					recordOutboxFailure(
						current,
						currentOutbox.admissionOrdinal,
						if (bridgeResult is LegacyV27EventFrameBridgeResult.IdentityCollision) {
							FAILURE_DESTINATION_IDENTITY_COLLISION
						} else {
							FAILURE_OUTBOX_ONLY_PARTIAL
						},
						lease,
						nowElapsed,
					)
					terminalizeExactOutbox(
						stableId = currentOutbox.stableId,
						disposition = LegacyV27ProjectionTargetEntity
							.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
						lease = lease,
						nowElapsed = nowElapsed,
					)
				}
				val retention = materializedRetention
				if (retention != null) {
					val currentLifecycle = lifecycleStore.snapshot()
					if (currentLifecycle.epoch != lease.collectedDataEpoch ||
						(lifecycle.accepts(retention) && !currentLifecycle.accepts(retention))
					) {
						throw LifecycleSupersededException
					}
				}
			}
		}
	}

	private suspend fun recordOutboxFailure(
		target: LegacyV27ProjectionTargetEntity,
		admissionOrdinal: Long,
		failureCode: String,
		lease: OwnedLease,
		nowElapsed: Long,
	) {
		requireOwnedMutation(
			database.legacyV27ProjectionDrainDao().markTargetPartial(
				projectionId = target.projectionId,
				projectionVersion = target.projectionVersion,
				failureCode = failureCode,
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = nowElapsed,
			),
		)
		val state = database.sourceProjectionStateDao()
		val existing = state.failure(
			target.projectionId,
			target.projectionVersion,
			admissionOrdinal,
		)
		state.saveFailure(
			SourceProjectionFailureEntity(
				projectionId = target.projectionId,
				projectionVersion = target.projectionVersion,
				admissionOrdinal = admissionOrdinal,
				attemptCount = (existing?.attemptCount ?: 0) + 1,
				failureCode = failureCode,
				terminal = true,
				lastAttemptAtMs = clock.currentTimeMillis(),
			),
		)
	}

	private suspend fun terminalizeExactOutbox(
		stableId: String,
		disposition: String,
		lease: OwnedLease,
		nowElapsed: Long,
	) {
		requireOwnedMutation(
			database.legacyV27ProjectionDrainDao().terminalizePendingOutbox(
				stableId = stableId,
				disposition = disposition,
				terminalAtMs = clock.currentTimeMillis(),
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = nowElapsed,
			),
		)
	}

	private suspend fun processEventFrameRow(
		target: LegacyV27ProjectionTargetEntity,
		row: SourceEventWalEntity,
		lease: OwnedLease,
	) {
		val externalLifecycle = assertLifecycleCurrent(lease)
		withOwnedTransaction(lease) { nowElapsed ->
			val current = requireTarget(target)
			check(row.admissionOrdinal == current.lastCompletedOrdinal + 1L) {
				"Legacy event-frame cursor is not immediately before its WAL row"
			}
			val roomLifecycle = requireNotNull(database.sourceEvidenceStateDao().get())
			val retainedFromMs = listOfNotNull(
				externalLifecycle.retainedFromMs,
				roomLifecycle.retainedFromMs,
			).maxOrNull()
			val retention = FactRetentionIdentity(
				capturedEpoch = row.capturedCollectedDataEpoch,
				acquiredAtMs = row.acquiredAtMs,
				destinationTimeMs = row.wallTimeMs ?: row.acquiredAtMs,
			)
			if (!externalLifecycle.accepts(retention) ||
				roomLifecycle.collectedDataEpoch != row.capturedCollectedDataEpoch ||
				(retainedFromMs != null && (
						retention.acquiredAtMs < retainedFromMs ||
							retention.destinationTimeMs < retainedFromMs
					))
			) {
				recordTerminalRowFailure(
					target = current,
					admissionOrdinal = row.admissionOrdinal,
					failureCode = FAILURE_RETENTION_REJECTED,
					lease = lease,
					nowElapsed = nowElapsed,
				)
				return@withOwnedTransaction
			}

			val integrityValid = when {
				row.hasVerifiedLegacyPayload() -> true
				row.integrityIdentity == SourceEventWalEntity.LEGACY_PENDING_CHECKSUM -> {
					val classification = if (row.hasPendingLegacyPayload()) {
						SourceEventWalEntity.LEGACY_CHECKSUM_VERIFIED
					} else {
						SourceEventWalEntity.LEGACY_CHECKSUM_MISMATCH
					}
				requireOwnedMutation(
						database.sourceEventWalDao().classifyPendingLegacyPayload(
							row.admissionOrdinal,
							classification,
						),
					)
					classification == SourceEventWalEntity.LEGACY_CHECKSUM_VERIFIED
				}
				else -> false
			}
			if (!integrityValid) {
				recordTerminalRowFailure(
					target = current,
					admissionOrdinal = row.admissionOrdinal,
					failureCode = FAILURE_PAYLOAD_INTEGRITY,
					lease = lease,
					nowElapsed = nowElapsed,
				)
				return@withOwnedTransaction
			}

			val payload = try {
				check(row.payloadVersion == RELEASED_PAYLOAD_VERSION)
				val source = SourceKind.entries.single { it.stableCode == row.sourceKind }
				payloadCodec.decode(source, RELEASED_PAYLOAD_VERSION, row.payload)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				recordTerminalRowFailure(
					target = current,
					admissionOrdinal = row.admissionOrdinal,
					failureCode = FAILURE_PAYLOAD_DECODE,
					lease = lease,
					nowElapsed = nowElapsed,
				)
				return@withOwnedTransaction
			}

			val result = if (row.logicalTrackingId == null) {
				LegacyV27EventFrameBridgeResult.NoFact
			} else {
				eventFrameBridge.bridge(row, payload)
			}
			if (result is LegacyV27EventFrameBridgeResult.IdentityCollision) {
				recordTerminalRowFailure(
					target = current,
					admissionOrdinal = row.admissionOrdinal,
					failureCode = FAILURE_DESTINATION_IDENTITY_COLLISION,
					lease = lease,
					nowElapsed = nowElapsed,
				)
			} else {
				advanceTargetInTransaction(current, row.admissionOrdinal, lease, nowElapsed)
			}
		}
		val currentLifecycle = lifecycleStore.snapshot()
		val retention = FactRetentionIdentity(
			capturedEpoch = row.capturedCollectedDataEpoch,
			acquiredAtMs = row.acquiredAtMs,
			destinationTimeMs = row.wallTimeMs ?: row.acquiredAtMs,
		)
		if (currentLifecycle.epoch != lease.collectedDataEpoch ||
			(externalLifecycle.accepts(retention) && !currentLifecycle.accepts(retention))
		) {
			throw LifecycleSupersededException
		}
	}

	private suspend fun recordTerminalRowFailure(
		target: LegacyV27ProjectionTargetEntity,
		admissionOrdinal: Long,
		failureCode: String,
		lease: OwnedLease,
		nowElapsed: Long,
	) {
		requireOwnedMutation(
			database.legacyV27ProjectionDrainDao().markTargetPartial(
				projectionId = target.projectionId,
				projectionVersion = target.projectionVersion,
				failureCode = failureCode,
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = nowElapsed,
			),
		)
		val existing = database.sourceProjectionStateDao().failure(
			target.projectionId,
			target.projectionVersion,
			admissionOrdinal,
		)
		database.sourceProjectionStateDao().saveFailure(
			SourceProjectionFailureEntity(
				projectionId = target.projectionId,
				projectionVersion = target.projectionVersion,
				admissionOrdinal = admissionOrdinal,
				attemptCount = (existing?.attemptCount ?: 0) + 1,
				failureCode = failureCode,
				terminal = true,
				lastAttemptAtMs = clock.currentTimeMillis(),
			),
		)
		advanceTargetInTransaction(target, admissionOrdinal, lease, nowElapsed)
	}

	private suspend fun recordGapAndAdvance(
		target: LegacyV27ProjectionTargetEntity,
		newCompletedOrdinal: Long,
		lease: OwnedLease,
	) {
		check(newCompletedOrdinal > target.lastCompletedOrdinal)
		// AUTOINCREMENT ordinals are high-water identities, not a dense log contract. INSERT
		// IGNORE and previously pruned prefixes can leave legitimate holes, so absence alone is
		// neither poison nor evidence loss. A pending outbox without raw WAL is recovered separately.
		advanceTarget(target, newCompletedOrdinal, lease)
	}

	private suspend fun markTargetPartial(
		target: LegacyV27ProjectionTargetEntity,
		failureCode: String,
		lease: OwnedLease,
	) {
		withOwnedTransaction(lease) { nowElapsed ->
			requireOwnedMutation(
				database.legacyV27ProjectionDrainDao().markTargetPartial(
					projectionId = target.projectionId,
					projectionVersion = target.projectionVersion,
					failureCode = failureCode,
					bootId = lease.bootId,
					ownerToken = lease.ownerToken,
					leaseGeneration = lease.generation,
					nowElapsedNanos = nowElapsed,
				),
			)
		}
	}

	private suspend fun advanceTarget(
		target: LegacyV27ProjectionTargetEntity,
		newCompletedOrdinal: Long,
		lease: OwnedLease,
	) {
		withOwnedTransaction(lease) { nowElapsed ->
			advanceTargetInTransaction(
				requireTarget(target),
				newCompletedOrdinal,
				lease,
				nowElapsed,
			)
		}
	}

	private suspend fun advanceTargetInTransaction(
		target: LegacyV27ProjectionTargetEntity,
		newCompletedOrdinal: Long,
		lease: OwnedLease,
		nowElapsed: Long,
	) {
		requireOwnedMutation(
			database.legacyV27ProjectionDrainDao().advanceTarget(
				projectionId = target.projectionId,
				projectionVersion = target.projectionVersion,
				expectedCompletedOrdinal = target.lastCompletedOrdinal,
				newCompletedOrdinal = newCompletedOrdinal,
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = nowElapsed,
			),
		)
	}

	private suspend fun terminalizeStrandedOutbox(
		target: LegacyV27ProjectionTargetEntity,
		lease: OwnedLease,
	) {
		if (database.legacyV27ProjectionDrainDao().pendingOutboxCount(
				target.projectionId,
				target.projectionVersion,
			) == 0L
		) return
		withOwnedTransaction(lease) { nowElapsed ->
			terminalizeOutbox(target, target.disposition, lease, nowElapsed)
		}
	}

	private suspend fun terminalizeOutbox(
		target: LegacyV27ProjectionTargetEntity,
		disposition: String,
		lease: OwnedLease,
		nowElapsed: Long,
	) {
		database.legacyV27ProjectionDrainDao().terminalizePendingOutbox(
			projectionId = target.projectionId,
			projectionVersion = target.projectionVersion,
			disposition = disposition,
			terminalAtMs = clock.currentTimeMillis(),
			bootId = lease.bootId,
			ownerToken = lease.ownerToken,
			leaseGeneration = lease.generation,
			nowElapsedNanos = nowElapsed,
		)
		check(database.legacyV27ProjectionDrainDao().pendingOutboxCount(
			target.projectionId,
			target.projectionVersion,
		) == 0L) { "Released-v27 outbox remained pending after terminalization" }
	}

	private suspend fun cleanupLegacyProjectionState(
		projectionId: String,
		lease: OwnedLease,
		nowElapsed: Long,
		deleteFailures: Boolean,
	) {
		val dao = database.legacyV27ProjectionDrainDao()
		dao.deleteLegacyV1JoinState(
			projectionId,
			lease.bootId,
			lease.ownerToken,
			lease.generation,
			nowElapsed,
		)
		if (deleteFailures) {
			dao.deleteLegacyV1Failures(
				projectionId,
				lease.bootId,
				lease.ownerToken,
				lease.generation,
				nowElapsed,
			)
		}
		dao.deleteLegacyV1Checkpoint(
			projectionId,
			lease.bootId,
			lease.ownerToken,
			lease.generation,
			nowElapsed,
		)
		dao.retireLegacyV1Registration(
			projectionId,
			lease.bootId,
			lease.ownerToken,
			lease.generation,
			nowElapsed,
		)
	}

	private suspend fun completeDrain(
		lease: OwnedLease,
	): LegacyV27ProjectionRecoveryResult {
		assertLifecycleCurrent(lease)
		val result = database.withTransaction {
			val nowElapsed = renewAndValidateLease(lease)
			val dao = database.legacyV27ProjectionDrainDao()
			if (dao.unknownPendingOutboxGenerations().isNotEmpty()) {
				throw UnsupportedLegacyContractException(FAILURE_UNKNOWN_OUTBOX_GENERATION)
			}
			val targets = dao.targets()
			check(targets.none {
				it.disposition == LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING
			}) { "Released-v27 drain still has a pending target" }
			val suppressionDispositions = listOf(
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_STALE_CONTROL,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_RETAINED,
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL,
			)
			val suppressedCount = dao.terminalizedOutboxCount(suppressionDispositions)
			requireOwnedMutation(
				dao.setSuppressedOutboxCount(
					suppressedCount = suppressedCount,
					bootId = lease.bootId,
					ownerToken = lease.ownerToken,
					leaseGeneration = lease.generation,
					nowElapsedNanos = nowElapsed,
				),
			)
			val partial = targets.any { target ->
				target.disposition in setOf(
					LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
					LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL,
				) || target.failureCode != null
			}
			val status = if (partial) {
				LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL
			} else {
				LegacyV27ProjectionDrainEntity.STATUS_COMPLETE
			}
			requireOwnedMutation(
				dao.completeDrain(
					status = status,
					completedAtMs = clock.currentTimeMillis(),
					failureCode = FAILURE_PARTIAL_RECOVERY.takeIf { partial },
					bootId = lease.bootId,
					ownerToken = lease.ownerToken,
					leaseGeneration = lease.generation,
					nowElapsedNanos = nowElapsed,
				),
			)
			LegacyV27ProjectionRecoveryResult.Complete(
				partial = partial,
				suppressedOutboxCount = suppressedCount,
			)
		}
		assertLifecycleCurrent(lease)
		return result
	}

	private suspend fun <T> withOwnedTransaction(
		lease: OwnedLease,
		block: suspend (nowElapsed: Long) -> T,
	): T {
		assertLifecycleCurrent(lease)
		return database.withTransaction {
			block(renewAndValidateLease(lease))
		}
	}

	private suspend fun renewAndValidateLease(lease: OwnedLease): Long {
		val nowElapsed = clock.elapsedRealtimeNanos()
		val nextExpiry = maxOf(
			nowElapsed + LEASE_DURATION_NANOS,
			lease.expiresElapsedRealtimeNanos + 1L,
		)
		if (database.legacyV27ProjectionDrainDao().renewLease(
				bootId = lease.bootId,
				ownerToken = lease.ownerToken,
				leaseGeneration = lease.generation,
				nowElapsedNanos = nowElapsed,
				leaseExpiresElapsedNanos = nextExpiry,
			) != 1
		) {
			throw LifecycleSupersededException
		}
		lease.expiresElapsedRealtimeNanos = nextExpiry
		val roomLifecycle = requireNotNull(database.sourceEvidenceStateDao().get())
		if (roomLifecycle.collectedDataEpoch != lease.collectedDataEpoch) {
			throw LifecycleSupersededException
		}
		return nowElapsed
	}

	private suspend fun assertLifecycleCurrent(
		lease: OwnedLease,
	): CollectedDataLifecycleSnapshot = lifecycleStore.snapshot().also { lifecycle ->
		if (lifecycle.epoch != lease.collectedDataEpoch) throw LifecycleSupersededException
	}

	private suspend fun requireTarget(
		target: LegacyV27ProjectionTargetEntity,
	): LegacyV27ProjectionTargetEntity = requireNotNull(
		database.legacyV27ProjectionDrainDao().target(target.projectionId, target.projectionVersion),
	) { "Released-v27 target disappeared while its drain was owned" }

	private fun requireOwnedMutation(updatedRows: Int) {
		if (updatedRows != 1) throw LifecycleSupersededException
	}

	private fun LegacyV27ProjectionDrainEntity.terminalResultOrNull():
		LegacyV27ProjectionRecoveryResult? = when (status) {
		LegacyV27ProjectionDrainEntity.STATUS_NOT_REQUIRED ->
			LegacyV27ProjectionRecoveryResult.NotRequired
		LegacyV27ProjectionDrainEntity.STATUS_COMPLETE ->
			LegacyV27ProjectionRecoveryResult.Complete(false, suppressedOutboxCount)
		LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL ->
			LegacyV27ProjectionRecoveryResult.Complete(true, suppressedOutboxCount)
		LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET ->
			LegacyV27ProjectionRecoveryResult.Blocked(failureCode ?: FAILURE_UNSUPPORTED_TARGET)
		else -> null
	}

	private fun Exception.retryableFailureCode(): String = when (this) {
		is IllegalStateException -> FAILURE_RECOVERY_INVARIANT
		else -> FAILURE_STORAGE_RETRYABLE
	}

	private data class OwnedLease(
		val bootId: String,
		val ownerToken: String,
		val generation: Long,
		var expiresElapsedRealtimeNanos: Long,
		val collectedDataEpoch: Long,
	)

	private data class FactRetentionIdentity(
		val capturedEpoch: Long,
		val acquiredAtMs: Long,
		val destinationTimeMs: Long,
	)

	private fun CollectedDataLifecycleSnapshot.accepts(
		retention: FactRetentionIdentity,
	): Boolean = accepts(retention.capturedEpoch, retention.acquiredAtMs) &&
		(retainedFromMs?.let { retainedFloor ->
			retention.destinationTimeMs >= retainedFloor
		} ?: true)

	private class UnsupportedLegacyContractException(
		val failureCode: String,
	) : IllegalStateException(failureCode)

	private object LifecycleSupersededException : IllegalStateException()

	private companion object {
		const val RELEASED_PROJECTION_VERSION = 1
		const val RELEASED_PAYLOAD_VERSION = 1
		const val RELEASED_OUTBOX_PAYLOAD_VERSION = 1
		const val ACTIVITY_AUTOMATION = "activity-automation"
		const val EVENT_TRACKING_FRAME = "event-tracking-frame"
		const val EVENT_TRACKING_FRAME_STABLE_PREFIX = "$EVENT_TRACKING_FRAME:"
		const val EVENT_TRACKING_FRAME_OUTBOX_KIND = "event-tracking-frame-v1"
		const val EXPLICIT_TRACKING_JOINS = "explicit-tracking-joins"
		const val LOCATION_DOMAIN = "location-domain"
		const val REGISTRATION_ACTIVE = "ACTIVE"
		const val REGISTRATION_NOT_REGISTERED = "NOT_REGISTERED_AT_MIGRATION"
		val SUPPORTED_INITIAL_REGISTRATION_STATUSES = setOf(
			REGISTRATION_ACTIVE,
			REGISTRATION_NOT_REGISTERED,
		)
		val SUPPORTED_PROJECTIONS = setOf(
			ACTIVITY_AUTOMATION,
			EVENT_TRACKING_FRAME,
			EXPLICIT_TRACKING_JOINS,
			LOCATION_DOMAIN,
		)

		const val DEFAULT_BATCH_SIZE = 128
		const val LEASE_DURATION_MS = 30_000L
		const val LEASE_DURATION_NANOS = LEASE_DURATION_MS * 1_000_000L

		const val FAILURE_UNSUPPORTED_CONTRACT = "UNSUPPORTED_V27_DRAIN_CONTRACT"
		const val FAILURE_UNSUPPORTED_TARGET = "UNSUPPORTED_LEGACY_PROJECTION"
		const val FAILURE_INCOMPLETE_TARGET_SET = "INCOMPLETE_V27_TARGET_SET"
		const val FAILURE_UNKNOWN_OUTBOX_GENERATION = "UNKNOWN_V27_OUTBOX_GENERATION"
		const val FAILURE_PAYLOAD_INTEGRITY = "LEGACY_V27_PAYLOAD_INTEGRITY"
		const val FAILURE_PAYLOAD_DECODE = "LEGACY_V27_PAYLOAD_DECODE"
		const val FAILURE_OUTBOX_DECODE = "LEGACY_V27_OUTBOX_DECODE"
		const val FAILURE_OUTBOX_IDENTITY_MISMATCH = "LEGACY_V27_OUTBOX_IDENTITY_MISMATCH"
		const val FAILURE_OUTBOX_OUTSIDE_ACTIVATION = "LEGACY_V27_OUTBOX_OUTSIDE_ACTIVATION"
		const val FAILURE_OUTBOX_SEMANTIC_MISMATCH = "LEGACY_V27_OUTBOX_SEMANTIC_MISMATCH"
		const val FAILURE_OUTBOX_ONLY_PARTIAL = "LEGACY_V27_OUTBOX_ONLY_PARTIAL"
		const val FAILURE_RETENTION_REJECTED = "LEGACY_V27_RETENTION_REJECTED"
		const val FAILURE_DESTINATION_IDENTITY_COLLISION = "BRIDGE_IDENTITY_COLLISION"
		const val FAILURE_LOCATION_SHADOW_PARTIAL = "LOCATION_SHADOW_PARTIAL"
		const val FAILURE_PARTIAL_RECOVERY = "LEGACY_V27_RECOVERY_PARTIAL"
		const val FAILURE_RECOVERY_INVARIANT = "LEGACY_V27_RECOVERY_INVARIANT"
		const val FAILURE_STORAGE_RETRYABLE = "LEGACY_V27_STORAGE_RETRYABLE"
	}
}

sealed interface LegacyV27ProjectionRecoveryResult {
	data object NotRequired : LegacyV27ProjectionRecoveryResult

	data class Complete(
		val partial: Boolean,
		val suppressedOutboxCount: Long,
	) : LegacyV27ProjectionRecoveryResult

	data object LeaseUnavailable : LegacyV27ProjectionRecoveryResult

	/** A full-deletion epoch changed; the deletion owner, not recovery, now owns convergence. */
	data object LifecycleSuperseded : LegacyV27ProjectionRecoveryResult

	data class Blocked(val failureCode: String) : LegacyV27ProjectionRecoveryResult

	data class FailedRetryable(val failureCode: String) : LegacyV27ProjectionRecoveryResult
}
