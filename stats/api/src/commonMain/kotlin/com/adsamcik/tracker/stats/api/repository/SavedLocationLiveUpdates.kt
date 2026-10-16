package com.adsamcik.tracker.stats.api.repository

/** Process hint after an existing saved point and its purpose commit. Never recording authority. */
interface SavedLocationLiveUpdates {
    /**
     * Publishes a process-local hint after this positive saved point identity and its purpose
     * associations commit.
     */
    fun pointCommitted(sampleId: Long)
}
