package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.StringRes
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Logs important information.
 * Follows user preferences about logging.
 */
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
		genericDao = LogDatabase.database(context).genericLogDao()
	}

	@AnyThread
	fun log(data: LogData) {
		require(isInitialized)
		if (requireNotNull(preferences).getBooleanRes(
						R.string.settings_log_enabled_key,
						R.string.settings_log_enabled_default
				)) {
			launch {
				requireNotNull(genericDao).insert(data)
			}
		}
	}

	@AnyThread
	fun logWithPreference(data: LogData, @StringRes key: Int, @StringRes default: Int) {
		if (requireNotNull(preferences).getBooleanRes(key, default)) {
			log(data)
		}
	}

	@AnyThread
	inline fun <R> measureTimeMillis(name: String, method: () -> R): R {
		val result: R
		val time = kotlin.system.measureTimeMillis {
			result = method()
		}
		val message = "Measured time of $name is $time"
		log(LogData(message = message, source = "performance"))
		if (BuildConfig.DEBUG) {
			Log.d("TrackerPerf", message)
		}

		return result
	}
}
