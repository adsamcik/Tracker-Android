package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.SavedLocationLiveUpdates
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One bounded wakeup; readers catch up through saved receipt IDs instead of trusting this payload. */
@Singleton
internal class SavedLocationLiveUpdateSignal @Inject constructor() : SavedLocationLiveUpdates {
    private val pending = MutableSharedFlow<Long>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hints = pending.asSharedFlow()
    override fun pointCommitted(sampleId: Long) {
        require(sampleId > 0L)
        pending.tryEmit(sampleId)
    }
}
