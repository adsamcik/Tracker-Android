package com.adsamcik.tracker.game.goals

import android.content.Context

object GoalResetScheduler {
    fun ensureScheduled(context: Context) {
        NewDayGoalWorker.ensureScheduled(context)
    }
}
