package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.source.location.LocationAmbientQualification
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.location.readLocationAmbientReceipt
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Atomic setup for the existing permanent Location destination, never a second writer owner. */
@Singleton
internal class ProtectedLocationWriterSetupCoordinator @Inject constructor(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val boundary: SourceWriterTransitionBoundary,
	private val persistenceLease: ExclusiveTrackingPersistenceLifecycleLease,
	private val persistence: Provider<PersistenceProcessor>,
	private val qualifier: Provider<LocationWalQualificationAdapter>,
	private val recovery: Provider<SourcePipelineRecovery>,
) {
	private val source = SourceKind.LOCATION
	private val finalBinding = ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V2
	private val originalBinding = ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V1
	private val phase = SourceWriterTransitionPhase.ACTIVATE_CANDIDATE

	suspend fun ensureFinalWriter(updatedAtMs: Long): SourceWriterTransitionResult {
		require(updatedAtMs >= 0L)
		var needsDrain = false
		val result = boundary.run(source, phase) { lease ->
			val ready = database.withTransaction {
				boundary.requireLease(lease)
				val current = currentRollout()
				val active = activeLane()
				if (isFinalReady(active, current)) SourceWriterTransitionResult.AlreadyApplied(
					source, phase, current.revision, 1L, null) else null
			}
			if (ready != null) return@run ready
			persistenceLease.tryWithOfflineLocationRecovery {
				persistence.get().withLocationWriterSetupQuiesced {
					database.withTransaction {
						boundary.requireLease(lease)
						boundary.requireLocationSetupIdle()
						requirePermanentOwner()
						if (!catalog.owns(finalBinding) || database.sourceProjectionStateDao().registration(
							finalBinding.projectionId, finalBinding.projectionVersion) != null) {
							boundary.refuse(SourceWriterTransitionBlocker.GLOBAL_WRITER_COLLISION)
						}
						var current = currentRollout()
						var active = activeLane()
						var recoveryAfterOrdinal: Long
						if (active != null) {
							if (catalog.bindingFor(active) != originalBinding ||
								active.terminalDisposition != null || active.terminalAtMs != null) {
								boundary.refuse(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
							}
							if (active.captureAdmissionCutoffOrdinal == null) {
								val contained = RoomTrackingRolloutStateStore(database, catalog).beginLaneContainment(
									originalBinding, nextRevision(current.revision), updatedAtMs)
								current = contained.rollout; active = contained.lane
							}
							val cutoff = requireNotNull(active.captureAdmissionCutoffOrdinal)
							if (active.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW) {
								requireNoCaptureInShadow(active.activationOrdinal - 1L, cutoff)
								recoveryAfterOrdinal = active.activationOrdinal - 1L
								if (active.contiguousAdmissionOrdinal < cutoff) {
									check(database.sourceProjectionStateDao().advanceProductLaneCursor(source.stableCode,
										originalBinding.bindingGeneration, originalBinding.projectionId, originalBinding.projectionVersion,
										active.contiguousAdmissionOrdinal, cutoff, updatedAtMs) == 1)
								}
							} else if (active.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
								if (active.contiguousAdmissionOrdinal != cutoff) {
									needsDrain = true
									return@withTransaction SourceWriterTransitionResult.Blocked(source, phase,
										SourceWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND)
								}
								recoveryAfterOrdinal = cutoff
							} else boundary.refuse(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
							current = RoomTrackingRolloutStateStore(database, catalog)
								.finishLaneRetirement(originalBinding, cutoff, updatedAtMs)
						} else {
							val latest = database.sourceProjectionStateDao().latestProductLane(source.stableCode)
							if (latest == null) {
								requireFreshOrCompletedClear()
								recoveryAfterOrdinal = database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L
							} else {
								val cutoff = latest.captureAdmissionCutoffOrdinal
								if (catalog.bindingFor(latest) != originalBinding || latest.status != SourceProductProjectionLaneEntity.STATUS_RETIRED ||
									latest.terminalDisposition != SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN ||
									latest.terminalAtMs == null || cutoff == null || latest.contiguousAdmissionOrdinal != cutoff) {
									boundary.refuse(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
								}
								recoveryAfterOrdinal = if (latest.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW) {
									requireNoCaptureInShadow(latest.activationOrdinal - 1L, cutoff)
									latest.activationOrdinal - 1L
								} else if (latest.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) cutoff
								else boundary.refuse(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
							}
						}
						if (current.sourceOwners.getValue(source) != SourceOwner.CONTAINED ||
							current.productProjectionStages.getValue(source) != ProductProjectionStage.LEGACY_CANONICAL ||
							current.captureModeMasks.getValue(source) != 0L) boundary.refuse(SourceWriterTransitionBlocker.ROLLOUT_NOT_CONTAINED)
						val highWater = database.liveSourceProjectionActivationOrdinal() - 1L
						val pendingAmbient = firstPendingAmbientAfter(recoveryAfterOrdinal, highWater)
						boundary.requireLease(lease)
						val activation = pendingAmbient ?: Math.addExact(highWater, 1L)
						val revision = nextRevision(current.revision)
						val updated = current.copy(revision = revision,
							sourceOwners = current.sourceOwners + (source to SourceOwner.EVENT),
							productProjectionStages = current.productProjectionStages + (source to ProductProjectionStage.EVENT_CANONICAL),
							captureModeMasks = current.captureModeMasks + (source to finalBinding.captureModeMask))
						val lane = SourceProductProjectionLaneEntity(sourceKind = source.stableCode,
							bindingGeneration = finalBinding.bindingGeneration, projectionId = finalBinding.projectionId,
							projectionVersion = finalBinding.projectionVersion, captureModeMask = finalBinding.captureModeMask,
							productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
							activatedRolloutRevision = revision, activationOrdinal = activation,
							contiguousAdmissionOrdinal = activation - 1L, retentionRequired = true,
							status = SourceProductProjectionLaneEntity.STATUS_ACTIVE, installedAtMs = updatedAtMs, updatedAtMs = updatedAtMs)
						database.sourceProjectionStateDao().installProductLane(lane)
						database.trackingRolloutStateDao().save(updated.toEntity(updatedAtMs))
						if (!isFinalReady(lane, updated)) boundary.refuse(SourceWriterTransitionBlocker.POSTCONDITION_FAILED)
						needsDrain = pendingAmbient != null
						SourceWriterTransitionResult.Applied(source, phase, revision, 1L, null)
					}
				} ?: SourceWriterTransitionResult.Blocked(source, phase, SourceWriterTransitionBlocker.LEGACY_WRITER_NOT_QUIESCENT)
			} ?: SourceWriterTransitionResult.Busy(source, phase)
		}
		// Release the offline and coordinator permits before the existing recovery writer acquires them.
		if (needsDrain) recovery.get().requestProtectedLocationCanonicalDrain()
		return result
	}

	private suspend fun currentRollout(): TrackingRolloutState = database.trackingRolloutStateDao().get()
		?.decodeCurrentModelOrNull() ?: boundary.refuse(SourceWriterTransitionBlocker.ROLLOUT_MISSING_OR_UNREADABLE)

	private suspend fun activeLane(): SourceProductProjectionLaneEntity? {
		val active = database.sourceProjectionStateDao().allActiveProductLanes().filter { it.sourceKind == source.stableCode }
		if (active.size > 1) boundary.refuse(SourceWriterTransitionBlocker.MULTIPLE_ACTIVE_LANES)
		return active.singleOrNull()
	}

	private suspend fun requirePermanentOwner() {
		if (!database.sourceDestinationOwnerDao().isExactOwner(source.stableCode,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
			SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION)) {
			boundary.refuse(SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
	}

	private suspend fun isFinalReady(lane: SourceProductProjectionLaneEntity?, rollout: TrackingRolloutState): Boolean {
		val current = lane ?: return false
		return current.matches(finalBinding, ProductProjectionStage.EVENT_CANONICAL, rollout.revision) &&
			current.captureAdmissionCutoffOrdinal == null && current.terminalDisposition == null && current.terminalAtMs == null &&
			current.isCanonicalCaptureAuthorizedBy(database, rollout, catalog) &&
			database.sourceProjectionStateDao().registration(finalBinding.projectionId, finalBinding.projectionVersion) == null
	}

	private suspend fun requireFreshOrCompletedClear() {
		val evidence = database.sourceEvidenceStateDao().get() ?: boundary.refuse(SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE)
		val previousNative = database.query(SimpleSQLiteQuery("""
			SELECT EXISTS(SELECT 1 FROM location_observation_decision WHERE writer_projection_id IS NOT NULL)
			OR EXISTS(SELECT 1 FROM location_sample_purpose_authority WHERE purpose = 'SESSION_CAPTURE')
		""".trimIndent())).use { it.moveToFirst() && it.getInt(0) != 0 }
		if (previousNative) boundary.refuse(SourceWriterTransitionBlocker.ACTIVE_LANE_MISSING)
		if (evidence.collectedDataEpoch == 0L) return
		val clear = database.collectedDataDeletionOperationDao().completedFullDeletionAfter(evidence.collectedDataEpoch - 1L)
			?: boundary.refuse(SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE)
		if (clear.targetCollectedDataEpoch != evidence.collectedDataEpoch ||
			clear.phase != CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED ||
			clear.retentionWorkExecutionId != null || clear.retentionDestructivePlan != null ||
			clear.retainedFromMs?.let { evidence.retainedFromMs == null || evidence.retainedFromMs < it } == true) {
			boundary.refuse(SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE)
		}
	}

	private suspend fun requireNoCaptureInShadow(after: Long, through: Long) {
		val products = database.query(SimpleSQLiteQuery("""
			SELECT EXISTS(SELECT 1 FROM location_observation_decision d JOIN source_event_wal w
			ON w.event_id = d.observation_source_event_id WHERE w.source_kind = 1
			AND w.admission_ordinal > ? AND w.admission_ordinal <= ? AND d.writer_projection_id IS NOT NULL)
			OR EXISTS(SELECT 1 FROM location_accepted_receipt r JOIN location_sample_purpose_authority p
			ON p.sample_id = r.sample_id WHERE r.source_admission_ordinal > ? AND r.source_admission_ordinal <= ?
			AND p.purpose = 'SESSION_CAPTURE')
		""".trimIndent(), arrayOf(after, through, after, through))).use { it.moveToFirst() && it.getInt(0) != 0 }
		if (products) boundary.refuse(SourceWriterTransitionBlocker.ACTIVE_LANE_CONFLICT)
		val deletedThrough = database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L
		var cursor = maxOf(after, minOf(through, deletedThrough))
		while (cursor < through) {
			currentCoroutineContext().ensureActive()
			val page = database.sourceEventWalDao().continuityEventsAfterThrough(cursor, through, 64)
			if (page.isEmpty()) boundary.refuse(SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
			for (row in page) {
				if (row.admissionOrdinal != Math.addExact(cursor, 1L)) {
					boundary.refuse(SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
				}
				cursor = row.admissionOrdinal
				if (row.sourceKind != source.stableCode) continue
				val wal = database.sourceEventWalDao().boundedPayloadByEventId(row.eventId,
					LocationWalQualificationAdapter.MAX_LOCATION_PAYLOAD_BYTES)
				if (wal == null || !wal.hasQualifiedIntegrity() ||
					wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
					boundary.refuse(SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND)
				}
			}
			yield()
		}
	}

	private suspend fun firstPendingAmbientAfter(after: Long, through: Long): Long? {
		var cursor = after
		while (cursor < through) {
			currentCoroutineContext().ensureActive()
			val page = database.sourceEventWalDao().sourceProjectionCandidatesAfterThrough(source.stableCode, cursor, through, 64)
			if (page.isEmpty()) break
			for (row in page) {
				if (row.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_AMBIENT_PRODUCT == 0L) continue
				val original = qualifier.get().qualifyAmbient(SourceEventId(row.eventId))
				if (original is LocationAmbientQualification.Qualified && database.readLocationAmbientReceipt(original.command) == null) {
					return row.admissionOrdinal
				}
			}
			cursor = page.last().admissionOrdinal; yield()
		}
		return null
	}

	private fun nextRevision(current: Long): Long = if (current < Long.MAX_VALUE) current + 1L
		else boundary.refuse(SourceWriterTransitionBlocker.REVISION_EXHAUSTED)
}
