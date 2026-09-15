package com.adsamcik.tracker.points.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

/**
 * DAO for awarded points
 */
@Dao
interface PointsAwardedDao : BaseDao<PointsAwarded> {
	/**
	 * Returns number of points earned between two time intervals.
	 */
	@Query("SELECT COALESCE(SUM(value), 0) FROM points_awarded WHERE time >= :from AND time <= :to")
	fun countBetween(from: Long, to: Long): Double

	/**
	 * Returns number of points earned between two time intervals as a Flow.
	 */
	@Query("SELECT COALESCE(SUM(value), 0) FROM points_awarded WHERE time >= :from AND time <= :to")
	fun countBetweenFlow(from: Long, to: Long): Flow<Double>

	@Query("SELECT EXISTS(SELECT 1 FROM points_awarded WHERE time = :time AND source = :source LIMIT 1)")
	fun hasAwardAt(time: Long, source: String): Boolean

	@Query(
		"""
		SELECT * FROM points_awarded
		WHERE source = :source AND effect_key = :effectKey
		LIMIT 1
		""",
	)
	suspend fun getRevisionedEffect(source: String, effectKey: String): PointsAwarded?

	@Query(
		"""
		UPDATE points_awarded
		SET time = :time,
			value = :value,
			effect_revision = :effectRevision,
			effect_value_micros = :effectValueMicros
		WHERE source = :source
			AND effect_key = :effectKey
			AND effect_revision <= :effectRevision
		""",
	)
	suspend fun updateRevisionedEffect(
		source: String,
		effectKey: String,
		effectRevision: Long,
		time: Long,
		value: Double,
		effectValueMicros: Long,
	): Int

	/**
	 * Applies the latest revision of one derived goal award.
	 *
	 * A zero-valued revision is retained as a durable receipt. Removing the row would allow an
	 * older asynchronous projection to recreate a reward after a correction or source deletion.
	 */
	@Transaction
	suspend fun applyGoalEffect(
		effectKey: String,
		effectRevision: Long,
		time: Long,
		valueMicros: Long,
	): Boolean {
		require(effectKey.isNotBlank()) { "Effect key must not be blank" }
		require(effectRevision > 0L) { "Effect revision must be positive" }
		require(valueMicros >= 0L) { "Goal points must not be negative" }

		val source = AwardSource.GOAL.value
		val current = getRevisionedEffect(source, effectKey)
		if (current != null) {
			val currentRevision = checkNotNull(current.effectRevision) {
				"Revisioned goal effect is missing its revision"
			}
			if (currentRevision > effectRevision) return false

			return updateRevisionedEffect(
				source = source,
				effectKey = effectKey,
				effectRevision = effectRevision,
				time = time,
				value = valueMicros.toDouble() / POINT_MICROS,
				effectValueMicros = valueMicros,
			) == 1
		}

		return insert(
			PointsAwarded(
				id = 0,
				time = time,
				value = Points(
					valueMicros.toDouble() / POINT_MICROS,
				),
				source = AwardSource.GOAL,
				effectKey = effectKey,
				effectRevision = effectRevision,
				effectValueMicros = valueMicros,
			),
		) != -1L
	}

	@Query("DELETE FROM points_awarded")
	fun deleteAll()

	private companion object {
		const val POINT_MICROS = 1_000_000.0
	}
}
