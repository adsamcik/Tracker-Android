package com.adsamcik.signalcollector.tracker.component

import android.content.Context

interface TrackerComponent {
	fun onDisable(context: Context)
	fun onEnable(context: Context)
}