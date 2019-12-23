package com.adsamcik.tracker.common.module

import android.content.Context
import androidx.annotation.WorkerThread

interface ModuleInitializer {
	@WorkerThread
	fun initialize(context: Context)
}

