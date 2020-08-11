package com.adsamcik.tracker.shared.utils.debug

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.tracker.shared.base.BuildConfig
import com.adsamcik.tracker.shared.base.isEmulator
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Object that handles reporting of any message, error or exception that is passed to it.
 */
object Reporter {
	private var isInitialized = false
	private var isEnabled = false
	private val crashlytics = FirebaseCrashlytics.getInstance()

	private val loggingObserver = Observer<Boolean> {
		isEnabled = it
		crashlytics.setCrashlyticsCollectionEnabled(it)
	}

	/**
	 * Initializes reporter. Required for proper functionality.
	 */
	fun initialize(context: Context) {
		synchronized(isInitialized) {
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
		if (isEnabled) crashlytics.recordException(exception)
	}

	/**
	 * Reports a message
	 *
	 * @param message Message that is reported
	 */
	@Suppress("TooGenericExceptionThrown")
	fun report(message: String) {
		if (BuildConfig.DEBUG) throw Exception(message)

		checkInitialized()
		if (isEnabled) crashlytics.recordException(Throwable(message))
	}

	/**
	 * Message that is logged and only reported if an error or exception is raised.
	 *
	 * @param message Message that is logged
	 */
	@Suppress("TooGenericExceptionThrown")
	fun log(message: String) {
		if (BuildConfig.DEBUG) throw Exception(message)

		checkInitialized()
		if (isEnabled) crashlytics.log(message)
	}
}

