package com.adsamcik.tracker.common.debug

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.StringRes
import com.adsamcik.tracker.common.preference.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

object Logger : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job


	private var genericDao: GenericLogDao? = null

	private var isInitialized = false

	private var preferences: Preferences? = null

	fun initialize(context: Context) {
		synchronized(isInitialized) {
			if (isInitialized) return
			isInitialized = true
		}

		preferences = Preferences.getPref(context)
		genericDao = DebugDatabase.getInstance(context).genericLogDao()
	}

	@AnyThread
	fun log(data: LogData) {
		require(isInitialized)
		launch {
			requireNotNull(genericDao).insert(data)
		}
	}

	@AnyThread
	fun logWithPreference(data: LogData, @StringRes key: Int, @StringRes default: Int) {
		if (requireNotNull(preferences).getBooleanRes(key, default)) log(data)
	}
}
