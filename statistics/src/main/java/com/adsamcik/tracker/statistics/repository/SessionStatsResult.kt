package com.adsamcik.tracker.statistics.repository

import com.adsamcik.tracker.statistics.data.Stat

sealed class SessionStatsResult {
    data class Success(val stats: List<Stat>) : SessionStatsResult()

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SessionStatsResult()
}
