package com.adsamcik.tracker.activity.api

import android.content.Context
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import kotlin.reflect.KClass

/**
 * Contract for managing live activity-recognition requests.
 */
interface ActivityRequestManager {
    val lastActivity: ActivityInfo

    fun requestActivity(context: Context, requestData: ActivityRequestData): Boolean

    fun removeActivityRequest(context: Context, tClass: KClass<*>)
}
