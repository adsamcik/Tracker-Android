package com.adsamcik.signalcollector.activity

import android.content.Context
import com.adsamcik.signalcollector.common.data.ActivityInfo
import kotlin.reflect.KClass

typealias ActivityRequestCallback = (context: Context, activity: ActivityInfo, elapsedTime: Long) -> Unit

data class ActivityRequest(val key: KClass<*>, val detectionIntervalS: Int, val callback: ActivityRequestCallback)