package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import javax.inject.Inject
import javax.inject.Singleton

/** Completes the existing clear operation while its provider and startup fences remain closed. */
@Singleton
class SharedSourceWriterDeletionRearm @Inject internal constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val stepsRearm: StepsWriterDeletionRearm,
) {
	suspend fun rearmAfterFullDeletion(
		operationId: String,
		targetCollectedDataEpoch: Long,
		updatedAtMs: Long,
	) {
		require(operationId.isNotBlank())
		require(targetCollectedDataEpoch > 0L)
		require(updatedAtMs >= 0L)
		val lifecycle = lifecycleStore.snapshot()
		database.withTransaction {
			val operations = database.collectedDataDeletionOperationDao()
			val operation = requireNotNull(operations.get(operationId)) {
				"Collected-data deletion operation receipt is missing"
			}
			val evidence = requireNotNull(database.sourceEvidenceStateDao().get()) {
				"Collected-data deletion evidence guard is missing"
			}
			check(operation.targetCollectedDataEpoch == targetCollectedDataEpoch &&
				lifecycle.epoch == targetCollectedDataEpoch &&
				evidence.collectedDataEpoch == targetCollectedDataEpoch &&
				operation.retainedFromMs == lifecycle.retainedFromMs &&
				operation.retainedFromMs == evidence.retainedFromMs &&
				operation.retentionWorkExecutionId == null &&
				operation.retentionDestructivePlan == null
			) { "Collected-data deletion operation no longer owns this lifecycle" }
			if (operation.phase == CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED) {
				return@withTransaction
			}
			check(operation.phase == CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED) {
				"Collected-data deletion operation is not ready for writer re-arm"
			}
			check(updatedAtMs >= operation.deletedAtMs) {
				"Collected-data deletion phase timestamp predates its operation"
			}
			check(!oldRowsRemain()) { "Collected-data deletion source rows remain" }
			check(database.liveSourceProjectionActivationOrdinal() ==
				Math.addExact(evidence.deletedSourceEventHighWaterOrdinal, 1L)
			) { "Collected-data deletion WAL allocation fence changed" }
			for (spec in SourceWriterTransitionSpec.entries) {
				resetKnownOwner(spec, operation, updatedAtMs)
			}
			// Protected Location has no 2N owner arithmetic. Its original owner is left intact.
			// Steps retains its established arithmetic and updates contained rollout only once.
			stepsRearm.applyInCurrentTransaction(updatedAtMs)
			check(lifecycleStore.snapshot() == lifecycle) {
				"Collected-data lifecycle changed during writer re-arm"
			}
			check(operations.compareAndSetPhase(
				operationId = operationId,
				targetCollectedDataEpoch = targetCollectedDataEpoch,
				expectedPhase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
				newPhase = CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
				updatedAtMs = updatedAtMs,
			) == 1) { "Collected-data deletion writer re-arm phase changed concurrently" }
		}
	}

	private suspend fun resetKnownOwner(
		spec: SourceWriterTransitionSpec,
		operation: CollectedDataDeletionOperationEntity,
		updatedAtMs: Long,
	) {
		val dao = database.sourceDestinationOwnerDao()
		val owner = dao.get(spec.source.stableCode, spec.destination)
		if (owner == null) {
			// Activity/Pressure originally have legacy owners. Radio originally permits absence.
			if (spec.initialOwner == null) {
				check(dao.insertIfAbsent(SourceDestinationOwnerEntity(
					sourceKind = spec.source.stableCode,
					destination = spec.destination,
					owner = spec.candidateOwner,
					ownerGeneration = FINAL_OWNER_GENERATION,
					updatedAtMs = updatedAtMs,
				)) != -1L) { "Collected-data deletion destination appeared concurrently" }
			}
			return
		}
		val recognized = when (owner.ownerGeneration) {
			1L -> spec.initialOwner != null && owner.owner == spec.initialOwner
			2L, 4L -> owner.owner == spec.candidateOwner
			3L, 5L -> owner.owner == spec.containedOwner
			else -> false
		}
		// Unknown authority is preserved as source-local repair debt; it grants no rollout.
		if (!recognized || (owner.ownerGeneration == FINAL_OWNER_GENERATION &&
			owner.owner == spec.candidateOwner)) return
		check(dao.resetKnownCaptureOwnerAfterFullDeletion(
			sourceKind = spec.source.stableCode,
			destination = spec.destination,
			expectedOwner = owner.owner,
			expectedOwnerGeneration = owner.ownerGeneration,
			operationId = operation.operationId,
			targetCollectedDataEpoch = operation.targetCollectedDataEpoch,
			updatedAtMs = updatedAtMs,
		) == 1) { "Collected-data deletion destination owner changed concurrently" }
	}

	private fun oldRowsRemain(): Boolean {
		// These are fixed existing source authority/payload tables, not a generic cleanup registry.
		val sql = "SELECT " + EMPTY_AFTER_CLEAR_TABLES.joinToString(" OR ") {
			"EXISTS(SELECT 1 FROM $it LIMIT 1)"
		}
		return database.openHelper.writableDatabase.query(sql).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getInt(0) != 0
		}
	}

	private companion object {
		const val FINAL_OWNER_GENERATION = 4L
		val EMPTY_AFTER_CLEAR_TABLES = listOf(
			"source_event_wal", "pending_signal", "source_product_projection_lane",
			"source_projection_registration", "source_projection_checkpoint",
			"source_projection_failure", "source_projection_join_state",
			"source_projection_outbox", "source_coordinator_lease",
			"legacy_v27_projection_drain", "legacy_v27_projection_target",
			"logical_tracking_session", "source_service_run", "session_manifest_version",
			"session_manifest_source", "session_lifecycle_intent_version",
			"lifecycle_desired_action", "source_session_completeness",
			"source_capture_admission_barrier", "source_run_retirement",
			"source_caller_accepted_authority", "source_demand", "provider_registration_generation",
			"source_authorization", "source_registration_state", "source_runtime_state",
			"source_applied_plan_state", "location_sample", "location_observation",
			"location_accepted_receipt", "location_sample_purpose_authority",
			"location_projection_observation", "location_projection_point",
			"step_fact_revision", "pressure_fact_revision", "activity_captured_window_revision",
			"activity_captured_fragment", "activity_captured_evidence",
			"activity_captured_window_cursor", "activity_captured_registration_plan",
			"wifi_captured_fact_revision", "wifi_captured_fact_cursor",
			"cell_captured_fact_revision", "cell_captured_fact_cursor",
			"step_interval", "activity_snapshot", "pressure_sample",
			"wifi_observation", "cell_sample",
		)
	}
}
