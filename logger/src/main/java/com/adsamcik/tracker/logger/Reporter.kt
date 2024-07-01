package com.adsamcik.tracker.logger

import android.content.Context
import android.util.Log
import androidx.lifecycle.Observer
import com.adsamcik.tracker.shared.base.BuildConfig
import com.adsamcik.tracker.shared.base.isEmulator
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver

/**
 * Object that handles reporting of any message, error or exception that is passed to it.
 */
object Reporter {
	private var isInitialized = false
	private var isEnabled = false
	private const val TAG = "com.adsamcik.tracker-error"

	private val loggingObserver = Observer<Boolean> {
		isEnabled = it
	}

	/**
	 * Initializes reporter. Required for proper functionality.
	 */
	fun initialize(context: Context) {
		synchronized(this) {
			if (isInitialized) return
			isInitialized = true
		}

		if (isEmulator) return

		PreferenceObserver.observe(
				context,
				com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_key,
				com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_default,
				loggingObserver
		)
	}

	private fun checkInitialized() {
		if (!isInitialized) {
			throw UninitializedPropertyAccessException("Reporter needs to be initialized")
		}
	}

	fun report(exception: Throwable) {
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
	fun report(message: String) {
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
	fun log(message: String) {
		if (BuildConfig.DEBUG) {
			throw Exception(message)
		}

		checkInitialized()
		if (isEnabled) {
			Log.e(TAG, message)
		}
	}
}

