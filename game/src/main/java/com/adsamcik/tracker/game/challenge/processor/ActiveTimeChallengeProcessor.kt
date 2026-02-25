package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import javax.inject.Inject

class ActiveTimeChallengeProcessor @Inject constructor() : ChallengeProcessor {
	override val type: ChallengeType = ChallengeType.ActiveTime

	override val titleRes: Int = R.string.challenge_active_time_title

	override fun formatDescription(context: Context, entity: ChallengeEntity): String {
		return context.getString(
			R.string.challenge_active_time_title,
			entity.requiredValue.toInt().toString()
		)
	}

	override fun extractProgress(context: Context, session: TrackerSession): Double {
		val estimatedSeconds = (session.distanceOnFootInM / 1.4)
			.coerceAtMost((session.end - session.start) / 1000.0)
		return estimatedSeconds / 60.0
	}

	override val defaultRequiredValue: Double = 300.0

	override val defaultDurationMs: Long = 7L * 24 * 60 * 60 * 1000
}
