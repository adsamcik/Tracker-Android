package com.adsamcik.signalcollector.game.challenge.database

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.ChallengeType
import com.adsamcik.signalcollector.game.challenge.data.persistence.ExplorerChallengePersistence
import com.adsamcik.signalcollector.game.challenge.data.persistence.StepChallengePersistence
import com.adsamcik.signalcollector.game.challenge.data.persistence.WalkDistanceChallengePersistence
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry

object ChallengeLoader {
	@WorkerThread
	fun loadChallenge(context: Context, entry: ChallengeEntry): ChallengeInstance<*, *> {
		return when (entry.type) {
			ChallengeType.Explorer -> ExplorerChallengePersistence().load(context, entry.id)
			ChallengeType.WalkDistance -> WalkDistanceChallengePersistence().load(context, entry.id)
			ChallengeType.Step -> StepChallengePersistence().load(context, entry.id)
		}
	}
}
