package com.adsamcik.tracker.game.challenge.data

import android.content.Context
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase

interface ChallengePersistence<Instance : ChallengeInstance<*, *>> {
	fun getDatabase(context: Context): ChallengeDatabase = ChallengeDatabase.database(context)

	fun load(context: Context, entryId: Long): Instance

	fun persist(context: Context, instance: Instance)
}
