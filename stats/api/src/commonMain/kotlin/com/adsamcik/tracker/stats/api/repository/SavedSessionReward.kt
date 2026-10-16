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

/**
 * Current saved recording reward, removal revision, legacy ownership or unavailable source data.
 */
sealed interface SavedSessionRewardRead {
    /**
     * One current completed-recording reward with its exact data epoch and revision.
     */
    data class Ready(val value: SavedSessionReward) : SavedSessionRewardRead
    /**
     * Current data epoch/revision indicates that the previous saved recording reward should be
     * retracted.
     */
    data class Removed(val dataEpoch: Long, val revision: Long) : SavedSessionRewardRead
    /** Older awards have no reconstructable recording ownership; preserve them without awarding twice. */
    data object Legacy : SavedSessionRewardRead
    /**
     * The requested data or authority is unavailable; any named reason preserves the original
     * failure cause.
     */
    data object Unavailable : SavedSessionRewardRead
}
