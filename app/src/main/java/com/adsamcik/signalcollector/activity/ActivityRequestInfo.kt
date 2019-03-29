package com.adsamcik.signalcollector.activity

/**
 * Activity request object that is used as activity update request for Activity Recognition API
 */
data class ActivityRequestInfo(var updateDelay: Int, var isBackgroundTracking: Boolean)