package com.adsamcik.signalcollector.common

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.crashlytics.android.Crashlytics
import io.fabric.sdk.android.Fabric
import java.lang.ref.WeakReference

object Reporter {
	private var isInitialized = false
	private var isEnabled = false

	private var context: WeakReference<Context>? = null

	private val loggingObserver = Observer<Boolean> {
		isEnabled = it
		val context = context?.get()
		if (it && context != null) {
			Fabric.with(context, Crashlytics())
		}
	}

	fun initialize(context: Context) {
		synchronized(isInitialized) {
			if (isInitialized) return
			isInitialized = true
		}

		if (isEmulator) return

		if (this.context?.get() == null) {
			this.context = WeakReference(context.applicationContext)
		}

		PreferenceObserver.observe(context, R.string.settings_error_reporting_key, R.string.settings_error_reporting_default, loggingObserver)
	}

	private fun checkInitialized() {
		if (!isInitialized) throw UninitializedPropertyAccessException("Reporter needs to be initialized")
	}

	fun logException(exception: Throwable) {
		checkInitialized()
		if (isEnabled) Crashlytics.logException(exception)
	}
}