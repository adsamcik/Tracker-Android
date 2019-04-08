package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.misc.extension.formatAsDistance
import com.adsamcik.signalcollector.misc.extension.rescale
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.data.TrackerSession

class WalkDistanceChallengeInstance(context: Context,
                                    data: ChallengeEntry,
                                    title: String,
                                    descriptionTemplate: String,
                                    extra: WalkDistanceChallengeEntity)
	: ChallengeInstance<WalkDistanceChallengeEntity>(data, title, descriptionTemplate, extra) {

	private val context = context.applicationContext

	override val description: String
		get() = descriptionTemplate.format(extra.requiredDistanceInM.formatAsDistance(1, Preferences.getLengthSystem(context)))

	override val progress: Int
		get() = ((extra.distanceInM.toDouble() / extra.requiredDistanceInM.toDouble())).rescale(0.0..100.0).toInt()

	override fun checkCompletionConditions() = extra.distanceInM >= extra.requiredDistanceInM

	override fun processSession(session: TrackerSession) {
		extra.distanceInM += session.distanceOnFootInM
	}

}