package com.adsamcik.signalcollector.game.challenge.data.instance

import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.extra.WalkInTheParkChallengeEntry
import com.adsamcik.signalcollector.misc.extension.rescale
import com.adsamcik.signalcollector.tracker.data.TrackerSession

class WalkInTheParkChallengeInstance(data: ChallengeEntry,
                                     title: String,
                                     descriptionTemplate: String,
                                     extra: WalkInTheParkChallengeEntry)
	: ChallengeInstance<WalkInTheParkChallengeEntry>(data, title, descriptionTemplate, extra) {

	override val description: String
		get() = descriptionTemplate.format(extra.requiredDistanceInM)
	override val progress: Int
		get() = ((extra.distanceInM.toDouble() / extra.requiredDistanceInM.toDouble())).rescale(0.0..100.0).toInt()

	override fun batchProcess(session: TrackerSession) {
		extra.distanceInM += session.distanceOnFootInM
		if (extra.distanceInM >= extra.requiredDistanceInM)
			extra.isCompleted = true
	}

}