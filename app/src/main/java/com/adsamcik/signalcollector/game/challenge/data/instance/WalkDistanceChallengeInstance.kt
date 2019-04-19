package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.misc.extension.formatDistance
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession

class WalkDistanceChallengeInstance(context: Context,
                                    data: ChallengeEntry,
                                    title: String,
                                    descriptionTemplate: String,
                                    extra: WalkDistanceChallengeEntity)
	: ChallengeInstance<WalkDistanceChallengeEntity>(data, title, descriptionTemplate, extra) {

	private val context = context.applicationContext

	override val description: String
		get() {
			val lengthSystem = Preferences.getLengthSystem(context)
			val resources = context.resources
			return descriptionTemplate.format(resources.formatDistance(extra.requiredDistanceInM, 1, lengthSystem))
		}

	override val progress: Double
		get() = extra.distanceInM.toDouble() / extra.requiredDistanceInM.toDouble()

	override fun checkCompletionConditions() = extra.distanceInM >= extra.requiredDistanceInM

	override fun processSession(session: TrackerSession) {
		extra.distanceInM += session.distanceOnFootInM
	}

}