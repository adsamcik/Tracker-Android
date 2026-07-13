package com.adsamcik.tracker.activity.api

import android.content.Context
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * Contract for managing live activity-recognition requests.
 */
interface ActivityRequestManager {
    val lastActivity: RecognizedActivity
    val activityUpdates: Flow<ActivityUpdate>
    val transitionUpdates: Flow<List<TransitionUpdate>>

    suspend fun requestActivity(context: Context, requestData: ActivityRequestData): Boolean

    suspend fun removeActivityRequest(context: Context, tClass: KClass<*>)
}
