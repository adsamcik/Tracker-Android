package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSourcePlanStore @Inject constructor(
	private val database: AppDatabase,
	private val codec: SourcePlanCodec,
) {
	suspend fun persistDesired(plan: AcquisitionPlanRevision, status: DesiredPlanStatus) {
		database.withTransaction {
			val dao = database.sourcePlanStateDao()
			val existing = dao.revision(plan.revision)
			val encoded = plan.plans.values.sortedBy { it.source.stableCode }.map { sourcePlan ->
				val value = codec.encode(sourcePlan)
				SourceDesiredPlanEntity(plan.revision, sourcePlan.source.stableCode, 1, value.bytes, value.checksum)
			}
			if (existing == null) {
				dao.insertRevision(
					AcquisitionPlanRevisionEntity(
						plan.revision,
						plan.planId,
						plan.createdAtMs,
						status.name,
						plan.sourcePolicyRevision,
					),
				)
				dao.insertDesiredPlans(encoded)
			} else {
				check(existing.planId == plan.planId) { "Plan revision identity collision" }
				check(existing.createdAtMs == plan.createdAtMs) { "Plan revision creation-time collision" }
				check(existing.sourcePolicyRevision == plan.sourcePolicyRevision) {
					"Plan revision source-policy collision"
				}
				val stored = dao.desiredPlans(plan.revision)
				check(stored.map { it.sourceKind to it.payloadChecksum } ==
					encoded.map { it.sourceKind to it.payloadChecksum }) {
					"Plan revision payload collision"
				}
				dao.updateRevisionStatus(plan.revision, status.name)
			}
		}
	}

	suspend fun load(revision: Long): AcquisitionPlanRevision? = database.withTransaction {
		val dao = database.sourcePlanStateDao()
		val header = dao.revision(revision) ?: return@withTransaction null
		val plans = dao.desiredPlans(revision).associate { entity ->
			val source = SourceKind.entries.single { it.stableCode == entity.sourceKind }
			source to codec.decode(entity.payload)
		}
		AcquisitionPlanRevision(
			header.revision,
			header.planId,
			header.createdAtMs,
			plans,
			header.sourcePolicyRevision,
		)
	}

	suspend fun saveApplied(state: AppliedSourcePlan, updatedAtMs: Long) {
		database.sourcePlanStateDao().saveAppliedState(
			SourceAppliedPlanStateEntity(
				sourceKind = state.source.stableCode,
				desiredRevision = state.desiredRevision,
				appliedRevision = state.appliedRevision,
				sourceInstanceId = state.sourceInstanceId?.value,
				registrationGeneration = state.registrationGeneration,
				appliedAtElapsedNanos = state.appliedAtElapsedRealtimeNanos,
				status = state.status.name,
				degradedReasons = state.degradedReasons.map(SourceDegradedReason::name).sorted().joinToString(","),
				updatedAtMs = updatedAtMs,
			),
		)
	}

	suspend fun updateStatus(revision: Long, status: DesiredPlanStatus) {
		check(database.sourcePlanStateDao().updateRevisionStatus(revision, status.name) == 1)
	}
}

enum class DesiredPlanStatus { DESIRED, APPLYING, EFFECTIVE, DEGRADED, FAILED, SUPERSEDED }
