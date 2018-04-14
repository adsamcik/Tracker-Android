package com.adsamcik.signalcollector.enums

import android.support.annotation.IntDef

const val STILL = 0
const val ON_FOOT = 1
const val IN_VEHICLE = 2
const val UNKNOWN = 3

@IntDef(STILL, ON_FOOT, IN_VEHICLE, UNKNOWN)
@Retention(AnnotationRetention.SOURCE)
annotation class ResolvedActivity
