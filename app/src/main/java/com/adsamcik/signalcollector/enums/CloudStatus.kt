package com.adsamcik.signalcollector.enums

import android.support.annotation.IntDef

object CloudStatuses {
    const val UNKNOWN = -1
    const val NO_SYNC_REQUIRED = 0
    const val SYNC_AVAILABLE = 1
    const val SYNC_SCHEDULED = 2
    const val SYNC_IN_PROGRESS = 3
    const val ERROR = 4

    @IntDef(UNKNOWN, NO_SYNC_REQUIRED, SYNC_AVAILABLE, SYNC_SCHEDULED, SYNC_IN_PROGRESS, ERROR)
    @Retention(AnnotationRetention.SOURCE)
    annotation class CloudStatus
}
