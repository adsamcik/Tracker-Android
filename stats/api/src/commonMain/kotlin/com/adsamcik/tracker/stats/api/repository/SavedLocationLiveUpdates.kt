package com.adsamcik.tracker.stats.api.repository

/** Process hint after an existing saved point and its purpose commit. Never recording authority. */
interface SavedLocationLiveUpdates {
    fun pointCommitted(sampleId: Long)
}
