package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession

import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.tracker.game.challenge.data.persistence.WalkDistanceChallengePersistence
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance


internal class WalkDistanceChallengeInstance(
		data: ChallengeEntry,
		definition: ChallengeDefinition<WalkDistanceChallengeInstance>,
		extra: WalkDistanceChallengeEntity
) : ChallengeInstance<WalkDistanceChallengeEntity, WalkDistanceChallengeInstance>(
		data,
		definition,
		extra
) {

	override val persistence
		get() = WalkDistanceChallengePersistence()

	override fun getDescription(context: Context): String {
		val lengthSystem = Preferences.getLengthSystem(context)
		val resources = context.resources
		return resources.getString(
				definition.descriptionRes,
				resources.formatDistance(extra.requiredDistanceInM, 1, lengthSystem)
		)
	}

	override val progress: Double
		get() = extra.distanceInM.toDouble() / extra.requiredDistanceInM.toDouble()

	override fun checkCompletionConditions() = extra.distanceInM >= extra.requiredDistanceInM

	override fun processSession(context: Context, session: TrackerSession) {
		extra.distanceInM += session.distanceOnFootInM
	}

}

