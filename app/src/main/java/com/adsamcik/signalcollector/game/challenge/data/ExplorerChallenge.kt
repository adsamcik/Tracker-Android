package com.adsamcik.signalcollector.game.challenge.data

import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.tracker.data.TrackerSession

class ExplorerChallenge(override val name: String,
                        val description: String,
                        override val difficulty: ChallengeDifficulty) : Challenge {
	override fun generateDescription(progressData: ChallengeProgressData): String {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun batchProcess(session: TrackerSession) {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

}