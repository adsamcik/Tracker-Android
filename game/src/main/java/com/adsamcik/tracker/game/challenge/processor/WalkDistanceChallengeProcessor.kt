package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import javax.inject.Inject

class WalkDistanceChallengeProcessor @Inject constructor() : ChallengeProcessor {
	override val type: ChallengeType = ChallengeType.WalkDistance

	override val titleRes: Int = R.string.challenge_walk_distance_title

	override fun formatDescription(context: Context, entity: ChallengeEntity): String {
		val formatted = context.resources.formatDistance(
			entity.requiredValue.toFloat(),
			1,
			TrackerSettingsQuick.lengthSystem(context)
		)
		return context.getString(R.string.challenge_walk_in_the_park_description, formatted)
	}

	override suspend fun extractProgress(context: Context, session: TrackerSession): Double {
		return session.distanceOnFootInM.toDouble()
	}

	override val defaultRequiredValue: Double = 30_000.0

	override val defaultDurationMs: Long = 7L * 24 * 60 * 60 * 1000
}
