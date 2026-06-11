package com.adsamcik.tracker.shared.utils.module

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * Public interface used for subscribing to collection events.
 */
interface TrackerUpdateReceiver {
    /**
     * Called when new data is tracked.
     */
    @WorkerThread
    fun onNewData(
        context: Context,
        session: TrackerSession,
        collectionData: CollectionData
    )

    companion object {
        const val ACTION_SESSION_UPDATE: String = "update"
        const val ACTION_REGISTER_COMPONENT: String = "com.adsamcik.tracker.listener.REGISTER"
        const val ACTION_UNREGISTER_COMPONENT: String = "com.adsamcik.tracker.listener.UNREGISTER"
        const val RECEIVER_LISTENER_REGISTRATION_CLASSNAME = "className"
    }
}
