package com.adsamcik.tracker.common.debug

import android.content.Context

object Logger {
	private var genericDao: GenericLogDao? = null

	private var isInitialized = false

	fun initialize(context: Context) {
		synchronized(isInitialized) {
			if (isInitialized) return
			isInitialized = true
		}

		genericDao = DebugDatabase.getInstance(context).genericLogDao()
	}

	fun log(data: LogData) {
		require(isInitialized)
		requireNotNull(genericDao).insert(data)
	}
}
