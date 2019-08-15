package com.adsamcik.signalcollector.common.module

import android.content.Context
import androidx.annotation.WorkerThread

interface ModuleInitializer {
	@WorkerThread
	fun initialize(context: Context)
}

