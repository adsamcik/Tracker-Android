package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import javax.inject.Inject

/**
 * Consistency challenge: track at least once on N distinct calendar days.
 * Sessions with < [MIN_COLLECTIONS] don't count (prevents accidental start/stop).
 * Uses [ChallengeEntity.extraJson] to persist the set of tracked epoch-days.
 */
class ConsistencyChallengeProcessor @Inject constructor() : ChallengeProcessor {
	override val type: ChallengeType = ChallengeType.Consistency

	override val titleRes: Int = R.string.challenge_consistency_title

	override fun formatDescription(context: Context, entity: ChallengeEntity): String {
		return context.getString(
			R.string.challenge_consistency_description,
			entity.requiredValue.toInt(),
		)
	}

	override fun extractProgress(context: Context, session: TrackerSession): Double {
		// Consistency doesn't use simple additive progress —
		// it's handled by updateEntity() which manages the day set.
		// Return 0 here; actual progress is computed in updateEntity.
		return 0.0
	}

	/**
	 * Update entity with the new session's calendar day.
	 * Returns the updated entity with new currentValue = distinct day count.
	 */
	fun updateEntity(entity: ChallengeEntity, session: TrackerSession): ChallengeEntity {
		if (session.collections < MIN_COLLECTIONS) return entity

		val epochDay = session.start / MS_PER_DAY
		val days = parseTrackedDays(entity.extraJson)

		if (!days.add(epochDay)) return entity

		return entity.copy(
			currentValue = days.size.toDouble(),
			extraJson = """{"$KEY_TRACKED_DAYS":[${days.joinToString(",")}]}""",
		)
	}

	private fun parseTrackedDays(extraJson: String?): MutableSet<Long> {
		if (extraJson.isNullOrBlank()) return mutableSetOf()
		return DAY_REGEX.findAll(extraJson)
			.mapTo(mutableSetOf()) { it.value.toLong() }
	}

	// 7 unique days base
	override val defaultRequiredValue: Double = 7.0

	// 10 day window (gives 3 days of slack)
	override val defaultDurationMs: Long = 10L * 24 * 60 * 60 * 1000

	override val minDurationMultiplier: Double get() = 0.7
	override val maxDurationMultiplier: Double get() = 2.0

	companion object {
		private const val MIN_COLLECTIONS = 2
		private const val MS_PER_DAY = 86_400_000L
		private const val KEY_TRACKED_DAYS = "trackedDays"
		private val DAY_REGEX = Regex("-?\\d+")
	}
}
