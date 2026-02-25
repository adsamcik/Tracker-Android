package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import javax.inject.Inject

class StepChallengeProcessor @Inject constructor() : ChallengeProcessor {
	override val type: ChallengeType = ChallengeType.Step

	override val titleRes: Int = R.string.challenge_step_title

	override fun formatDescription(context: Context, entity: ChallengeEntity): String {
		return context.getString(
			R.string.challenge_step_description,
			entity.requiredValue.toInt().toString()
		)
	}

	override fun extractProgress(context: Context, session: TrackerSession): Double {
		return session.steps.toDouble()
	}

	override val defaultRequiredValue: Double = 50_000.0

	override val defaultDurationMs: Long = 7L * 24 * 60 * 60 * 1000
}
