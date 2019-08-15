package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.MainThread

internal interface TrackerComponent {
	@MainThread
	suspend fun onDisable(context: Context)

	@MainThread
	suspend fun onEnable(context: Context)
}
