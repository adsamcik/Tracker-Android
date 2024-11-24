package com.adsamcik.tracker.game.challenge.data.definition

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.instance.ActiveTimeChallengeInstance
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.builder.ActiveTimeChallengeBuilder
import com.adsamcik.tracker.shared.base.Time

class ActiveTimeChallengeDefinition : ChallengeDefinition<ActiveTimeChallengeInstance>(
    R.string.challenge_active_time_title,
    R.string.challenge_active_time_description,
    Time.DAY_IN_MILLISECONDS * BASE_DAY_COUNT
) {
    val defaultActiveTimeInMinutes: Int = BASE_MINUTES_PER_DAY * BASE_DAY_COUNT

    override val type: ChallengeType = ChallengeType.ActiveTime

    override fun newInstance(context: Context, startAt: Long): ActiveTimeChallengeInstance {
        return ActiveTimeChallengeBuilder(this).build(context, startAt)
    }

    companion object {
        private const val BASE_MINUTES_PER_DAY = 30
        private const val BASE_DAY_COUNT = 7
    }
}
