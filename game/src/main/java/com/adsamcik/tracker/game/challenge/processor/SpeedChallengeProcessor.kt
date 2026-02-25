package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import javax.inject.Inject

/**
 * Speed challenge: cover a target distance on foot within a tight time window.
 * Functions identically to WalkDistance but with shorter default duration,
 * creating time pressure through the challenge expiry system.
 */
class SpeedChallengeProcessor @Inject constructor() : ChallengeProcessor {
	override val type: ChallengeType = ChallengeType.Speed

	override val titleRes: Int = R.string.challenge_speed_title

	override fun formatDescription(context: Context, entity: ChallengeEntity): String {
		val formatted = context.resources.formatDistance(
			entity.requiredValue.toFloat(),
			1,
			TrackerSettingsQuick.lengthSystem(context),
		)
		return context.getString(R.string.challenge_speed_description, formatted)
	}

	override fun extractProgress(context: Context, session: TrackerSession): Double {
		return session.distanceOnFootInM.toDouble()
	}

	// 2km base — achievable in ~25min at brisk walking pace
	override val defaultRequiredValue: Double = 2_000.0

	// 1 day window — tight enough to feel like a sprint challenge
	override val defaultDurationMs: Long = 24L * 60 * 60 * 1000

	override val minDurationMultiplier: Double get() = 0.5
	override val maxDurationMultiplier: Double get() = 2.0
}
