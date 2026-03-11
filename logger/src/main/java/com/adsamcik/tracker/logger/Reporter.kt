package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.logger.BuildConfig
import com.adsamcik.tracker.shared.base.isEmulator
import com.adsamcik.tracker.shared.base.logging.ErrorReporter
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Object that handles reporting of any message, error or exception that is passed to it.
 */
object Reporter : ErrorReporter {
	private var isInitialized = false
	private var isEnabled = false
	private const val TAG = "com.adsamcik.tracker-error"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var preferenceJob: Job? = null

	/**
	 * Initializes reporter. Required for proper functionality.
	 */
	fun initialize(context: Context) {
		synchronized(this) {
			if (isInitialized) return
			isInitialized = true
		}

		if (isEmulator) return

		preferenceJob?.cancel()
		preferenceJob = PreferenceFlows.boolean(
			context,
			com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_key,
			com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_default
		).onEach { isEnabled = it }
			.launchIn(scope)

	// Register as facade delegate so other modules can log without depending on :logger
	ReporterFacade.setDelegate(this)
	}

	private fun checkInitialized() {
		if (!isInitialized) {
			throw UninitializedPropertyAccessException("Reporter needs to be initialized")
		}
	}

	override fun report(exception: Throwable) {
		@Suppress("TooGenericExceptionThrown")
		if (BuildConfig.DEBUG) throw Exception(exception)

		checkInitialized()
		if (isEnabled) {
			exception.message?.let { Log.e(TAG, it) }
		}
	}

	/**
	 * Reports a message
	 *
	 * @param message Message that is reported
	 */
	@Suppress("TooGenericExceptionThrown")
	override fun report(message: String) {
		if (BuildConfig.DEBUG) {
			throw Exception(message)
		}

		checkInitialized()
		if (isEnabled) {
			Log.e(TAG, message)
		}
	}

	/**
	 * Message that is logged and only reported if an error or exception is raised.
	 *
	 * @param message Message that is logged
	 */
	@Suppress("TooGenericExceptionThrown")
	override fun log(message: String) {
		if (BuildConfig.DEBUG) {
			throw Exception(message)
		}

		checkInitialized()
		if (isEnabled) {
			Log.e(TAG, message)
		}
	}

	/**
	 * Logs an informational message scoped to a logical source without throwing in debug builds.
	 */
	fun i(source: String, message: String) {
		logWithSource(priority = Log.INFO, source = source, message = message)
	}

	/**
	 * Logs a warning message scoped to a logical source without throwing in debug builds.
	 */
	fun w(source: String, message: String) {
		logWithSource(priority = Log.WARN, source = source, message = message)
	}

	private fun logWithSource(priority: Int, source: String, message: String) {
		checkInitialized()
		if (!isEnabled && !BuildConfig.DEBUG) return
		val scopedTag = "$TAG.$source"
		Log.println(priority, scopedTag, message)
	}
}
