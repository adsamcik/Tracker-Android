package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity

@Dao
interface SourcePlanStateDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRevision(entity: AcquisitionPlanRevisionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDesiredPlans(entities: List<SourceDesiredPlanEntity>)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveAppliedState(entity: SourceAppliedPlanStateEntity)

	@Query("SELECT * FROM acquisition_plan_revision WHERE revision = :revision")
	suspend fun revision(revision: Long): AcquisitionPlanRevisionEntity?

	@Query("SELECT * FROM acquisition_plan_revision ORDER BY revision DESC LIMIT 1")
	suspend fun latestRevision(): AcquisitionPlanRevisionEntity?

	@Query("SELECT * FROM source_desired_plan WHERE revision = :revision ORDER BY source_kind")
	suspend fun desiredPlans(revision: Long): List<SourceDesiredPlanEntity>

	/** Exact immutable source plan; unlike applied state this row is never replaced by reconfigure. */
	@Query(
		"SELECT * FROM source_desired_plan WHERE revision = :revision " +
			"AND source_kind = :sourceKind LIMIT 1",
	)
	suspend fun desiredPlan(revision: Long, sourceKind: Int): SourceDesiredPlanEntity?

	/** Current physical-session snapshot only; this table is not per-revision history. */
	@Query("SELECT * FROM source_applied_plan_state ORDER BY source_kind")
	suspend fun appliedStates(): List<SourceAppliedPlanStateEntity>

	@Query(
		"SELECT * FROM source_applied_plan_state WHERE desired_revision = :desiredRevision " +
			"ORDER BY source_kind",
	)
	suspend fun appliedStates(desiredRevision: Long): List<SourceAppliedPlanStateEntity>

	@Query("UPDATE acquisition_plan_revision SET status = :status WHERE revision = :revision")
	suspend fun updateRevisionStatus(revision: Long, status: String): Int

	@Query("DELETE FROM source_applied_plan_state WHERE source_kind NOT IN (:sourceKinds)")
	suspend fun deleteAppliedStatesOutside(sourceKinds: Collection<Int>): Int

	@Query("DELETE FROM source_applied_plan_state")
	fun deleteAllAppliedStates()

	@Query("DELETE FROM source_desired_plan")
	fun deleteAllDesiredPlans()

	@Query("DELETE FROM acquisition_plan_revision")
	fun deleteAllRevisions()
}
