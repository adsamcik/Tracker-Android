package com.adsamcik.signalcollector.game.challenge.data

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase

interface ChallengePersistence<Instance : ChallengeInstance<*, *>> {
	fun getDatabase(context: Context) = ChallengeDatabase.getDatabase(context)

	fun load(context: Context, entryId: Long): Instance

	fun persist(context: Context, instance: Instance)
}