package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import androidx.annotation.MainThread
import com.adsamcik.signalcollector.tracker.data.CollectionTempData

internal interface TrackerComponent {
	@MainThread
	suspend fun onDisable(context: Context)

	@MainThread
	suspend fun onEnable(context: Context)
}