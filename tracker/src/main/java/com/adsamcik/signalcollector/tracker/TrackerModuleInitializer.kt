package com.adsamcik.signalcollector.tracker

import android.content.Context
import com.adsamcik.signalcollector.activity.ActivityRequestManager
import com.adsamcik.signalcollector.common.module.ModuleInitializer

@Suppress("unused")
class TrackerModuleInitializer : ModuleInitializer {
	override fun initialize(context: Context) {
		ActivityRequestManager.requestAutoTracking(context, )
	}

}