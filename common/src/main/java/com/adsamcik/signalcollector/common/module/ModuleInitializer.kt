package com.adsamcik.signalcollector.common.module

import android.content.Context

interface ModuleInitializer {
	fun initialize(context: Context)
}