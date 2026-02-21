package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.entity.ActiveTimeChallengeEntity
import com.adsamcik.tracker.game.challenge.data.persistence.ActiveTimeChallengePersistence
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.data.TrackerSession

class ActiveTimeChallengeInstance(
    data: ChallengeEntry,
    definition: ChallengeDefinition<ActiveTimeChallengeInstance>,
    extra: ActiveTimeChallengeEntity
) : ChallengeInstance<ActiveTimeChallengeEntity, ActiveTimeChallengeInstance>(
    data,
    definition,
    extra
) {

    override val persistence
        get() = ActiveTimeChallengePersistence()

    override fun getTitle(context: Context): String {
        return context.getString(definition.titleRes, extra.requiredActiveTimeInMinutes)
    }

    override fun getDescription(context: Context): String {
        return context.getString(definition.descriptionRes, extra.requiredActiveTimeInMinutes)
    }

    override val progress: Double
        get() = if (extra.requiredActiveTimeInMinutes > 0) {
            (extra.activeTimeInMinutes.toDouble() / extra.requiredActiveTimeInMinutes.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

    override fun checkCompletionConditions() = extra.activeTimeInMinutes >= extra.requiredActiveTimeInMinutes

    override fun processSession(context: Context, session: TrackerSession) {
        extra.activeTimeInMinutes += estimateTimeOnFoot(session)
    }

    private fun estimateTimeOnFoot(session: TrackerSession): Int {
        val averageWalkingSpeed = 1.4 // meters per second

        // Calculate time on foot in seconds
        val timeOnFootInSeconds = session.distanceOnFootInM / averageWalkingSpeed

        // Ensure the calculated time does not exceed the session duration
        val sessionDurationInSeconds = (session.end - session.start) / 1000.0

        return (timeOnFootInSeconds.coerceAtMost(sessionDurationInSeconds) / 60.0).toInt()
    }

}
