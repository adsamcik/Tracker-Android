package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.logger.BuildConfig
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.isEmulator
import com.adsamcik.tracker.shared.base.logging.ErrorReporter
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Object that handles reporting of any message, error or exception that is passed to it.
 */
object Reporter : ErrorReporter {
	@Volatile
	private var isInitialized = false

	@Volatile
	private var isEnabled = false
	private const val TAG = "com.adsamcik.tracker-error"
	private val scope = CoroutineScope(SupervisorJob() + DefaultDispatchersProvider.default)
	private var preferenceJob: Job? = null

	/**
	 * Initializes reporter. Required for proper functionality.
	 */
	fun initialize(context: Context) {
		synchronized(this) {
			if (isInitialized) return
			isInitialized = true
		}

		// Register facade delegate before emulator guard so logging works during development
		ReporterFacade.setDelegate(this)

		if (isEmulator) return

		preferenceJob?.cancel()
		preferenceJob = PreferenceFlows.boolean(
			context,
			com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_key,
			com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_default
		).onEach { isEnabled = it }
			.launchIn(scope)
	}

	private fun checkInitialized(): Boolean {
		if (!isInitialized) {
			Log.w(TAG, "Reporter used before initialization")
			return false
		}
		return true
	}

	private fun redactMessage(message: String): String = PiiRedactor.redact(message)

	private fun redactThrowable(exception: Throwable): Throwable = Throwable(
		redactMessage(exception.message ?: exception::class.java.simpleName),
		exception.cause?.let { redactThrowable(it) }
	).apply {
		stackTrace = exception.stackTrace
	}

	private fun shouldEmitErrorLogs(): Boolean = BuildConfig.DEBUG || isEnabled

	private fun debugTrace(message: String) {
		if (BuildConfig.DEBUG) {
			Exception(message).printStackTrace()
		}
	}

	override fun report(exception: Throwable) {
		if (!checkInitialized() || !shouldEmitErrorLogs()) return
		val sanitizedException = redactThrowable(exception)
		Log.e(TAG, sanitizedException.message.orEmpty(), sanitizedException)
		if (BuildConfig.DEBUG) {
			sanitizedException.printStackTrace()
		}
	}

	/**
	 * Reports a message
	 *
	 * @param message Message that is reported
	 */
	override fun report(message: String) {
		logError(message)
	}

	/**
	 * Message that is logged and only reported if an error or exception is raised.
	 *
	 * @param message Message that is logged
	 */
	override fun log(message: String) {
		logError(message)
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

	private fun logError(message: String) {
		if (!checkInitialized() || !shouldEmitErrorLogs()) return
		val redactedMessage = redactMessage(message)
		Log.e(TAG, redactedMessage)
		debugTrace(redactedMessage)
	}

	private fun logWithSource(priority: Int, source: String, message: String) {
		if (!checkInitialized()) return
		if (!isEnabled && !BuildConfig.DEBUG) return
		val scopedTag = "$TAG.$source"
		Log.println(priority, scopedTag, redactMessage(message))
	}
}
