package com.adsamcik.tracker.stats.api.repository

/** A real completed recording, never a retrospective annotation or imported session label. */
data class SavedSessionReward(
    val segmentId: Long,
    val dataEpoch: Long,
    val revision: Long,
    val earnedAtMs: Long,
    val durationMs: Long,
    val distanceMeters: Double?,
) {
    val effectKey: String get() = "recording:$dataEpoch:$segmentId"
}

sealed interface SavedSessionRewardRead {
    data class Ready(val value: SavedSessionReward) : SavedSessionRewardRead
    data class Removed(val dataEpoch: Long, val revision: Long) : SavedSessionRewardRead
    /** Older awards have no reconstructable recording ownership; preserve them without awarding twice. */
    data object Legacy : SavedSessionRewardRead
    data object Unavailable : SavedSessionRewardRead
}
