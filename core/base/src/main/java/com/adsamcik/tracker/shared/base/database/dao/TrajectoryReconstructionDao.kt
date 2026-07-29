package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.RouteHypothesisEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectorySourceLinkEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryStateEntity
import com.adsamcik.tracker.shared.base.database.data.VisitIntervalEntity

@Dao
interface TrajectoryReconstructionDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRun(run: TrajectoryReconstructionRunEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertStates(states: List<TrajectoryStateEntity>): List<Long>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertSourceLinks(links: List<TrajectorySourceLinkEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRouteHypotheses(hypotheses: List<RouteHypothesisEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertVisitIntervals(visits: List<VisitIntervalEntity>)

	@Query(
		"""
		UPDATE trajectory_reconstruction_run
		SET status = :status, completed_at_ms = :completedAtMs, failure_reason = :failureReason
		WHERE run_id = :runId AND status = 'RUNNING'
		""",
	)
	suspend fun completeRun(
		runId: String,
		status: String,
		completedAtMs: Long,
		failureReason: String? = null,
	): Int

	@Query(
		"""
		SELECT * FROM trajectory_reconstruction_run
		WHERE status = 'COMPLETED'
		  AND source_start_ms <= :toMs
		  AND source_end_ms >= :fromMs
		ORDER BY completed_at_ms DESC
		LIMIT 1
		""",
	)
	suspend fun latestCompletedOverlapping(
		fromMs: Long,
		toMs: Long,
	): TrajectoryReconstructionRunEntity?

	@Query(
		"""
		SELECT * FROM trajectory_reconstruction_run
		WHERE status = 'COMPLETED' AND source_clock_domain_id = :clockDomainId
		ORDER BY completed_at_ms DESC
		LIMIT 1
		""",
	)
	suspend fun latestCompletedForClockDomain(
		clockDomainId: String,
	): TrajectoryReconstructionRunEntity?

	@Query(
		"""
		SELECT * FROM trajectory_state
		WHERE run_id = :runId AND estimate_kind = :estimateKind
		ORDER BY state_index ASC
		""",
	)
	suspend fun states(runId: String, estimateKind: String = "SMOOTHED"): List<TrajectoryStateEntity>

	/**
	 * Derived states cannot outlive the raw source range that explains them.
	 *
	 * Deleting the run cascades to states, visits, hypotheses, and source links.
	 */
	@Query("DELETE FROM trajectory_reconstruction_run WHERE source_start_ms < :beforeMs")
	suspend fun deleteWithSourceBefore(beforeMs: Long): Int

	@Query("DELETE FROM trajectory_reconstruction_run")
	fun deleteAll()
}
