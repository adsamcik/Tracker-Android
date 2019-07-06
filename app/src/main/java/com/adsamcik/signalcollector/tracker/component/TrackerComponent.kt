package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import androidx.annotation.MainThread

interface TrackerComponent {
	@MainThread
	suspend fun onDisable(context: Context)

	@MainThread
	suspend fun onEnable(context: Context)
}