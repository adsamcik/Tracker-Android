package com.adsamcik.tracker.game.challenge.data.persistence

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengePersistence
import com.adsamcik.tracker.game.challenge.data.definition.ActiveTimeChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.instance.ActiveTimeChallengeInstance

class ActiveTimeChallengePersistence : ChallengePersistence<ActiveTimeChallengeInstance> {
    override fun persist(context: Context, instance: ActiveTimeChallengeInstance) {
        val database = getDatabase(context)
        database.entryDao().update(instance.data)
        database.activeTimeDao().update(instance.extra)
    }

    override fun load(context: Context, entryId: Long): ActiveTimeChallengeInstance {
        val database = getDatabase(context)
        val entry = database.entryDao().get(entryId)
        val entity = database.activeTimeDao().getByEntry(entryId)
        val definition = ActiveTimeChallengeDefinition()
        return ActiveTimeChallengeInstance(
            entry,
            definition,
            entity
        )
    }
}
