package com.adsamcik.tracker.shared.utils.module

import androidx.annotation.WorkerThread

interface ModuleInitializer {
	val priority: Int
		get() = DEFAULT_PRIORITY

	@WorkerThread
	fun initialize()

	companion object {
		const val DEFAULT_PRIORITY: Int = 1_000
	}
}
