package com.adsamcik.tracker.game.challenge.data.builder

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeBuilder
import com.adsamcik.tracker.game.challenge.data.definition.ActiveTimeChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.entity.ActiveTimeChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.ActiveTimeChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.Time

internal class ActiveTimeChallengeBuilder(
    private val definition: ActiveTimeChallengeDefinition
) : ChallengeBuilder<ActiveTimeChallengeInstance>(definition) {

    private var requiredActiveTimeInMinutes: Int = 0

    private fun selectRequiredActiveTime() {
        val minMultiplier = 0.5
        val maxMultiplier = 2.0

        // Adjust min and max multipliers slightly based on duration
        val adjustment = 0.3 * (durationMultiplierNormalized - 0.5)
        val adjustedMin = minMultiplier + adjustment
        val adjustedMax = maxMultiplier + adjustment

        val countMultiplier = normalRandom(adjustedMin..adjustedMax)
        requiredActiveTimeInMinutes = (definition.defaultActiveTimeInMinutes * countMultiplier).toInt()

        // Calculate rate (minutes per day)
        val durationDays = duration.toDouble() / Time.DAY_IN_MILLISECONDS
        val rate = requiredActiveTimeInMinutes / durationDays

        // Normalize rate against default rate
        val defaultRate = definition.defaultActiveTimeInMinutes / (definition.defaultDuration.toDouble() / Time.DAY_IN_MILLISECONDS)
        val difficultyValue = rate / defaultRate

        addDifficulty(difficultyValue)
    }

    override fun selectChallengeSpecificParameters() {
        selectRequiredActiveTime()
    }

    override fun buildChallenge(
        context: Context,
        entry: ChallengeEntry
    ): ActiveTimeChallengeInstance {
        return ActiveTimeChallengeInstance(
            entry, definition,
            ActiveTimeChallengeEntity(entry.id, false, 0, requiredActiveTimeInMinutes)
        )
    }

    override fun persistExtra(
        database: ChallengeDatabase,
        challenge: ActiveTimeChallengeInstance
    ) {
        database.activeTimeDao().insert(challenge.extra)
    }
}
