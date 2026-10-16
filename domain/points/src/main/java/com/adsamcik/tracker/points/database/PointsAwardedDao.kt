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
	/** Reads one effect by its source and stable key, returning null when no receipt exists. */
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
			AND effect_revision < :effectRevision
		""",
	)
	/** Replaces an effect only with a higher revision; returns the number of rows updated. */
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
		return applyRevisionedEffect(AwardSource.GOAL, effectKey, effectRevision, time, valueMicros)
	}

	@Query("SELECT * FROM points_awarded WHERE source = 'session' AND effect_key IS NOT NULL " +
		"AND id > :afterId ORDER BY id LIMIT :limit")
	/** Pages session effect receipts by increasing row ID after the exclusive cursor. */
	suspend fun sessionEffectPage(afterId: Int, limit: Int): List<PointsAwarded>

	/** A recording keeps its original receipt key after logical backup restore. */
	@Query("SELECT * FROM points_awarded WHERE source = 'session' " +
		"AND effect_key LIKE 'recording:%:' || :segmentId ORDER BY id LIMIT 2")
	suspend fun sessionEffects(segmentId: Long): List<PointsAwarded>

	@Query("SELECT EXISTS(SELECT 1 FROM points_awarded WHERE source = 'session' " +
		"AND effect_key IS NULL AND time = :time)")
	/** Detects timestamp-only session awards so new receipt keys do not grant the same older award twice. */
	suspend fun hasLegacySessionAwardAt(time: Long): Boolean

	@Transaction
	/** Applies the latest session effect transactionally, preserving old timestamp-only duplicate prevention. */
	suspend fun applySessionEffect(effectKey: String, effectRevision: Long, time: Long, valueMicros: Long): Boolean {
		// The older timestamp-only row has no safe segment association. Never grant it twice.
		if (getRevisionedEffect(AwardSource.SESSION.value, effectKey) == null && hasLegacySessionAwardAt(time)) {
			return false
		}
		return applyRevisionedEffect(AwardSource.SESSION, effectKey, effectRevision, time, valueMicros)
	}

	@Transaction
	/** Acknowledges identical replay, rejects stale/conflicting revision payloads and retains zero-value retractions. */
	suspend fun applyRevisionedEffect(awardSource: AwardSource, effectKey: String, effectRevision: Long,
		time: Long, valueMicros: Long): Boolean {
		require(effectKey.isNotBlank()) { "Effect key must not be blank" }
		require(effectRevision > 0L) { "Effect revision must be positive" }
		require(valueMicros >= 0L) { "Goal points must not be negative" }

		val source = awardSource.value
		val current = getRevisionedEffect(source, effectKey)
		if (current != null) {
			val currentRevision = checkNotNull(current.effectRevision) {
				"Revisioned goal effect is missing its revision"
			}
			if (currentRevision > effectRevision) {
				return false
			}
			if (currentRevision == effectRevision) {
				// One revision has one exact payload. Replay acknowledges without rewriting;
				// different values need a newer correction, including a zero-value retraction.
				return current.time == time && current.effectValueMicros == valueMicros &&
					current.value.value == valueMicros.toDouble() / POINT_MICROS
			}

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
				source = awardSource,
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
